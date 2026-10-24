package kr.moduit.meetingnote

/**
 * 여러 모델의 인식 결과를 글자 단위로 줄 맞춰 다수결로 합친다.
 * 공백·문장부호는 비교에서 빼고, 기준 결과(가장 가운데 결과)의 것을 그대로 살린다.
 */
object Combiner {

    data class Result(val text: String, val uncertain: Set<Int>)

    private const val SKIP = ".,!?·~…\"'“”‘’()-:;"
    private fun isSkip(c: Char) = c.isWhitespace() || SKIP.indexOf(c) >= 0

    /** 비교용 글자열과, 각 글자 앞에 붙어 있던 공백·문장부호(extras[i]), 맨 끝 꼬리(extras[n]) */
    private class Norm(val chars: String, val extras: List<String>)

    private fun norm(s: String): Norm {
        val sb = StringBuilder()
        val extras = mutableListOf<String>()
        var pending = StringBuilder()
        for (c in s) {
            if (isSkip(c)) pending.append(c)
            else {
                extras += pending.toString(); pending = StringBuilder()
                sb.append(c)
            }
        }
        extras += pending.toString()
        return Norm(sb.toString(), extras)
    }

    fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** 기준 p에 h를 맞춘 결과: sub[i] = p[i]에 대응하는 h의 글자(빠졌으면 null), ins[i] = p[i] 앞에 h가 더 넣은 글자 */
    private class Aligned(val sub: Array<Char?>, val ins: Array<String>)

    private fun align(p: String, h: String): Aligned {
        val n = p.length; val m = h.length
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (p[i - 1] == h[j - 1]) 0 else 1)
        }
        val sub = arrayOfNulls<Char>(n)
        val ins = Array(n + 1) { "" }
        var i = n; var j = m
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (p[i - 1] == h[j - 1]) 0 else 1) {
                sub[i - 1] = h[j - 1]; i--; j--
            } else if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                sub[i - 1] = null; i--
            } else {
                ins[i] = h[j - 1] + ins[i]; j--
            }
        }
        return Aligned(sub, ins)
    }

    /** hyps는 우선순위 순서(앞쪽이 더 믿을 만한 모델)로 넘긴다 */
    fun combine(hyps: List<String>): Result {
        val list = hyps.map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return Result("", emptySet())
        if (list.size == 1) return Result(list[0], emptySet())

        val norms = list.map { norm(it) }
        // 다른 결과들과 가장 가까운 것을 기준으로 (띄어쓰기 없는 결과는 기준으로 덜 고름)
        var best = 0; var bestScore = Int.MAX_VALUE
        for (i in norms.indices) {
            var score = 0
            for (j in norms.indices) if (i != j) score += distance(norms[i].chars, norms[j].chars)
            if (!list[i].contains(' ') && norms[i].chars.length > 6) score += 3
            if (score < bestScore) { bestScore = score; best = i }
        }
        val pv = norms[best]
        val p = pv.chars
        val others = norms.indices.filter { it != best }.map { align(p, norms[it].chars) }

        val out = StringBuilder()
        val unsure = mutableSetOf<Int>()
        for (i in 0..p.length) {
            out.append(pv.extras[i])
            // 끼워 넣기: 나머지 모두가 같은 글자를 넣었으면 채택
            val insAll = others.map { it.ins[i] }
            if (others.size >= 2 && insAll.all { it.isNotEmpty() && it == insAll[0] }) out.append(insAll[0])
            if (i == p.length) break
            val c = p[i]
            val votes = others.map { it.sub[i] }
            var chosen: Char? = c
            var doubt = false
            if (others.size >= 2) {
                val a = votes[0]; val b = votes[1]
                if (a == b && a != c) chosen = a          // 나머지 둘이 같은 다른 답 → 그쪽 채택(둘 다 빠뜨렸으면 삭제)
                else if (a != c && b != c) doubt = true   // 셋 다 다름 → 불확실
            } else {
                if (votes[0] != c) doubt = true           // 두 모델뿐이면 다를 때 불확실 표시
            }
            if (chosen != null) {
                if (doubt) unsure += out.length
                out.append(chosen)
            }
        }
        return Result(out.toString(), unsure)
    }
}
