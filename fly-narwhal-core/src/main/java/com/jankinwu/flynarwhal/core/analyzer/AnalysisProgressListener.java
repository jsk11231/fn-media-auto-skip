package com.jankinwu.flynarwhal.core.analyzer;

@FunctionalInterface
public interface AnalysisProgressListener {
    AnalysisProgressListener NOOP = (stage, completed, total) -> {};

    void onProgress(String stage, int completed, int total);
}
