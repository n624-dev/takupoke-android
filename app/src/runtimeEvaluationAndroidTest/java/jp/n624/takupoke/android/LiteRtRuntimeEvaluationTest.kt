package jp.n624.takupoke.android

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fixed bridge keeps SDK/core/coroutine calls in the same optimized APK.
 * The ordinary offline suite never includes this class or the target harness. */
class LiteRtRuntimeEvaluationTest {
    @Test(timeout=900000) fun pinnedCandidateCpu4096SchemaValidatorCancellationAndRelease() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        check(args.getString("runtimeEvaluation")=="true")
        val context=instrumentation.targetContext
        assertTrue("Optimized target must call the offline virtual repository hook",(context.applicationContext as OfflineApplication).offlineTransportInjected)
        LiteRtRuntimeEvaluationHarness.evaluate(context,instrumentation.context,requireNotNull(args.getString("modelPath")))
    }
}
