package jp.n624.takupoke.core

object LinkSearch {
    private val syllables = ("あ:a い:i う:u え:e お:o か:ka き:ki く:ku け:ke こ:ko さ:sa し:shi す:su せ:se そ:so た:ta ち:chi つ:tsu て:te と:to な:na に:ni ぬ:nu ね:ne の:no は:ha ひ:hi ふ:fu へ:he ほ:ho ま:ma み:mi む:mu め:me も:mo や:ya ゆ:yu よ:yo ら:ra り:ri る:ru れ:re ろ:ro わ:wa を:o ん:n が:ga ぎ:gi ぐ:gu げ:ge ご:go ざ:za じ:ji ず:zu ぜ:ze ぞ:zo だ:da ぢ:ji づ:zu で:de ど:do ば:ba び:bi ぶ:bu べ:be ぼ:bo ぱ:pa ぴ:pi ぷ:pu ぺ:pe ぽ:po ぁ:a ぃ:i ぅ:u ぇ:e ぉ:o ゔ:vu " +
        "きゃ:kya きゅ:kyu きょ:kyo しゃ:sha しゅ:shu しょ:sho ちゃ:cha ちゅ:chu ちょ:cho にゃ:nya にゅ:nyu にょ:nyo ひゃ:hya ひゅ:hyu ひょ:hyo みゃ:mya みゅ:myu みょ:myo りゃ:rya りゅ:ryu りょ:ryo ぎゃ:gya ぎゅ:gyu ぎょ:gyo じゃ:ja じゅ:ju じょ:jo びゃ:bya びゅ:byu びょ:byo ぴゃ:pya ぴゅ:pyu ぴょ:pyo うぇ:we うぃ:wi うぉ:wo てぃ:ti でぃ:di ふぁ:fa ふぃ:fi ふぇ:fe ふぉ:fo").split(' ').associate { it.substringBefore(':') to it.substringAfter(':') }
    private fun hiragana(value: String) = normalized(value).lowercase(java.util.Locale.ROOT).map { if (it.code in 0x30A1..0x30F6) (it.code - 0x60).toChar() else it }.joinToString("")
    private fun canonical(value: String) = value.replace("shi", "si").replace("chi", "ti").replace("tsu", "tu").replace("fu", "hu").replace("ji", "zi")
    fun normalize(value: String) = canonical(hiragana(value).filter { it.isLetterOrDigit() })
    fun romaji(value: String): String {
        val source = hiragana(value); val result = StringBuilder(); var index = 0; var doubled = false
        while (index < source.length) {
            val c = source[index]
            if (c == 'っ') { doubled = true; index++; continue }
            if (c == 'ー') { result.lastOrNull()?.takeIf { it in "aeiou" }?.let(result::append); index++; continue }
            val pair = source.substring(index, minOf(index + 2, source.length)); val combined = syllables[pair]
            val syllable = combined ?: syllables[c.toString()] ?: c.toString(); index += if (combined != null && pair.length == 2) 2 else 1
            if (doubled && syllable.first() in 'a'..'z') result.append(syllable.first())
            doubled = false; result.append(syllable)
        }
        return canonical(result.toString())
    }
    fun score(terms: String, query: String): Int {
        val q = normalize(query); if (q.isEmpty()) return -1
        val expanded = terms.split('|').filter(String::isNotEmpty).flatMap { t -> val text = normalize(t)
            if (hiragana(t).any { it.code in 0x3041..0x3096 }) { val r = normalize(romaji(t)); listOf(text, r, r.replace("ou", "o").replace("uu", "o").replace("oo", "o")) } else listOf(text)
        }
        return expanded.mapIndexed { i, term -> when { term == q -> 1000 - i; term.startsWith(q) -> 800 - i; term.contains(q) -> 500 - i; else -> -1 } }.maxOrNull() ?: -1
    }
}
