package jp.n624.takupoke.core

import java.time.LocalDate

/** Values for the last save boundary, independent of provider/model output. */
data class RecoverySelection(val period:String,val uri:String,val kind:MaterialKind,val pdfHash:String)
object RecoveryAdoption {
    fun allowed(prepared:RecoverySelection,current:RecoverySelection,actualFileHash:String,doc:RecoveryDocument,result:RecoveryResult,day:LocalDate=today()):Boolean {
        if(prepared!=current || current.period!=retentionPeriod(day) || actualFileHash!=current.pdfHash || doc.pdfHash!=current.pdfHash || RecoveryPolicy.kind(current.kind)!=doc.kind || doc.schoolYear!=schoolYear(day))return false
        if(doc.kind==RecoveryDocumentKind.TIMETABLE && doc.term!=(if(current.period.endsWith("-1"))"前期" else "後期"))return false
        return RecoveryValidator.validate(doc,result).canAdopt
    }
}
