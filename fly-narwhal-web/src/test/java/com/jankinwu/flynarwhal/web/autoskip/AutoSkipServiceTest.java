package com.jankinwu.flynarwhal.web.autoskip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSkipServiceTest {

    @Test
    void bulkEligibilityUsesDisplayedConsensusAndIncludesExactThreshold() {
        AutoSkipModels.SeasonSuggestion suggestion = new AutoSkipModels.SeasonSuggestion();
        suggestion.setAnalysisStatus("COMPLETED");
        suggestion.setSkipEnding(90);
        suggestion.setIntroConsensus(0.8);
        suggestion.setEndingConsensus(0.6);
        suggestion.setConsensusPercent(AutoSkipService.overallConsensusPercent(suggestion));

        assertEquals(80, suggestion.getConsensusPercent());
        assertTrue(AutoSkipService.isBulkEligible(suggestion, 80));
        assertFalse(AutoSkipService.isBulkEligible(suggestion, 81));
    }

    @Test
    void bulkEligibilityRejectsUnfinishedOrEmptySuggestions() {
        AutoSkipModels.SeasonSuggestion suggestion = new AutoSkipModels.SeasonSuggestion();
        suggestion.setAnalysisStatus("IN_PROGRESS");
        suggestion.setSkipOpening(30);
        suggestion.setConsensusPercent(100);

        assertFalse(AutoSkipService.isBulkEligible(suggestion, 80));

        suggestion.setAnalysisStatus("PARTIAL_SUCCESS");
        suggestion.setSkipOpening(0);
        assertFalse(AutoSkipService.isBulkEligible(suggestion, 80));
    }
}
