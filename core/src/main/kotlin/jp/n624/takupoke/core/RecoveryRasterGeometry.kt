package jp.n624.takupoke.core

/** Pixel coverage is an independent check: a missed OCR line cannot silently become an empty lesson. */
object RecoveryRasterGeometry {
    data class Result(val complete: Boolean, val lines: List<Line>, val blankBoxes: List<RecoveryBox>)
    fun analyze(width: Int, height: Int, pixels: IntArray, recognized: List<RecoveryBox>): Result {
        require(width in 1..4096 && height in 1..4096 && pixels.size == width*height && recognized.size<=100000)
        // Both graph work and raster visits are shared across the whole page.
        // A malformed page fails safely instead of monopolizing foreground work.
        var graphWork=0;var pixelWork=0
        fun graph() { if(++graphWork%128==0)interrupted();require(graphWork<=1000000) { "画像罫線の比較上限" } }
        fun pixel() { if(++pixelWork%128==0)interrupted();require(pixelWork<=32000000) { "画像画素の解析上限" } }
        interrupted()
        // Blank evidence is conservative: colored or faint unrecognized marks
        // still prevent adopting a free period. A dark threshold is only used
        // to detect ruled strokes, never to certify blank paper.
        fun ink(x:Int,y:Int):Boolean { pixel();val c=pixels[y*width+x]; return ((c ushr 16) and 255)<255 || ((c ushr 8) and 255)<255 || (c and 255)<255 }
        fun ruleInk(x:Int,y:Int):Boolean { pixel();val c=pixels[y*width+x]; return ((c ushr 16) and 255)<180 && ((c ushr 8) and 255)<180 && (c and 255)<180 }
        val covered=BooleanArray(pixels.size); val lines=mutableListOf<Line>()
        fun mark(x:Int,y:Int) { if(x in 0 until width && y in 0 until height){ pixel();covered[y*width+x]=true } }
        for(y in 0 until height) { interrupted();var start=0;while(start<width) { if(!ruleInk(start,y)){start++;continue};var end=start+1;while(end<width&&ruleInk(end,y))end++;if(end-start>=maxOf(80,width/20)){lines+=Line(start.toDouble(),y.toDouble(),(end-1).toDouble(),y.toDouble())};start=end } }
        for(x in 0 until width) { interrupted();var start=0;while(start<height) { if(!ruleInk(x,start)){start++;continue};var end=start+1;while(end<height&&ruleInk(x,end))end++;if(end-start>=maxOf(80,height/20)){lines+=Line(x.toDouble(),start.toDouble(),x.toDouble(),(end-1).toDouble())};start=end } }
        // Collapse stroke thickness; otherwise a three-pixel line creates spurious tiny cells.
        val collapsed=mutableListOf<Line>()
        lines.sortedWith(compareBy<Line>{if(it.horizontal)0 else 1}.thenBy{if(it.horizontal)it.y1 else it.x1}).forEach { line -> if(collapsed.none { old -> graph();old.horizontal==line.horizontal && (if(line.horizontal)kotlin.math.abs(old.y1-line.y1)<=3 && kotlin.math.abs(old.x1-line.x1)<=3 && kotlin.math.abs(old.x2-line.x2)<=3 else kotlin.math.abs(old.x1-line.x1)<=3 && kotlin.math.abs(old.y1-line.y1)<=3 && kotlin.math.abs(old.y2-line.y2)<=3) })collapsed+=line }
        // A character stroke (for example 一 or I) is not a table border.
        // Accept only strokes whose two endpoints join perpendicular ruled strokes.
        var ruled=collapsed.toList()
        while(true) {
            interrupted()
            val candidates=ruled
            val retained=candidates.filter { line ->
                if(line.horizontal) listOf(line.x1,line.x2).all { x -> candidates.any { graph();it.vertical && kotlin.math.abs(it.x1-x)<=4 && line.y1>=it.y1-4 && line.y1<=it.y2+4 } }
                else listOf(line.y1,line.y2).all { y -> candidates.any { graph();it.horizontal && kotlin.math.abs(it.y1-y)<=4 && line.x1>=it.x1-4 && line.x1<=it.x2+4 } }
            }
            if(retained.size==ruled.size)break
            ruled=retained
        }
        // Mask the measured stroke and only contiguous full-length fringe
        // scanlines. A nearby isolated faint glyph is not part of the border.
        ruled.forEach { line ->
            interrupted()
            if(line.horizontal) {
                val xs=line.x1.toInt()..line.x2.toInt();val base=line.y1.toInt()
                xs.forEach { mark(it,base) }
                for(direction in listOf(-1,1))for(step in 1..3) {
                    val y=base+direction*step
                    if(y !in 0 until height || !xs.all { ink(it,y) })break
                    xs.forEach { mark(it,y) }
                }
            } else {
                val ys=line.y1.toInt()..line.y2.toInt();val base=line.x1.toInt()
                ys.forEach { mark(base,it) }
                for(direction in listOf(-1,1))for(step in 1..3) {
                    val x=base+direction*step
                    if(x !in 0 until width || !ys.all { ink(x,it) })break
                    ys.forEach { mark(x,it) }
                }
            }
        }
        val textCovered=covered.copyOf()
        recognized.forEach { box -> require(box.valid);for(y in (box.y.toInt()-2).coerceAtLeast(0)..(box.y+box.height+2).toInt().coerceAtMost(height-1))for(x in (box.x.toInt()-2).coerceAtLeast(0)..(box.x+box.width+2).toInt().coerceAtMost(width-1)){ pixel();textCovered[y*width+x]=true } }
        val complete=pixels.indices.none { i -> pixel();!textCovered[i] && ink(i%width,i/width) }
        val blank=mutableListOf<RecoveryBox>()
        fun measuredBox(x:Double,y:Double):Box? {
            var left:Double?=null;var right:Double?=null;var top:Double?=null;var bottom:Double?=null
            ruled.forEach { line ->
                graph()
                if(line.vertical && line.y1-.8<=y && y<=line.y2+.8) {
                    if(line.x1<x-.5)left=maxOf(left?:line.x1,line.x1)
                    if(line.x1>x+.5)right=minOf(right?:line.x1,line.x1)
                }
                if(line.horizontal && line.x1-.8<=x && x<=line.x2+.8) {
                    if(line.y1<y-.5)top=maxOf(top?:line.y1,line.y1)
                    if(line.y1>y+.5)bottom=minOf(bottom?:line.y1,line.y1)
                }
            }
            return if(left!=null && right!=null && top!=null && bottom!=null)Box(left,top,right,bottom)else null
        }
        val xs=ruled.filter{it.vertical}.map{it.x1}.distinct().sorted();val ys=ruled.filter{it.horizontal}.map{it.y1}.distinct().sorted()
        if(xs.size.toLong()*ys.size<=100000)xs.zipWithNext().forEach { (a,b)->ys.zipWithNext().forEach { (c,d)-> if(b-a>6 && d-c>6)measuredBox((a+b)/2,(c+d)/2)?.let { box -> var clear=true;for(y in (box.top.toInt()+1) until box.bottom.toInt())for(x in (box.left.toInt()+1) until box.right.toInt())if(ink(x,y)&&!covered[y*width+x])clear=false;if(clear)blank+=RecoveryBox(box.left,box.top,box.right-box.left,box.bottom-box.top) } } }
        return Result(complete,ruled,blank.distinct())
    }
}
