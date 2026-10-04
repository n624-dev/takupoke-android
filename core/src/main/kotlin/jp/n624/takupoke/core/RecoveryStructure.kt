package jp.n624.takupoke.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.abs

@Serializable enum class RecoveryPromptMode { fieldExtraction, structureProposal }
@Serializable data class RecoveryStructureCut(val id:String,val axis:String,val position:Double)
data class RecoveryStructureUnit(val id:String,val sources:List<RecoverySource>,val box:RecoveryBox) { val text get()=sources.joinToString("") { it.text } }
data class RecoveryStructureRequest(val id:String,val page:Int,val box:RecoveryBox,val slots:List<RecoverySlot>,val units:List<RecoveryStructureUnit>,val cuts:List<RecoveryStructureCut>) {
    val prompt get()=RecoveryPromptCell(id,slots,units.map { RecoveryPromptSource(it.id,it.text,it.box,it.sources.first().sourceLine,it.sources.first().sourceOrder) },emptyList(),1,emptyList(),mode=RecoveryPromptMode.structureProposal,structureCuts=cuts)
}
class RecoveryStructurePreparation(val document:RecoveryDocument,val requests:List<RecoveryStructureRequest>,val pages:List<RecoveryLayoutPage>):Exception("原文に基づく表構造の確認が必要です。")
data class RecoveryStructureRole(val role:String,val labels:List<RecoveryStructureUnit>,val body:List<RecoveryStructureUnit>,val scope:RecoveryBox,val labelBox:RecoveryBox)
data class RecoveryStructureResolution(val state:RecoveryJobState,val proposals:Map<String,List<RecoveryLesson>>?=null,val metadata:RecoveryMetadata?=null,val errors:List<String> = emptyList())

/** AI selects only existing measured IDs; this certificate independently proves the semantic partition. */
object RecoveryStructure {
    private fun union(boxes:List<RecoveryBox>):RecoveryBox {
        require(boxes.isNotEmpty())
        val x=boxes.minOf { it.x };val y=boxes.minOf { it.y }
        return RecoveryBox(x,y,boxes.maxOf { it.x+it.width }-x,boxes.maxOf { it.y+it.height }-y).also { require(it.valid) }
    }
    fun request(id:String,page:Int,box:RecoveryBox,slots:List<RecoverySlot>,sources:List<RecoverySource>):RecoveryStructureRequest {
        require(box.valid && sources.isNotEmpty() && sources.size<=512 && sources.map { it.id }.distinct().size==sources.size)
        require(sources.all { it.page==page && box.contains(it.box) })
        val units=mutableListOf<RecoveryStructureUnit>()
        val glyphs=sources.mapIndexed { i,s -> Glyph(s.text,s.box.x,s.box.y,s.box.width,s.box.height,i,s.sourceLine) }
        Grid.rows(glyphs).forEach { row ->
            val groups=mutableListOf<MutableList<Glyph>>()
            row.forEach { glyph ->
                val last=groups.lastOrNull()?.lastOrNull()
                if(last==null || glyph.x-last.x-last.width>maxOf(2.0,minOf(last.height,glyph.height)*.55))groups+=mutableListOf(glyph) else groups.last()+=glyph
            }
            groups.forEach { group -> val originals=group.map { sources[it.order] };units+=RecoveryStructureUnit("g${units.size}",originals,union(originals.map { it.box })) }
        }
        require(units.size<=64)
        val leftLabels=units.filter { it.box.x-box.x<=it.box.height }
        val aliases=RecoveryRoles.byLabel.keys.map { key("$it:") }
        require(leftLabels.size in 3..9 && leftLabels.count { key(it.text).endsWith(":") }==3 && leftLabels.all { unit->aliases.any { it.contains(key(unit.text)) } })
        fun gaps(intervals:List<Pair<Double,Double>>,first:Double,last:Double):List<Double> {
            val merged=mutableListOf<Pair<Double,Double>>()
            intervals.sortedBy { it.first }.forEach { range -> val previous=merged.lastOrNull();if(previous!=null && range.first<=previous.second)merged[merged.lastIndex]=previous.first to maxOf(previous.second,range.second) else merged+=range }
            return listOf(first)+merged.zipWithNext().mapNotNull { (a,b)->if(b.first-a.second>=.5)(a.second+b.first)/2 else null }+last
        }
        val ys=gaps(units.map { it.box.y to it.box.y+it.box.height },box.y,box.y+box.height)
        val xs=gaps(units.map { it.box.x to it.box.x+it.box.width },box.x,box.x+box.width)
        val cuts=ys.mapIndexed { i,p->RecoveryStructureCut("y$i","horizontal",p) }+xs.mapIndexed { i,p->RecoveryStructureCut("x$i","vertical",p) }
        return RecoveryStructureRequest(id,page,box,slots,units,cuts)
    }
    fun verify(request:RecoveryStructureRequest,lessons:List<RecoveryLesson>):List<RecoveryStructureRole> {
        fun valid(condition:Boolean) { if(!condition)throw InvalidRecoveryOutput() }
        valid(lessons.size==1 && request.units.map { it.id }.distinct().size==request.units.size && request.cuts.map { it.id }.distinct().size==request.cuts.size)
        val units=request.units.associateBy { it.id };val cuts=request.cuts.associateBy { it.id }
        valid(units.keys.intersect(cuts.keys).isEmpty())
        val fields=listOf(lessons.single().subject,lessons.single().teacher,lessons.single().room)
        val result=mutableListOf<RecoveryStructureRole>();val labelsUsed=mutableSetOf<String>()
        listOf("subject","teacher","room").zip(fields).forEach { (role,field)->
            valid(field.state==RecoveryValueState.PRESENT && field.value.isEmpty() && field.evidence.distinct().size==field.evidence.size)
            val labelIds=field.evidence.filter { it in units };val cutIds=field.evidence.filter { it in cuts }
            valid(labelIds.size in 1..3 && cutIds.size==3 && field.evidence==labelIds+cutIds)
            val top=cuts.getValue(cutIds[0]);val bottom=cuts.getValue(cutIds[1]);val left=cuts.getValue(cutIds[2])
            valid(top.axis=="horizontal" && bottom.axis=="horizontal" && left.axis=="vertical" && top.position<bottom.position && left.position>request.box.x && left.position<request.box.x+request.box.width)
            val labels=labelIds.map { units.getValue(it) }
            valid(labels.map { it.id }==labels.sortedWith(compareBy<RecoveryStructureUnit> { it.box.y }.thenBy { it.box.x }).map { it.id })
            valid(key(labels.joinToString("") { it.text }) in RecoveryRoles.labels.getValue(role).map { key("$it:") } && labels.all { labelsUsed.add(it.id) })
            val labelBox=union(labels.map { it.box });val scope=RecoveryBox(left.position,top.position,request.box.x+request.box.width-left.position,bottom.position-top.position)
            val height=labels.maxOf { it.box.height }
            valid(request.box.contains(scope) && labels.all { it.box.x-request.box.x<=height && abs(it.box.x-labels.first().box.x)<=height*.5 } && labelBox.x+labelBox.width<=scope.x && labelBox.y>=scope.y && labelBox.y+labelBox.height<=scope.y+scope.height)
            result+=RecoveryStructureRole(role,labels,emptyList(),scope,labelBox)
        }
        valid(result.indices.all { i->result.take(i).none { minOf(it.scope.y+it.scope.height,result[i].scope.y+result[i].scope.height)>maxOf(it.scope.y,result[i].scope.y) } })
        val body=request.units.filter { it.id !in labelsUsed }
        valid(body.isNotEmpty() && request.units.filter { it.box.x-request.box.x<=it.box.height }.all { it.id in labelsUsed })
        body.forEach { unit ->
            val matching=result.indices.filter { index-> val r=result[index];r.scope.contains(unit.box) && unit.box.y>=r.labelBox.y && unit.box.y+unit.box.height<=r.labelBox.y+r.labelBox.height }
            valid(matching.size==1);val index=matching.single();result[index]=result[index].copy(body=result[index].body+unit)
        }
        valid(result.single { it.role=="subject" }.body.isNotEmpty())
        return result.sortedBy { it.scope.y }
    }
    /** Enumerate only the bounded measured label rail; every answer still needs the full certificate. */
    fun cheap(request:RecoveryStructureRequest):List<RecoveryLesson>? = cheap(request,{})
    internal fun cheap(request:RecoveryStructureRequest,charge:(Int)->Unit):List<RecoveryLesson>? {
        val work=RecoveryWork(100_000)
        fun step() { work.step();charge(1) }
        fun read(text:String):String { charge(text.length);return work.read(text) }
        if(request.units.size !in 1..64 || request.cuts.size>130)return null
        val required=request.units.filter { step();it.box.x-request.box.x<=it.box.height }
        if(required.size !in 3..9)return null
        val roles=listOf("subject","teacher","room")
        val aliases=roles.associateWith { role->RecoveryRoles.labels.getValue(role).map { key("$it:") } }
        val allAliases=aliases.values.flatten()
        val texts=request.units.associate { unit->step();unit.id to key(read(unit.text)) }
        val fragments=request.units.filter { unit->step();val text=texts.getValue(unit.id);text.isNotEmpty() && allAliases.any { step();it.contains(text) } }
        val maxHeight=fragments.maxOfOrNull { step();it.box.height } ?: return null
        // Optional short fragments may align with a taller label; body-like fragments remain certificate inputs.
        val pool=fragments.filter { step();it.box.x-request.box.x<=maxHeight }
            .sortedWith { a,b -> step();compareValuesBy(a,b,{it.box.y},{it.box.x}) }
        val candidates=roles.associateWith { mutableListOf<RecoveryField>() }
        fun consider(role:String,labels:List<RecoveryStructureUnit>) {
            step()
            val box=union(labels.map { step();it.box })
            // Canonical nearest cuts avoid treating equivalent empty margins as different partitions.
            val top=request.cuts.filter { step();it.axis=="horizontal" && it.position<=box.y }.maxByOrNull { step();it.position } ?: return
            val bottom=request.cuts.filter { step();it.axis=="horizontal" && it.position>=box.y+box.height }.minByOrNull { step();it.position } ?: return
            val left=request.cuts.filter { step();it.axis=="vertical" && it.position>=box.x+box.width }.minByOrNull { step();it.position } ?: return
            candidates.getValue(role)+=RecoveryField(RecoveryValueState.PRESENT,"",labels.map { it.id }+listOf(top.id,bottom.id,left.id))
        }
        fun chains(role:String,start:Int,labels:List<RecoveryStructureUnit>,text:String) {
            if(labels.isNotEmpty() && text in aliases.getValue(role))consider(role,labels)
            if(labels.size==3)return
            for(i in start until pool.size) {
                step();val next=text+texts.getValue(pool[i].id)
                if(aliases.getValue(role).any { step();it.startsWith(next) })chains(role,i+1,labels+pool[i],next)
            }
        }
        roles.forEach { chains(it,0,emptyList(),"") }
        var accepted:List<RecoveryLesson>?=null
        var partition:List<Triple<String,List<String>,List<String>>>?=null
        for(subject in candidates.getValue("subject"))for(teacher in candidates.getValue("teacher"))for(room in candidates.getValue("room")) {
            step()
            val proposal=listOf(RecoveryLesson(subject,teacher,room,emptyList(),emptyList()))
            val verified=try { verify(request,proposal) }catch(_:InvalidRecoveryOutput) { continue }
            val semantic=verified.sortedBy { it.role }.map { step();Triple(it.role,it.labels.map { u->u.id },it.body.map { u->u.id }) }
            if(partition!=null && partition!=semantic)throw RecoveryPreparationFailure("表構造の役割候補が一意ではありません")
            accepted=proposal;partition=semantic
        }
        return accepted
    }
    suspend fun resolve(requests:List<RecoveryStructureRequest>,providers:List<LocalRecoveryProvider>,os:String,osMajor:Int,check:()->Unit):RecoveryStructureResolution {
        require(requests.size in 1..32 && requests.map { it.id }.distinct().size==requests.size && requests.all { json.encodeToString(it.prompt).length<=8192 })
        suspend fun alive() { currentCoroutineContext().ensureActive();check() }
        var runtimeFailed=false
        for(id in RecoveryPolicy.providers(os,osMajor)) {
            alive();val matching=providers.filter { it.id==id && it.localOnly }
            if(matching.size>1)return RecoveryStructureResolution(RecoveryJobState.FAILED,errors=listOf("duplicateProviders"))
            val provider=matching.singleOrNull() ?: continue
            val availability=try { provider.availability().also { alive() } }catch(e:kotlinx.coroutines.CancellationException) { throw e }catch(_:Exception) { runtimeFailed=true;continue }
            if(availability!=LocalProviderState.READY) {
                if(RecoveryPolicy.mayTryNext(availability) || os=="windows" && availability==LocalProviderState.NOT_READY)continue
                return RecoveryStructureResolution(RecoveryJobState.AWAITING_MODEL,errors=listOf(availability.name))
            }
            try {
                val proposals=linkedMapOf<String,List<RecoveryLesson>>()
                requests.forEach { request -> alive();val answer=provider.recoverCell(request.prompt);alive();verify(request,answer);proposals[request.id]=answer }
                alive();return RecoveryStructureResolution(RecoveryJobState.AWAITING_CONFIRMATION,proposals,provider.metadata)
            }catch(e:kotlinx.coroutines.CancellationException) { throw e }catch(_:InvalidRecoveryOutput) { return RecoveryStructureResolution(RecoveryJobState.FAILED,errors=listOf("invalidOutput")) }catch(_:Exception) { runtimeFailed=true }
        }
        alive();return RecoveryStructureResolution(if(runtimeFailed)RecoveryJobState.FAILED else RecoveryJobState.AWAITING_MODEL,errors=listOf(if(runtimeFailed)"runtimeFailure" else "noLocalProvider"))
    }
}
