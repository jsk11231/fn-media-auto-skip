package com.jankinwu.flynarwhal.web.autoskip;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.jankinwu.flynarwhal.core.data.AnalysisStatus;
import com.jankinwu.flynarwhal.core.dto.request.EpisodeDetailRequest;
import com.jankinwu.flynarwhal.core.ffmpeg.FFmpegWrapper;
import com.jankinwu.flynarwhal.web.entity.EpisodeSegment;
import com.jankinwu.flynarwhal.web.entity.TvSeasonInfo;
import com.jankinwu.flynarwhal.web.mapper.EpisodeSegmentMapper;
import com.jankinwu.flynarwhal.web.mapper.TvSeasonInfoMapper;
import com.jankinwu.flynarwhal.web.service.AnalysisService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoSkipService {
    private final AutoSkipConfigStore configStore;
    private final FnMediaClient fnMediaClient;
    private final AnalysisService analysisService;
    private final TvSeasonInfoMapper tvSeasonInfoMapper;
    private final EpisodeSegmentMapper episodeSegmentMapper;

    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "AutoSkipDiscovery");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private final AutoSkipModels.ScanProgress scanProgress = new AutoSkipModels.ScanProgress();
    private final Map<String, String> applyStatuses = new ConcurrentHashMap<>();

    public String connect(String baseUrl, String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }
        String normalized = normalizeBaseUrl(baseUrl);
        String token = fnMediaClient.login(normalized, username.trim(), password);
        configStore.saveConnection(normalized, username, password, token);
        return "连接成功";
    }

    public AutoSkipModels.Dashboard dashboard() {
        AutoSkipConfig config = configStore.current();
        AutoSkipModels.Dashboard dashboard = new AutoSkipModels.Dashboard();
        dashboard.setConfigured(configStore.isConfigured());
        dashboard.setBaseUrl(config.getBaseUrl());
        dashboard.setUsername(config.getUsername());
        dashboard.setAutoApply(config.isAutoApply());
        dashboard.setOverwriteExisting(config.isOverwriteExisting());
        dashboard.setScheduledScan(config.isScheduledScan());
        dashboard.setScanIntervalHours(config.getScanIntervalHours());
        dashboard.setMinimumEpisodes(config.getMinimumEpisodes());
        dashboard.setConsensusThreshold(config.getConsensusThreshold());
        dashboard.setToleranceSeconds(config.getToleranceSeconds());
        dashboard.setScan(copyProgress());
        dashboard.setFfmpegAvailable(FFmpegWrapper.isFfmpegAvailable());
        dashboard.setChromaprintAvailable(FFmpegWrapper.isChromaprintMuxerAvailable());

        List<TvSeasonInfo> seasons = tvSeasonInfoMapper.selectList(null);
        seasons.sort(Comparator.comparingInt(this::statusRank)
                .thenComparing(TvSeasonInfo::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder())));
        dashboard.setSeasons(seasons.stream().map(this::buildSuggestion).toList());
        return dashboard;
    }

    public void updateSettings(AutoSkipModels.SettingsRequest request) {
        configStore.updatePublicSettings(request);
    }

    public String startScan(boolean force) {
        if (!configStore.isConfigured()) {
            throw new IllegalStateException("请先连接飞牛影视");
        }
        if (!scanRunning.compareAndSet(false, true)) {
            return "扫描已在运行";
        }
        scanExecutor.submit(() -> discoverAndQueue(force));
        return "扫描已启动";
    }

    public String apply(String seasonGuid, boolean force) {
        TvSeasonInfo season = tvSeasonInfoMapper.selectById(seasonGuid);
        if (season == null) {
            throw new IllegalArgumentException("未找到该剧季");
        }
        AutoSkipModels.SeasonSuggestion suggestion = buildSuggestion(season);
        if (!suggestion.isSafe() && !force) {
            throw new IllegalStateException("分析一致率不足，需要人工确认后强制应用");
        }
        applySuggestion(suggestion, false);
        return applyStatuses.getOrDefault(seasonGuid, "已处理");
    }

    @EventListener
    public void afterAnalysis(SeasonAnalysisCompletedEvent event) {
        try {
            TvSeasonInfo season = tvSeasonInfoMapper.selectById(event.seasonGuid());
            if (season == null) {
                return;
            }
            AutoSkipModels.SeasonSuggestion suggestion = buildSuggestion(season);
            applyStatuses.put(event.seasonGuid(), suggestion.isSafe() ? "分析完成，建议值可用" : "需要人工复核");
            if (configStore.current().isAutoApply() && suggestion.isSafe()) {
                applySuggestion(suggestion, true);
            }
        } catch (Exception e) {
            log.error("分析后自动写入失败: {}", event.seasonGuid(), e);
            applyStatuses.put(event.seasonGuid(), "写入失败：" + safeMessage(e));
        }
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void scheduledScanTick() {
        AutoSkipConfig config = configStore.current();
        if (!config.isScheduledScan() || !configStore.isConfigured() || scanRunning.get()) {
            return;
        }
        long intervalMs = config.getScanIntervalHours() * 3_600_000L;
        long now = System.currentTimeMillis();
        if (now - config.getLastScheduledScanEpochMs() < intervalMs) {
            return;
        }
        configStore.markScheduledScan(now);
        startScan(false);
    }

    @PreDestroy
    public void shutdown() {
        scanExecutor.shutdownNow();
    }

    private void discoverAndQueue(boolean force) {
        synchronized (scanProgress) {
            scanProgress.setRunning(true);
            scanProgress.setStage("正在登录飞牛影视");
            scanProgress.setDiscovered(0);
            scanProgress.setQueued(0);
            scanProgress.setLastError("");
            scanProgress.setLastStarted(LocalDateTime.now());
        }
        try {
            String token = freshToken();
            AutoSkipConfig config = configStore.current();
            JsonNode databases = fnMediaClient.mediaDatabases(config.getBaseUrl(), token);
            for (JsonNode database : array(databases)) {
                if (!"tv".equalsIgnoreCase(database.path("category").asText())) {
                    continue;
                }
                String libraryGuid = database.path("guid").asText();
                discoverLibrary(config.getBaseUrl(), token, libraryGuid, force);
            }
            synchronized (scanProgress) {
                scanProgress.setStage("扫描完成，分析队列正在后台运行");
                scanProgress.setLastFinished(LocalDateTime.now());
            }
        } catch (Exception e) {
            log.error("自动发现媒体库失败", e);
            synchronized (scanProgress) {
                scanProgress.setLastError(safeMessage(e));
                scanProgress.setStage("扫描失败");
                scanProgress.setLastFinished(LocalDateTime.now());
            }
        } finally {
            scanProgress.setRunning(false);
            scanRunning.set(false);
        }
    }

    private void discoverLibrary(String baseUrl, String token, String libraryGuid, boolean force) {
        int page = 1;
        int pageSize = 100;
        int total;
        do {
            setScanStage("正在读取电视节目，第 " + page + " 页");
            JsonNode response = fnMediaClient.tvItems(baseUrl, token, libraryGuid, page, pageSize);
            JsonNode list = response.path("list");
            total = response.path("total").asInt(list.size());
            for (JsonNode tv : array(list)) {
                discoverTv(baseUrl, token, tv, force);
            }
            page++;
        } while ((page - 1) * pageSize < total);
    }

    private void discoverTv(String baseUrl, String token, JsonNode tv, boolean force) {
        String tvGuid = tv.path("guid").asText();
        String tvTitle = tv.path("title").asText("未命名节目");
        if (tvGuid.isBlank()) {
            return;
        }
        JsonNode seasons = fnMediaClient.seasons(baseUrl, token, tvGuid);
        for (JsonNode season : array(seasons)) {
            String seasonGuid = season.path("guid").asText();
            if (seasonGuid.isBlank()) {
                continue;
            }
            synchronized (scanProgress) {
                scanProgress.setDiscovered(scanProgress.getDiscovered() + 1);
            }
            TvSeasonInfo existing = tvSeasonInfoMapper.selectById(seasonGuid);
            if (!force && existing != null && existing.getStatus() == AnalysisStatus.COMPLETED) {
                continue;
            }
            if (!force && analysisService.isQueuedOrRunning(seasonGuid)) {
                continue;
            }
            int seasonNumber = season.path("season_number").asInt(0);
            queueSeason(baseUrl, token, seasonGuid, tvTitle, seasonNumber);
        }
    }

    private void queueSeason(String baseUrl, String token, String seasonGuid, String tvTitle, int seasonNumber) {
        setScanStage("读取《" + tvTitle + "》第 " + seasonNumber + " 季");
        JsonNode episodes = fnMediaClient.episodes(baseUrl, token, seasonGuid);
        List<EpisodeDetailRequest> requests = new ArrayList<>();
        String seasonPath = "";
        for (JsonNode episode : array(episodes)) {
            String episodeGuid = episode.path("guid").asText();
            if (episodeGuid.isBlank()) {
                continue;
            }
            JsonNode streamData = fnMediaClient.streams(baseUrl, token, episodeGuid);
            String filePath = firstPlayablePath(streamData);
            if (filePath.isBlank()) {
                filePath = episode.path("file_name").asText("");
            }
            if (filePath.isBlank()) {
                continue;
            }
            if (seasonPath.isBlank()) {
                try {
                    Path parent = Path.of(filePath).getParent();
                    seasonPath = parent == null ? "" : parent.toString();
                } catch (Exception ignored) {
                }
            }
            EpisodeDetailRequest request = new EpisodeDetailRequest();
            request.setGuid(episodeGuid);
            request.setFilePath(filePath);
            request.setEpisodeNumber(episode.path("episode_number").asInt(requests.size() + 1));
            requests.add(request);
        }
        if (requests.size() < 2) {
            applyStatuses.put(seasonGuid, "剧集不足 2 集，已跳过");
            return;
        }
        analysisService.enqueueAnalyzeSeason(seasonGuid, seasonPath, requests, tvTitle, seasonNumber);
        synchronized (scanProgress) {
            scanProgress.setQueued(scanProgress.getQueued() + 1);
        }
    }

    private AutoSkipModels.SeasonSuggestion buildSuggestion(TvSeasonInfo season) {
        List<EpisodeSegment> segments = episodeSegmentMapper.selectList(
                new QueryWrapper<EpisodeSegment>().eq("season_guid", season.getSeasonGuid()).orderByAsc("episode_number"));
        AutoSkipConfig config = configStore.current();
        List<Double> introValues = new ArrayList<>();
        List<Double> endingValues = new ArrayList<>();
        for (EpisodeSegment segment : segments) {
            if (segment.getIntroStart() != null && segment.getIntroEnd() != null
                    && segment.getIntroStart().doubleValue() <= 30
                    && segment.getIntroEnd().doubleValue() >= 5
                    && segment.getIntroEnd().doubleValue() <= 600) {
                introValues.add(segment.getIntroEnd().doubleValue());
            }
            if (segment.getDuration() != null && segment.getDuration() > 0 && segment.getCreditsStart() != null) {
                double endingDuration = segment.getDuration() - segment.getCreditsStart().doubleValue();
                if (endingDuration >= 5 && endingDuration <= 600) {
                    endingValues.add(endingDuration);
                }
            }
        }
        Consensus intro = consensus(introValues, Math.max(segments.size(), 1), config);
        Consensus ending = consensus(endingValues, Math.max(segments.size(), 1), config);

        AutoSkipModels.SeasonSuggestion result = new AutoSkipModels.SeasonSuggestion();
        result.setSeasonGuid(season.getSeasonGuid());
        result.setTvTitle(season.getTvTitle());
        result.setSeasonNumber(season.getSeasonNumber());
        result.setAnalysisStatus(season.getStatus() == null ? "UNKNOWN" : season.getStatus().name());
        result.setEpisodeCount(segments.size());
        result.setIntroSamples(introValues.size());
        result.setEndingSamples(endingValues.size());
        result.setSkipOpening(intro.safe ? conservativeSeconds(intro.median) : 0);
        result.setSkipEnding(ending.safe ? conservativeSeconds(ending.median) : 0);
        result.setIntroConsensus(intro.score);
        result.setEndingConsensus(ending.score);
        AnalysisService.LiveProgress progress = analysisService.getLiveProgress(season.getSeasonGuid());
        if (progress != null) {
            result.setProgressStage(progress.stage());
            result.setProgressCompleted(progress.completed());
            result.setProgressTotal(progress.total());
            result.setProgressPercent(progress.percent());
        }
        boolean completed = season.getStatus() == AnalysisStatus.COMPLETED || season.getStatus() == AnalysisStatus.PARTIAL_SUCCESS;
        result.setSafe(completed && (intro.safe || ending.safe));
        if (season.getStatus() == AnalysisStatus.FAILED) {
            result.setReason("分析失败，请重新扫描；若再次失败请查看应用日志");
        } else if (!completed) {
            result.setReason("分析尚未完成");
        } else if (!intro.safe && !ending.safe) {
            result.setReason("多集结果不一致，需人工复核");
        } else if (!intro.safe) {
            result.setReason("片尾可信；片头疑似存在冷开场或长度变化");
        } else if (!ending.safe) {
            result.setReason("片头可信；片尾长度不一致");
        } else {
            result.setReason("多集结果一致，可安全应用");
        }
        result.setApplyStatus(applyStatuses.getOrDefault(season.getSeasonGuid(), ""));
        result.setUpdatedAt(season.getUpdateTime());
        return result;
    }

    private int statusRank(TvSeasonInfo season) {
        if (season.getStatus() == AnalysisStatus.IN_PROGRESS) return 0;
        if (season.getStatus() == AnalysisStatus.PENDING) return 1;
        return 2;
    }

    private Consensus consensus(List<Double> values, int totalEpisodes, AutoSkipConfig config) {
        if (values.size() < config.getMinimumEpisodes()) {
            return new Consensus(0, 0, false);
        }
        List<Double> sorted = values.stream().sorted().toList();
        double median = median(sorted);
        long nearMedian = sorted.stream().filter(value -> Math.abs(value - median) <= config.getToleranceSeconds()).count();
        double sampleConsensus = nearMedian / (double) sorted.size();
        double coverage = values.size() / (double) totalEpisodes;
        double score = Math.min(sampleConsensus, coverage);
        return new Consensus(median, round3(score), score >= config.getConsensusThreshold());
    }

    private void applySuggestion(AutoSkipModels.SeasonSuggestion suggestion, boolean automatic) {
        AutoSkipConfig config = configStore.current();
        String token = freshToken();
        List<EpisodeSegment> segments = episodeSegmentMapper.selectList(
                new QueryWrapper<EpisodeSegment>().eq("season_guid", suggestion.getSeasonGuid()).orderByAsc("episode_number"));
        String episodeGuid = segments.stream().map(EpisodeSegment::getGuid).filter(Objects::nonNull)
                .filter(value -> !value.isBlank()).findFirst().orElse("");
        CurrentConfig current = currentConfig(config.getBaseUrl(), token, episodeGuid);
        int opening = current.opening > 0 && !config.isOverwriteExisting() ? current.opening : suggestion.getSkipOpening();
        int ending = current.ending > 0 && !config.isOverwriteExisting() ? current.ending : suggestion.getSkipEnding();
        if (opening == current.opening && ending == current.ending) {
            applyStatuses.put(suggestion.getSeasonGuid(), "已有设置，未覆盖");
            return;
        }
        if (opening <= 0 && ending <= 0) {
            applyStatuses.put(suggestion.getSeasonGuid(), "没有可写入的可信结果");
            return;
        }
        fnMediaClient.setSkipConfig(config.getBaseUrl(), token, suggestion.getSeasonGuid(), opening, ending);
        applyStatuses.put(suggestion.getSeasonGuid(), (automatic ? "已自动应用：" : "已应用：")
                + "片头 " + opening + " 秒，片尾 " + ending + " 秒");
    }

    private CurrentConfig currentConfig(String baseUrl, String token, String episodeGuid) {
        if (episodeGuid.isBlank()) {
            return new CurrentConfig(0, 0);
        }
        try {
            JsonNode playInfo = fnMediaClient.playInfo(baseUrl, token, episodeGuid);
            JsonNode playConfig = playInfo.path("play_config");
            return new CurrentConfig(playConfig.path("skip_opening").asInt(0), playConfig.path("skip_ending").asInt(0));
        } catch (Exception e) {
            log.warn("读取现有跳过设置失败，将按未设置处理: {}", safeMessage(e));
            return new CurrentConfig(0, 0);
        }
    }

    private String freshToken() {
        AutoSkipConfig config = configStore.current();
        String password = configStore.password();
        if (config.getBaseUrl().isBlank() || config.getUsername().isBlank() || password.isBlank()) {
            throw new IllegalStateException("飞牛影视连接信息不完整");
        }
        String token = fnMediaClient.login(config.getBaseUrl(), config.getUsername(), password);
        configStore.saveToken(token);
        return token;
    }

    private String firstPlayablePath(JsonNode streamData) {
        JsonNode files = streamData.path("files");
        for (JsonNode file : array(files)) {
            String path = file.path("path").asText("").trim();
            if (!path.isBlank() && file.path("can_play").asInt(1) != 0) {
                return path;
            }
        }
        for (JsonNode file : array(files)) {
            String path = file.path("path").asText("").trim();
            if (!path.isBlank()) {
                return path;
            }
        }
        return "";
    }

    private Iterable<JsonNode> array(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }

    private double median(List<Double> sorted) {
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0 : sorted.get(middle);
    }

    private int conservativeSeconds(double value) {
        return Math.max(0, Math.min(600, (int) Math.floor(value - 1.5)));
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private AutoSkipModels.ScanProgress copyProgress() {
        synchronized (scanProgress) {
            AutoSkipModels.ScanProgress copy = new AutoSkipModels.ScanProgress();
            copy.setRunning(scanProgress.isRunning());
            copy.setStage(scanProgress.getStage());
            copy.setDiscovered(scanProgress.getDiscovered());
            copy.setQueued(scanProgress.getQueued());
            copy.setLastError(scanProgress.getLastError());
            copy.setLastStarted(scanProgress.getLastStarted());
            copy.setLastFinished(scanProgress.getLastFinished());
            return copy;
        }
    }

    private void setScanStage(String stage) {
        synchronized (scanProgress) {
            scanProgress.setStage(stage);
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

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private record Consensus(double median, double score, boolean safe) {}
    private record CurrentConfig(int opening, int ending) {}
}
