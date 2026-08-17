package com.jankinwu.flynarwhal.core.analyzer;

import com.jankinwu.flynarwhal.core.data.AnalysisMode;
import com.jankinwu.flynarwhal.core.data.AnalyzerAction;
import com.jankinwu.flynarwhal.core.data.QueuedEpisode;
import com.jankinwu.flynarwhal.core.data.Segment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class BatchBlackFrameAnalyzer implements MediaFileAnalyzer {

    private final BlackFrameAnalyzer blackFrameAnalyzer;

    @Override
    public void analyze(List<QueuedEpisode> episodes, AnalysisMode mode) {
        analyze(episodes, mode, AnalysisProgressListener.NOOP);
    }

    @Override
    public void analyze(List<QueuedEpisode> episodes, AnalysisMode mode, AnalysisProgressListener listener) {
        // BlackFrame only supports Credits
        if (mode != AnalysisMode.CREDITS) return;

        log.info("Starting BlackFrame Analysis for {} episodes", episodes.size());
        for (int i = 0; i < episodes.size(); i++) {
            QueuedEpisode episode = episodes.get(i);
            if (episode.isCreditsAnalyzed()) {
                listener.onProgress("检测片尾黑场", i + 1, episodes.size());
                continue;
            }

            try {
                Segment segment = blackFrameAnalyzer.analyzeCredits(episode);
                if (segment != null && segment.isValid()) {
                    log.info("Found Credits via BlackFrame for {}: {}-{}", episode.getPath(), segment.getStart(), segment.getEnd());
                    episode.setCreditsSegment(segment);
                    episode.setCreditsAnalyzed(true);
                    episode.setCreditsAction(AnalyzerAction.BLACK_FRAME);
                }
            } catch (Exception e) {
                log.error("Error in BlackFrame analysis for " + episode.getPath(), e);
            }
            listener.onProgress("检测片尾黑场", i + 1, episodes.size());
        }
    }
}
