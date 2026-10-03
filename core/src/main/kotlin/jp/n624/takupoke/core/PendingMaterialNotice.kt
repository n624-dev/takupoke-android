package jp.n624.takupoke.core

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable data class PendingChangeTarget(val date:String,val className:String,val period:String,val afterHash:String?)
@Serializable data class PendingMaterialNotice(val kind:MaterialKind,val pdfHash:String,val targets:List<PendingChangeTarget> = emptyList()) {
    fun count(currentHash:String,analysis:Analysis,selectedClasses:Set<String>,day:LocalDate):Int {
        if(currentHash!=pdfHash || analysis.kind!=kind || analysis.parserVersion!=PARSER_VERSION)return 0
        if(kind!=MaterialKind.CHANGES)return if(analysis.classes.any { it in selectedClasses } && analysis.dates.any { LocalDate.parse(it)>=day })1 else 0
        val now=grouped(analysis.changes)
        return targets.distinct().count { target -> target.className in selectedClasses && LocalDate.parse(target.date)>=day && now[Triple(target.date,target.className,target.period)]==target.afterHash }
    }
    companion object {
        private fun grouped(rows:List<Change>):Map<Triple<String,String,String>,String> = rows.groupBy { row -> Triple(row.date,canonicalClass(row.className),row.periods().takeIf { it.isNotEmpty() }?.joinToString(",") ?: row.period) }.mapValues { (_,values) -> RecoveryValidator.fingerprint(values.map { row -> row.copy(raw="",className=canonicalClass(row.className),period=row.periods().takeIf { it.isNotEmpty() }?.joinToString(",") ?: row.period) }.distinct().sortedBy { RecoveryValidator.fingerprint(it) }) }
        fun changes(hash:String,before:List<Change>?,after:List<Change>,classes:Set<String>,day:LocalDate):PendingMaterialNotice {
            if(before==null)return PendingMaterialNotice(MaterialKind.CHANGES,hash)
            val old=grouped(before);val new=grouped(after)
            val changed=(old.keys+new.keys).filter { it.second in classes && LocalDate.parse(it.first)>=day && old[it]!=new[it] }
            return PendingMaterialNotice(MaterialKind.CHANGES,hash,changed.map { PendingChangeTarget(it.first,it.second,it.third,new[it]) })
        }
    }
}
