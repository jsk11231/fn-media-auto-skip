package com.jankinwu.flynarwhal.web.autoskip;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoSkipConfigStore {
    private static final Path DATA_DIR = Path.of("data");
    private static final Path CONFIG_PATH = DATA_DIR.resolve("autoskip-config.json");
    private static final Path KEY_PATH = DATA_DIR.resolve(".autoskip.key");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;

    private final ObjectMapper objectMapper;
    private volatile AutoSkipConfig config = new AutoSkipConfig();
    private volatile byte[] key;

    @PostConstruct
    public synchronized void load() {
        try {
            Files.createDirectories(DATA_DIR);
            key = loadOrCreateKey();
            if (Files.exists(CONFIG_PATH)) {
                config = objectMapper.readValue(CONFIG_PATH.toFile(), AutoSkipConfig.class);
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法读取自动跳过配置", e);
        }
    }

    public AutoSkipConfig current() {
        return config;
    }

    public synchronized void updatePublicSettings(AutoSkipModels.SettingsRequest request) {
        AutoSkipConfig next = config;
        next.setAutoApply(request.isAutoApply());
        next.setOverwriteExisting(request.isOverwriteExisting());
        next.setScheduledScan(request.isScheduledScan());
        next.setScanIntervalHours(clamp(request.getScanIntervalHours(), 1, 168));
        next.setMinimumEpisodes(clamp(request.getMinimumEpisodes(), 2, 20));
        next.setConsensusThreshold(clamp(request.getConsensusThreshold(), 0.5, 1.0));
        next.setToleranceSeconds(clamp(request.getToleranceSeconds(), 2, 30));
        persist();
    }

    public synchronized void saveConnection(String baseUrl, String username, String password, String token) {
        config.setBaseUrl(normalizeBaseUrl(baseUrl));
        config.setUsername(username == null ? "" : username.trim());
        if (password != null && !password.isBlank()) {
            config.setEncryptedPassword(encrypt(password));
        }
        if (token != null && !token.isBlank()) {
            config.setEncryptedToken(encrypt(token));
        }
        persist();
    }

    public synchronized void saveToken(String token) {
        config.setEncryptedToken(token == null || token.isBlank() ? "" : encrypt(token));
        persist();
    }

    public synchronized void markScheduledScan(long epochMs) {
        config.setLastScheduledScanEpochMs(epochMs);
        persist();
    }

    public String password() {
        return decrypt(config.getEncryptedPassword());
    }

    public String token() {
        return decrypt(config.getEncryptedToken());
    }

    public boolean isConfigured() {
        return !config.getBaseUrl().isBlank() && !config.getUsername().isBlank() && !password().isBlank();
    }

    private synchronized void persist() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(CONFIG_PATH.toFile(), config);
            setPrivatePermissions(CONFIG_PATH);
        } catch (Exception e) {
            throw new IllegalStateException("无法保存自动跳过配置", e);
        }
    }

    private byte[] loadOrCreateKey() throws Exception {
        if (Files.exists(KEY_PATH)) {
            return Base64.getDecoder().decode(Files.readString(KEY_PATH, StandardCharsets.UTF_8).trim());
        }
        byte[] generated = new byte[32];
        RANDOM.nextBytes(generated);
        Files.writeString(KEY_PATH, Base64.getEncoder().encodeToString(generated), StandardCharsets.UTF_8);
        setPrivatePermissions(KEY_PATH);
        return generated;
    }

    private String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("凭据加密失败", e);
        }
    }

    private String decrypt(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            byte[] iv = new byte[12];
            byte[] encrypted = new byte[bytes.length - iv.length];
            System.arraycopy(bytes, 0, iv, 0, iv.length);
            System.arraycopy(bytes, iv.length, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("无法解密已保存的凭据，将要求重新连接");
            return "";
        }
    }

    private String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("飞牛地址不能为空");
        }
        String value = raw.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private void setPrivatePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows development hosts do not expose POSIX permissions.
        } catch (Exception e) {
            log.debug("无法设置文件权限: {}", path, e);
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
