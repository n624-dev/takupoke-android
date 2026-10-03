package jp.n624.takupoke.core

internal class RecoveryWorkLimit : IllegalArgumentException("Recovery comparison limit exceeded")
internal class RecoveryWork(private val limit:Long=20_000_000) {
    var visits=0L; private set
    init { interrupted() }
    fun spend(cost:Long) {
        interrupted()
        if(cost<0 || cost>limit-visits)throw RecoveryWorkLimit()
        visits+=cost
    }
    fun read(value:String):String { spend(value.length.toLong());return value }
    fun step() {
        if(++visits%128==0L)interrupted()
        if(visits>limit)throw RecoveryWorkLimit()
    }
}

/** Original source order is preserved in every bucket; IDs never imply geometry. */
internal class RecoverySources(doc:RecoveryDocument,private val work:RecoveryWork) {
    val byId=linkedMapOf<String,RecoverySource>()
    val byCell=linkedMapOf<String,MutableList<RecoverySource>>()
    val order=linkedMapOf<String,Int>()
    init { doc.sources.forEachIndexed { i,s -> work.step();byId[s.id]=s;order[s.id]=i;byCell.getOrPut(s.cellId){mutableListOf()}+=s } }
}

/** Balanced measured-box tree. Every visited node, including misses, spends the same budget. */
internal class RecoveryCellIndex(cells:List<RecoveryCell>,private val work:RecoveryWork) {
    private data class Entry(val ordinal:Int,val cell:RecoveryCell)
    private data class Bounds(val left:Double,val top:Double,val right:Double,val bottom:Double)
    private fun RecoveryBox.bounds()=Bounds(x,y,x+width,y+height)
    private data class Node(val box:Bounds,val entry:Entry?,val left:Node?,val right:Node?)
    private val pages:Map<Int,Node?>
    init {
        val grouped=linkedMapOf<Int,MutableList<Entry>>()
        cells.forEachIndexed { i,c -> work.step();if(c.box.valid)grouped.getOrPut(c.page){mutableListOf()}+=Entry(i,c) }
        pages=grouped.mapValues { build(it.value,0) }
    }
    private fun build(entries:List<Entry>,depth:Int):Node? {
        work.step();if(entries.isEmpty())return null
        if(entries.size==1)return Node(entries.single().cell.box.bounds(),entries.single(),null,null)
        val sorted=entries.sortedWith { a,b -> work.step();
            val ac=if(depth%2==0)a.cell.box.x+a.cell.box.width/2 else a.cell.box.y+a.cell.box.height/2
            val bc=if(depth%2==0)b.cell.box.x+b.cell.box.width/2 else b.cell.box.y+b.cell.box.height/2
            ac.compareTo(bc)
        }
        val middle=sorted.size/2;val left=requireNotNull(build(sorted.subList(0,middle),depth+1));val right=requireNotNull(build(sorted.subList(middle,sorted.size),depth+1))
        return Node(Bounds(minOf(left.box.left,right.box.left),minOf(left.box.top,right.box.top),maxOf(left.box.right,right.box.right),maxOf(left.box.bottom,right.box.bottom)),null,left,right)
    }
    fun overlaps(page:Int,box:RecoveryBox,found:(Int,RecoveryCell)->Unit) {
        if(!box.valid)return
        fun visit(node:Node?) {
            work.step();if(node==null)return
            val b=node.box
            if(minOf(b.right,box.x+box.width)<=maxOf(b.left,box.x) || minOf(b.bottom,box.y+box.height)<=maxOf(b.top,box.y))return
            val entry=node.entry
            if(entry!=null)found(entry.ordinal,entry.cell)else { visit(node.left);visit(node.right) }
        }
        visit(pages[page])
    }
}
