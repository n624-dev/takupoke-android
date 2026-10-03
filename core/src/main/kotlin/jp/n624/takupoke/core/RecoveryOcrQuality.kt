package jp.n624.takupoke.core

/** Confidence is a separate prerequisite; pixel coverage alone cannot detect 1/I or 0/O substitutions. */
object RecoveryOcrQuality {
    fun complete(pixelComplete: Boolean, confidences: List<Float>): Boolean =
        pixelComplete && confidences.all { it.isFinite() && it in .8f..1f }
}
