package jp.n624.takupoke.core

import kotlin.test.*

class ObservedRefreshQueueTest {
    @Test fun busyOperationChangeSurvivesReregistration() {
        val queue = ObservedRefreshQueue(); val first = queue.replace(0)!!
        assertTrue(queue.request(first))
        val next = queue.replace(first)!!
        assertFalse(queue.take(first)); assertTrue(queue.take(next))
    }
    @Test fun cancelledObserverCannotRestoreOrConsumePendingWork() {
        val queue = ObservedRefreshQueue(); val old = queue.replace(0)!!
        queue.request(old); val stopped = queue.stop()
        assertFalse(queue.request(old)); assertFalse(queue.take(old))
        val current = queue.replace(stopped)!!; queue.request(current)
        assertFalse(queue.take(old)); assertTrue(queue.take(current))
    }
    @Test fun staleRegistrationRequestCannotReplaceNewObservers() {
        val queue = ObservedRefreshQueue(); val old = queue.generation()
        queue.stop(); assertNull(queue.replace(old))
    }
    @Test fun readNotificationsEndAfterTwoUnchangedPasses() {
        val queue = ObservedRefreshQueue(); var token = queue.replace(0)!!
        queue.request(token)
        repeat(2) {
            assertTrue(queue.take(token)); queue.request(token)
            queue.complete(token, changed = false)
            token = queue.replace(token)!!
        }
        assertFalse(queue.hasPending(token)); assertFalse(queue.take(token))
        // A later independent change starts a fresh confirmation chain.
        assertTrue(queue.request(token)); assertTrue(queue.take(token))
    }
    @Test fun changedContentAllowsThePendingNewerVersionToBeChecked() {
        val queue = ObservedRefreshQueue(); val token = queue.replace(0)!!
        queue.request(token); queue.take(token); queue.request(token)
        queue.complete(token, changed = true)
        assertTrue(queue.take(token))
    }
    @Test fun oldCompletionCannotClearNewGenerationRequest() {
        val queue = ObservedRefreshQueue(); val old = queue.replace(0)!!
        queue.request(old); queue.take(old)
        val current = queue.replace(queue.stop())!!; queue.request(current)
        queue.complete(old, changed = false)
        assertTrue(queue.take(current))
    }
    @Test fun reregistrationDuringReadDoesNotLeaveTheQueueBusyForever() {
        val queue = ObservedRefreshQueue(); val old = queue.replace(0)!!
        queue.request(old); assertTrue(queue.take(old))
        val current = queue.replace(old)!!; queue.request(current)
        assertFalse(queue.take(current))
        queue.complete(old, changed = false)
        assertTrue(queue.take(current))
    }
}
