package jp.n624.takupoke.core

/** Keeps changes received during an operation without reviving cancelled observers. */
class ObservedRefreshQueue {
    private var generation = 0L
    private var pending = false
    private var checkingToken: Long? = null
    private var unchangedPasses = 0
    @Synchronized fun generation(): Long = generation
    @Synchronized fun replace(expected: Long): Long? {
        if (expected != generation) return null
        return ++generation
    }
    @Synchronized fun stop(): Long {
        pending = false; checkingToken = null; unchangedPasses = 0
        return ++generation
    }
    @Synchronized fun request(token: Long): Boolean {
        if (token != generation) return false
        if (checkingToken == null) unchangedPasses = 0
        pending = true
        return true
    }
    @Synchronized fun hasPending(token: Long) = token == generation && pending
    @Synchronized fun take(token: Long): Boolean {
        if (token != generation || !pending || checkingToken != null) return false
        pending = false; checkingToken = token
        return true
    }
    @Synchronized fun complete(token: Long, changed: Boolean) {
        if (token != checkingToken) return
        checkingToken = null
        // Re-registration can happen while background activation is running.
        // Release this read's ownership without discarding the new generation.
        if (token != generation) { unchangedPasses = 0; return }
        unchangedPasses = if (changed) 0 else unchangedPasses + 1
        // Some document providers notify while being read. Permit one stable
        // follow-up to catch a change during the read, then end that chain.
        if (unchangedPasses >= 2) pending = false
    }
}
