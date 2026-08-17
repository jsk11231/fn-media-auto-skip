package com.jankinwu.flynarwhal.web.autoskip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class FnMediaClient {
    private static final String API_KEY = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
    private static final String API_SECRET = "16CCEB3D-AB42-077D-36A1-F355324E4237";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/143 Safari/537.36";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HostnameVerifier FNOS_HOSTNAME_VERIFIER = (hostname, session) -> true;

    private final ObjectMapper objectMapper;
    private volatile SSLSocketFactory sslSocketFactory;

    public String login(String baseUrl, String username, String password) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", username);
        body.put("password", password);
        body.put("app_name", "trimemedia-web");
        JsonNode data = request(baseUrl, "", "POST", "/v/api/v1/login", body, Map.of());
        String token = data.path("token").asText("");
        if (token.isBlank()) {
            throw new FnApiException(401, "登录成功但未返回 token");
        }
        return token;
    }

    public JsonNode mediaDatabases(String baseUrl, String token) {
        return request(baseUrl, token, "GET", "/v/api/v1/mediadb/list", null, Map.of());
    }

    public JsonNode tvItems(String baseUrl, String token, String libraryGuid, int page, int pageSize) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("ancestor_guid", libraryGuid);
        body.put("exclude_grouped_video", 1);
        body.put("sort_type", "DESC");
        body.put("sort_column", "create_time");
        body.put("page_size", pageSize);
        body.put("page", page);
        ObjectNode tags = body.putObject("tags");
        ArrayNode types = tags.putArray("type");
        types.add("TV");
        tags.putNull("genres");
        tags.putNull("resolution");
        tags.putNull("color_range");
        tags.putNull("locate");
        tags.putNull("decade");
        tags.putNull("recognition_status");
        tags.putNull("watched");
        tags.putNull("audio_type");
        return request(baseUrl, token, "POST", "/v/api/v1/item/list", body, Map.of());
    }

    public JsonNode seasons(String baseUrl, String token, String tvGuid) {
        return request(baseUrl, token, "GET", "/v/api/v1/season/list/" + encodePath(tvGuid), null, Map.of());
    }

    public JsonNode episodes(String baseUrl, String token, String seasonGuid) {
        return request(baseUrl, token, "GET", "/v/api/v1/episode/list/" + encodePath(seasonGuid), null, Map.of());
    }

    public JsonNode streams(String baseUrl, String token, String episodeGuid) {
        return request(baseUrl, token, "GET", "/v/api/v1/stream/list/" + encodePath(episodeGuid), null, Map.of());
    }

    public JsonNode playInfo(String baseUrl, String token, String episodeGuid) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("item_guid", episodeGuid);
        return request(baseUrl, token, "POST", "/v/api/v1/play/info", body, Map.of());
    }

    public void setSkipConfig(String baseUrl, String token, String seasonGuid, int skipOpening, int skipEnding) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("guid", seasonGuid);
        body.put("skip_opening", skipOpening);
        body.put("skip_ending", skipEnding);
        request(baseUrl, token, "POST", "/v/api/v1/play/setConfigByItem", body, Map.of());
    }

    private JsonNode request(String baseUrl, String token, String method, String path, JsonNode body, Map<String, String> query) {
        HttpURLConnection connection = null;
        try {
            String bodyText = body == null ? "" : objectMapper.writeValueAsString(body);
            String queryString = buildQuery(query);
            URI uri = URI.create(baseUrl + path + (queryString.isEmpty() ? "" : "?" + queryString));
            connection = openConnection(uri);
            connection.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
            connection.setReadTimeout((int) Duration.ofSeconds(30).toMillis());
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("X-Trim-Client", "web");
            connection.setRequestProperty("X-Trim-Client-Version", "616");
            connection.setRequestProperty("Authx", authx(path, bodyText, query));
            if (token != null && !token.isBlank()) {
                connection.setRequestProperty("Authorization", token);
                connection.setRequestProperty("Cookie", "Trim-MC-token=" + token);
            }
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                connection.getOutputStream().write(bodyText.getBytes(StandardCharsets.UTF_8));
            }
            int statusCode = connection.getResponseCode();
            InputStream responseStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseBody = responseStream == null
                    ? ""
                    : new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
            if (responseStream != null) {
                responseStream.close();
            }
            JsonNode root;
            try {
                root = objectMapper.readTree(responseBody);
            } catch (Exception e) {
                throw new FnApiException(statusCode, "飞牛接口返回了非 JSON 内容");
            }
            int code = root.path("code").asInt(statusCode >= 400 ? statusCode : 0);
            if (statusCode >= 400 || code != 0) {
                String message = root.path("msg").asText(root.path("message").asText("请求失败"));
                throw new FnApiException(code == 0 ? statusCode : code, message);
            }
            return root.has("data") ? root.get("data") : root;
        } catch (FnApiException e) {
            throw e;
        } catch (Exception e) {
            throw new FnApiException(-1, "无法连接飞牛影视：" + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(URI uri) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        if (connection instanceof HttpsURLConnection httpsConnection) {
            httpsConnection.setSSLSocketFactory(sslSocketFactory());
            httpsConnection.setHostnameVerifier(FNOS_HOSTNAME_VERIFIER);
        }
        return connection;
    }

    private SSLSocketFactory sslSocketFactory() throws Exception {
        SSLSocketFactory current = sslSocketFactory;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (sslSocketFactory == null) {
                TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }};
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustAll, RANDOM);
                sslSocketFactory = sslContext.getSocketFactory();
            }
            return sslSocketFactory;
        }
    }

    private String authx(String path, String body, Map<String, String> query) throws Exception {
        String nonce = String.valueOf(100000 + RANDOM.nextInt(900000));
        String timestamp = String.valueOf(System.currentTimeMillis());
        String dataMd5;
        if (body != null && !body.isEmpty()) {
            dataMd5 = md5(body);
        } else if (query != null && !query.isEmpty()) {
            List<Map.Entry<String, String>> entries = new ArrayList<>(query.entrySet());
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            String canonical = String.join("&", entries.stream()
                    .filter(entry -> entry.getValue() != null)
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .toList());
            dataMd5 = md5(canonical);
        } else {
            dataMd5 = md5("");
        }
        String sign = md5(String.join("_", API_KEY, path, nonce, timestamp, dataMd5, API_SECRET));
        return "nonce=" + nonce + "&timestamp=" + timestamp + "&sign=" + sign;
    }

    private String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private String buildQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        query.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return String.join("&", ordered.entrySet().stream()
                .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                .toList());
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String encodePath(String value) {
        return urlEncode(value).replace("%2F", "/");
    }

    public static class FnApiException extends RuntimeException {
        private final int code;

        public FnApiException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}
