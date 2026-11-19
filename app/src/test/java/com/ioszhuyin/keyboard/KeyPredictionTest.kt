package com.ioszhuyin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeyPredictionTest {
    private val prediction = KeyPrediction(mapOf(
        "ㄅㄚ" to 10.0, "ㄅㄨˋ" to 60.0, "ㄅㄧˇ" to 30.0, "ㄇㄚ˙" to 50.0, "ㄕˋ" to 50.0
    ))

    @Test fun afterAnInitialOnlyItsFinalsArePredictedByFrequency() {
        val priors = prediction.priors("ㄅ")
        assertEquals(0.6, priors.getValue("ㄨ"), 1e-9)
        assertEquals(0.3, priors.getValue("ㄧ"), 1e-9)
        assertEquals(0.1, priors.getValue("ㄚ"), 1e-9)
        assertTrue("ㄩ" !in priors)
    }

    @Test fun aSyllableStartIsPredictedFromAllSyllables() {
        val priors = prediction.priors("")
        assertEquals(0.5, priors.getValue("ㄅ"), 1e-9)
        assertEquals(0.25, priors.getValue("ㄇ"), 1e-9)
        assertEquals(0.25, priors.getValue("ㄕ"), 1e-9)
    }

    @Test fun aCompleteSyllableMixesContinuingAndStartingANewOne() {
        // ㄕ is a syllable on its own and nothing extends it here, so the next
        // key starts a new syllable.
        assertEquals(prediction.priors(""), prediction.priors("ㄕ"))
        val priors = KeyPrediction(mapOf("ㄧ" to 50.0, "ㄧㄢ" to 50.0, "ㄅㄚ" to 100.0)).priors("ㄧ")
        assertEquals(0.5, priors.getValue("ㄢ"), 1e-9)
        assertEquals(0.5 * (100.0 / 200.0), priors.getValue("ㄅ"), 1e-9)
    }

    @Test fun parsesTheAssetFormatAndTheBundledTableIsSensible() {
        val parsed = KeyPrediction.parse("# header\nㄅㄚ\t12\nㄕˋ\t3\nbad line\n")
        assertEquals(mapOf("ㄅㄚ" to 12.0, "ㄕˋ" to 3.0), parsed)
        val asset = File("src/main/assets/zhuyin_syllable_frequency.tsv")
        val bundled = KeyPrediction(KeyPrediction.parse(asset.readText()))
        val afterB = bundled.priors("ㄅ")
        assertTrue(afterB.getValue("ㄨ") > afterB.getValue("ㄚ"))
        assertTrue("ㄩ" !in afterB)
        // Rare but real syllables are never treated as impossible.
        assertTrue(bundled.priors("ㄍ").getValue("ㄟ") >= PredictiveHitTesting.PRIOR_FLOOR)
    }
}

class PredictiveHitTestingTest {
    // Two 100×100 keys side by side: A at [0,100), B at [100,200).
    private val boxes = listOf(KeyboardHitBox(0f, 0f, 100f, 100f), KeyboardHitBox(100f, 0f, 200f, 100f))

    @Test fun theMiddleOfAKeyAlwaysSelectsIt() {
        assertEquals(0, PredictiveHitTesting.resolve(50f, 50f, boxes, listOf(0.0001, 0.9999)))
        assertEquals(0, PredictiveHitTesting.resolve(79f, 50f, boxes, listOf(0.0001, 0.9999)))
    }

    @Test fun plausibleKeysKeepTheirWholeDrawnArea() {
        assertEquals(0, PredictiveHitTesting.resolve(99f, 50f, boxes, listOf(0.02, 0.98)))
        assertEquals(1, PredictiveHitTesting.resolve(101f, 50f, boxes, listOf(0.98, 0.02)))
    }

    @Test fun theEdgeOfAKeyThatCannotContinueGoesToALikelyNeighbor() {
        assertEquals(1, PredictiveHitTesting.resolve(90f, 50f, boxes, listOf(0.0, 0.9)))
        // Its middle still types it, and with no likely neighbor it stays.
        assertEquals(0, PredictiveHitTesting.resolve(50f, 50f, boxes, listOf(0.0, 0.9)))
        assertEquals(0, PredictiveHitTesting.resolve(90f, 50f, boxes, listOf(0.0, 0.0)))
    }

    @Test fun aGapBetweenKeysGoesToTheLikelierKey() {
        val gapped = listOf(KeyboardHitBox(0f, 0f, 90f, 100f), KeyboardHitBox(110f, 0f, 200f, 100f))
        assertEquals(1, PredictiveHitTesting.resolve(99f, 50f, gapped, listOf(0.1, 0.9)))
        assertEquals(0, PredictiveHitTesting.resolve(101f, 50f, gapped, listOf(0.9, 0.1)))
    }

    @Test fun unweightedKeysKeepTheirDrawnArea() {
        assertEquals(0, PredictiveHitTesting.resolve(95f, 50f, boxes, listOf(null, 0.99)))
        assertEquals(-1, PredictiveHitTesting.resolve(95f, 50f, boxes.map { it.copy(enabled = false) }, listOf(0.5, 0.5)))
    }

    @Test fun theEdgeAdvantageIsBounded() {
        // At the shared edge both keys are equally close: the prior decides.
        assertEquals(1.0, PredictiveHitTesting.likelihoodRatio(0.5, 0.5), 1e-9)
        // Just outside the core, the other key needs about twice the probability.
        assertTrue(PredictiveHitTesting.likelihoodRatio(0.3, 0.7) in 2.5..8.0)
    }
}
