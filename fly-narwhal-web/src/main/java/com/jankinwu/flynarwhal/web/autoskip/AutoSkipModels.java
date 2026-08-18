package com.jankinwu.flynarwhal.web.autoskip;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class AutoSkipModels {
    private AutoSkipModels() {}

    @Data
    public static class ConnectRequest {
        private String baseUrl;
        private String username;
        private String password;
    }

    @Data
    public static class SettingsRequest {
        private boolean autoApply;
        private boolean overwriteExisting;
        private boolean scheduledScan = true;
        private int scanIntervalHours = 12;
        private int minimumEpisodes = 3;
        private double consensusThreshold = 0.75;
        private int toleranceSeconds = 8;
    }

    @Data
    public static class BulkApplyRequest {
        private int minimumPercent = 80;
    }

    @Data
    @NoArgsConstructor
    public static class BulkApplyResult {
        private int eligible;
        private int applied;
        private int skipped;
        private int failed;
        private List<String> failures = new ArrayList<>();
    }

    @Data
    @AllArgsConstructor
    public static class ApiResponse<T> {
        private boolean success;
        private String message;
        private T data;

        public static <T> ApiResponse<T> ok(String message, T data) {
            return new ApiResponse<>(true, message, data);
        }

        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(false, message, null);
        }
    }

    @Data
    @NoArgsConstructor
    public static class Dashboard {
        private boolean configured;
        private String baseUrl;
        private String username;
        private boolean autoApply;
        private boolean overwriteExisting;
        private boolean scheduledScan;
        private int scanIntervalHours;
        private int minimumEpisodes;
        private double consensusThreshold;
        private int toleranceSeconds;
        private ScanProgress scan = new ScanProgress();
        private List<SeasonSuggestion> seasons = new ArrayList<>();
        private boolean ffmpegAvailable;
        private boolean chromaprintAvailable;
    }

    @Data
    @NoArgsConstructor
    public static class ScanProgress {
        private boolean running;
        private String stage = "等待扫描";
        private int discovered;
        private int queued;
        private String lastError = "";
        private LocalDateTime lastStarted;
        private LocalDateTime lastFinished;
    }

    @Data
    @NoArgsConstructor
    public static class SeasonSuggestion {
        private String seasonGuid;
        private String tvTitle;
        private Integer seasonNumber;
        private String analysisStatus;
        private int episodeCount;
        private int introSamples;
        private int endingSamples;
        private int skipOpening;
        private int skipEnding;
        private double introConsensus;
        private double endingConsensus;
        private int consensusPercent;
        private String progressStage = "";
        private int progressCompleted;
        private int progressTotal;
        private int progressPercent;
        private boolean safe;
        private String reason;
        private String applyStatus = "";
        private LocalDateTime updatedAt;
    }
}
