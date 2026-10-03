package jp.n624.takupoke.core

import kotlin.test.*
import java.time.LocalDate
class PendingMaterialNoticeTest {
    private val day=LocalDate.of(2026,10,1)
    private val old=listOf(Change("2026-10-02","3_CN","1","A","B",""))
    private val new=listOf(old.first().copy(after="C"))
    @Test fun queuedNoticeIsRecountedForCurrentClassAndDate() {
        val pending=PendingMaterialNotice.changes("hash",old,new,setOf("3_CN"),day);val analysis=Analysis(MaterialKind.CHANGES,2026,changes=new)
        assertEquals(1,pending.count("hash",analysis,setOf("3_CN"),day))
        assertEquals(0,pending.count("hash",analysis,setOf("3_ES"),day))
        assertEquals(0,pending.count("hash",analysis,setOf("3_CN"),day.plusDays(2)))
        assertEquals(0,pending.count("updated",analysis,setOf("3_CN"),day))
        assertEquals(0,pending.count("hash",analysis.copy(changes=old),setOf("3_CN"),day))
    }
    @Test fun removalNoticeRemainsGroundedInAbsentCurrentRow() {
        val pending=PendingMaterialNotice.changes("hash",old,emptyList(),setOf("3_CN"),day)
        assertEquals(1,pending.count("hash",Analysis(MaterialKind.CHANGES,2026),setOf("3_CN"),day))
        assertEquals(0,pending.count("hash",Analysis(MaterialKind.CHANGES,2026,changes=old),setOf("3_CN"),day))
    }
}
