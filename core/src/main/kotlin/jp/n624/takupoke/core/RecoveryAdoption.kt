package jp.n624.takupoke.core

import java.time.LocalDate

/** Values for the last save boundary, independent of provider/model output. */
data class RecoverySelection(val period:String,val uri:String,val kind:MaterialKind,val pdfHash:String)
object RecoveryAdoption {
    fun matchesPeriod(doc:RecoveryDocument,period:String):Boolean {
        val parts=Regex("^([0-9]{4})-([12])$").matchEntire(period)?.groupValues ?: return false
        if(doc.schoolYear!=parts[1].toInt() || doc.schoolYear !in 1900..9998)return false
        if(doc.kind==RecoveryDocumentKind.TIMETABLE)return doc.term==if(parts[2]=="1")"前期"else "後期"
        return doc.days.isNotEmpty() && doc.days.all { value -> runCatching { val date=LocalDate.parse(value);date.toString()==value && retentionPeriod(date)==period }.getOrDefault(false) }
    }
    fun allowed(prepared:RecoverySelection,current:RecoverySelection,actualFileHash:String,doc:RecoveryDocument,result:RecoveryResult,day:LocalDate=today()):Boolean {
        if(prepared!=current || current.period!=retentionPeriod(day) || actualFileHash!=current.pdfHash || doc.pdfHash!=current.pdfHash || RecoveryPolicy.kind(current.kind)!=doc.kind || !matchesPeriod(doc,current.period))return false
        return RecoveryValidator.validate(doc,result).canAdopt
    }
}
