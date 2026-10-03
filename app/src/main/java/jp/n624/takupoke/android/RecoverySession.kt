package jp.n624.takupoke.android

import jp.n624.takupoke.core.*
import kotlinx.serialization.Serializable

@Serializable data class RecoveryPreview(val period: String, val uri: String, val document: RecoveryDocument, val result: RecoveryResult, val preparedAt: Long, val strictParserVersion: Int = 0) {
    val resultHash get() = RecoveryValidator.fingerprint(result)
    @kotlinx.serialization.Transient private var converted:Analysis?=null
    val analysis get() = synchronized(this) { converted ?: RecoveryAnalysis.convert(document,result).also { converted=it } }
}

@Serializable data class RecoveryAccepted(val preview: RecoveryPreview, val acceptance: RecoveryAcceptance)
