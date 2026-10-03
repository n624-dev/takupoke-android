package jp.n624.takupoke.android

import androidx.test.platform.app.InstrumentationRegistry
import jp.n624.takupoke.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual repository cancellation/store boundaries; all source geometry is invented. */
class RecoveryWorkNativeTest {
    @Test fun cancelInsideCpuValidationNeverPublishesPreviewOrReplacesFormalResult():Unit=runBlocking {
        val service=OfflineRecoveryServices()
        val seed=OfflineRecoverySeed(InstrumentationRegistry.getInstrumentation().targetContext,service)
        try {
            seed.install()
            val source=RecoveryLayout.prepare(listOf(RecoveryLayoutPage(1,service.layout())),"a".repeat(64),MaterialKind.TIMETABLE)
            var visits=0L;var cancelledAt=0L
            lateinit var operation:Job
            service.preparedDocument=source.copy(sources=object:AbstractList<RecoverySource>() {
                override val size get()=source.sources.size
                override fun get(index:Int):RecoverySource {
                    visits++
                    if(visits==source.sources.size.toLong()+1) { cancelledAt=visits;operation.cancel() }
                    return source.sources[index]
                }
            })
            val before=seed.database.records().single()
            val pending=async(Dispatchers.IO,start=CoroutineStart.LAZY) { seed.repository.startRecovery(MaterialKind.TIMETABLE) }
            operation=pending;pending.start()
            try { withTimeout(5000) { pending.await() };fail("CPU validation returned a preview after cancellation") }
            catch(e:CancellationException) { if(e is TimeoutCancellationException)throw e }
            assertTrue(pending.isCancelled && !pending.isActive)
            assertEquals(source.sources.size.toLong()+1,cancelledAt)
            val after=seed.database.records().single()
            assertEquals(before.analysis,after.analysis);assertEquals(before.parsedDigest,after.parsedDigest)
            assertEquals(RecoveryJobState.PENDING,after.recoveryJob?.state)
            assertTrue(seed.repository.state.value.recoveryPreviews.isEmpty())
            assertNull(seed.database.value("recovery-preview:TIMETABLE"))
            assertNull(seed.database.value("recovery-accepted:TIMETABLE:${after.digest}"))
            assertEquals(0,service.providerCalls);assertEquals(1,service.providerClosures)
        }finally { seed.stop() }
    }
}
