package jp.n624.takupoke.core

import kotlin.test.*

class RecoveryOcrQualityTest {
    @Test fun coveredLowOrUnavailableConfidenceCannotCertifyCompleteOcr() {
        for(value in listOf(0f,.79f,Float.NaN,Float.POSITIVE_INFINITY,-1f,1.01f))
            assertFalse(RecoveryOcrQuality.complete(true,listOf(.99f,value)))
    }
    @Test fun ConfidentTextStillNeedsFullPixelCoverageAndBlankPageUsesPixelProof() {
        assertTrue(RecoveryOcrQuality.complete(true,listOf(.8f,1f)))
        assertFalse(RecoveryOcrQuality.complete(false,listOf(.99f)))
        assertTrue(RecoveryOcrQuality.complete(true,emptyList()))
        assertFalse(RecoveryOcrQuality.complete(false,emptyList()))
    }
}
