package com.jankinwu.flynarwhal.web.autoskip;

import lombok.Data;

@Data
public class AutoSkipConfig {
    private String baseUrl = "";
    private String username = "";
    private String encryptedPassword = "";
    private String encryptedToken = "";
    private boolean autoApply = false;
    private boolean overwriteExisting = false;
    private boolean scheduledScan = true;
    private int scanIntervalHours = 12;
    private int minimumEpisodes = 3;
    private double consensusThreshold = 0.75;
    private int toleranceSeconds = 8;
    private long lastScheduledScanEpochMs = 0;
}
