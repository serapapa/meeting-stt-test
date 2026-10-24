package kr.moduit.meetingnote

/**
 * 내 용어사전으로 받아쓴 글을 고친다.
 *  1) 자주 틀리는 말 짝(틀린 말 → 바른 말)을 띄어쓰기 무시하고 바꾼다
 *  2) 사전 용어와 소리가 비슷한 말(초성·중성·종성 단위로 비교)을 용어로 바꾼다
 *  고치지 않을 말 목록에 있는 말은 건드리지 않는다.
 */
class TermFixer(termsText: String, pairsText: String, keepText: String) {

    data class Seg(val text: String, val orig: String? = null)

    private class Term(val term: String, val sounds: List<String>)
    private class Pair2(val wrong: String, val right: String)

    private val terms: List<Term>
    private val pairs: List<Pair2>
    private val keep: Set<String>

    init {
        terms = termsText.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val parts = line.split("=", limit = 2)
            val term = parts[0].trim()
            val extra = if (parts.size > 1) parts[1].split(",").map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
            Term(term, (listOf(term) + extra).map { norm(it) }.distinct().filter { it.length >= 2 })
        }.filter { it.term.isNotEmpty() }
        pairs = pairsText.lines().mapNotNull { line ->
            val p = line.split("→", "->")
            if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) Pair2(p[0].trim(), p[1].trim()) else null
        }
        keep = keepText.split(",", "\n").map { norm(it) }.filter { it.isNotEmpty() }.toSet()
    }

    val termList: List<String> get() = terms.map { it.term }

    fun fix(text: String): List<Seg> {
        var segs = listOf(Seg(text))
        // 1) 틀린 말 짝
        for (p in pairs) {
            val pattern = norm(p.wrong).map { Regex.escape(it.toString()) }.joinToString("\\s*")
            if (pattern.isEmpty()) continue
            val re = Regex(pattern)
            val next = mutableListOf<Seg>()
            for (sg in segs) {
                if (sg.orig != null) { next += sg; continue }
                var last = 0
                for (m in re.findAll(sg.text)) {
                    if (m.range.first > last) next += Seg(sg.text.substring(last, m.range.first))
                    next += Seg(p.right, m.value)
                    last = m.range.last + 1
                }
                if (last < sg.text.length) next += Seg(sg.text.substring(last))
            }
            segs = next
        }
        // 2) 소리가 비슷한 말
        val out = mutableListOf<Seg>()
        for (sg in segs) {
            if (sg.orig != null) { out += sg; continue }
            val tokens = Regex("\\s+|[^\\s]+").findAll(sg.text).map { it.value }.toList()
            var i = 0
            while (i < tokens.size) {
                val tk = tokens[i]
                if (tk.isBlank()) { out += Seg(tk); i++; continue }
                var best: Best? = null
                for (n in 1..3) {
                    val idx = mutableListOf<Int>()
                    var k = i
                    while (k < tokens.size && idx.size < n) { if (tokens[k].isNotBlank()) idx += k; k++ }
                    if (idx.size < n) break
                    val joined = idx.joinToString("") { tokens[it] }
                    val beforeLast = idx.dropLast(1).sumOf { tokens[it].length }
                    for (t in terms) for (snd in t.sounds) {
                        for (len in snd.length - 1..snd.length + 1) {
                            if (len < 2 || len > joined.length) continue
                            if (n > 1 && len <= beforeLast) continue
                            val head = joined.substring(0, len)
                            val rest = joined.substring(len)
                            if (rest.length > 3) continue
                            if (keep.contains(head) || keep.contains(tokens[idx[0]])) continue
                            val sim = if (head == snd) 1.0 else similarity(head, snd)
                            val thr = if (n > 1) 0.9 else if (snd.length <= 2) 0.86 else 0.8
                            if (sim >= thr && (best == null || sim > best.sim)) {
                                best = Best(sim, t.term, head, rest, idx.last(), head == snd && head == norm(t.term))
                            }
                        }
                    }
                }
                val b = best
                if (b != null) {
                    if (b.exact) out += Seg(b.term + b.rest)
                    else { out += Seg(b.term, b.head); if (b.rest.isNotEmpty()) out += Seg(b.rest) }
                    i = b.end + 1
                } else { out += Seg(tk); i++ }
            }
        }
        return out
    }

    fun plainFix(text: String): String = fix(text).joinToString("") { it.text }

    private class Best(val sim: Double, val term: String, val head: String, val rest: String, val end: Int, val exact: Boolean)

    companion object {
        val DEFAULT_TERMS = listOf(
            "모두잇", "사티스", "코어룸", "모두처치", "EAMS = 이에이엠에스, 이엠스, 이에이엠스, 이에스", "기업은행",
            "기획PL = 기획피엘", "제안서", "요구사항", "화면설계서", "단위테스트", "주간보고", "클로드", "깃허브 = 깃허브, 기트허브",
            "노션", "구글 드라이브", "슈퍼베이스 = 슈퍼베이스, 수파베이스", "아카이브", "심지훈", "전진영", "차장님", "이사님",
        ).joinToString("\n")
        val DEFAULT_PAIRS = listOf("키 커브 → 깃허브", "기타부 → 깃허브", "조정수 → 저장소", "EMS → EAMS", "처장님 → 차장님").joinToString("\n")
        const val DEFAULT_KEEP = "클라우드, 확인, 사실, 일정, 설계"

        fun norm(s: String) = s.replace(Regex("\\s"), "")

        private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
        private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
        private val JONG = arrayOf("", "K", "K", "K", "N", "N", "N", "T", "L", "L", "L", "L", "L", "L", "L", "L", "M", "P", "P", "T", "T", "NG", "T", "T", "K", "T", "P", "T")
        private val CGROUP = mapOf('ㄱ' to 1, 'ㄲ' to 1, 'ㅋ' to 1, 'ㄷ' to 2, 'ㄸ' to 2, 'ㅌ' to 2, 'ㅂ' to 3, 'ㅃ' to 3, 'ㅍ' to 3,
            'ㅈ' to 4, 'ㅉ' to 4, 'ㅊ' to 4, 'ㅅ' to 5, 'ㅆ' to 5, 'ㄴ' to 6, 'ㄹ' to 6, 'ㅁ' to 6)
        private val VNEAR = listOf("ㅐㅔ", "ㅒㅖ", "ㅙㅞㅚ", "ㅏㅓ", "ㅓㅗ", "ㅗㅜ", "ㅜㅡ", "ㅡㅓ", "ㅕㅛ", "ㅣㅢ")
        private val JSOUND = mapOf("K" to 'ㄱ', "T" to 'ㄷ', "P" to 'ㅂ', "N" to 'ㄴ', "L" to 'ㄹ', "M" to 'ㅁ')

        /** 소리 단위: C초성, V중성, J종성, O기타 */
        private fun sounds(str: String): List<String> {
            val out = mutableListOf<String>()
            for (ch in str.lowercase()) {
                val c = ch.code - 0xAC00
                if (c in 0 until 11172) {
                    val cho = CHO[c / 588]; val jung = JUNG[(c % 588) / 28]; val jong = JONG[c % 28]
                    if (cho != 'ㅇ') out += "C$cho"
                    out += "V$jung"
                    if (jong.isNotEmpty()) out += "J$jong"
                } else if (!ch.isWhitespace()) out += "O$ch"
            }
            return out
        }

        private fun consonant(t: String): Char? = when (t[0]) {
            'C' -> t[1]
            'J' -> JSOUND[t.substring(1)]
            else -> null
        }

        private fun subCost(a: String, b: String): Double {
            if (a == b) return 0.0
            if (a[0] == 'V' && b[0] == 'V') return if (VNEAR.any { it.contains(a[1]) && it.contains(b[1]) }) 0.45 else 0.8
            val ca = consonant(a); val cb = consonant(b)
            if (ca != null && cb != null) {
                if (ca == cb) return 0.3
                val ga = CGROUP[ca]; val gb = CGROUP[cb]
                if (ga != null && ga == gb) return if (ga == 6) 0.5 else 0.35
                return 0.9
            }
            return 1.0
        }

        private fun indel(a: String) = when {
            a == "Cㅎ" -> 0.4
            a[0] == 'J' -> 0.5
            a[0] == 'C' -> 0.8
            else -> 1.0
        }

        fun similarity(x: String, y: String): Double {
            val a = sounds(x); val b = sounds(y)
            if (a.isEmpty() || b.isEmpty()) return 0.0
            var prev = DoubleArray(b.size + 1)
            for (j in 1..b.size) prev[j] = prev[j - 1] + indel(b[j - 1])
            for (i in 1..a.size) {
                val cur = DoubleArray(b.size + 1)
                cur[0] = prev[0] + indel(a[i - 1])
                for (j in 1..b.size) {
                    cur[j] = minOf(prev[j] + indel(a[i - 1]), cur[j - 1] + indel(b[j - 1]), prev[j - 1] + subCost(a[i - 1], b[j - 1]))
                }
                prev = cur
            }
            return 1 - prev[b.size] / maxOf(a.size, b.size)
        }
    }
}
