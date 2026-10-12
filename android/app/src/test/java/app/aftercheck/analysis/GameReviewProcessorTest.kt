package app.aftercheck.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameReviewProcessorTest {
    @Test
    fun usesDocumentedClassificationThresholds() {
        assertEquals("BEST", GameReviewProcessor.classify(0))
        assertEquals("BEST", GameReviewProcessor.classify(10))
        assertEquals("EXCELLENT", GameReviewProcessor.classify(11))
        assertEquals("GOOD", GameReviewProcessor.classify(26))
        assertEquals("INACCURACY", GameReviewProcessor.classify(51))
        assertEquals("MISTAKE", GameReviewProcessor.classify(101))
        assertEquals("BLUNDER", GameReviewProcessor.classify(201))
    }

    @Test
    fun accuracyEstimateIsBoundedAndPunishesLargerLosses() {
        val lowLoss = GameReviewProcessor.estimateAccuracyForTest(listOf(5, 10))
        val highLoss = GameReviewProcessor.estimateAccuracyForTest(listOf(100, 300))
        assertTrue(lowLoss in 0.0..100.0)
        assertTrue(highLoss in 0.0..100.0)
        assertTrue(lowLoss > highLoss)
    }
}
