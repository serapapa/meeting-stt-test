package kr.moduit.meetingnote

import java.io.File
import kotlin.math.ln

/** 노트의 한 구간 (제목 단위) */
class Chunk(
    val id: Int,
    val path: String,        // 저장소 안 경로
    val title: String,       // 노트 제목
    val heading: String,     // 구간 제목 (없으면 "")
    val text: String,        // 구간 본문
    val weight: Double,      // 폴더·상태에 따른 가중치
) {
    val flat: String = (title + " " + heading + " " + text).replace(Regex("\\s"), "").lowercase()
    val head: String = (title + heading).replace(Regex("\\s"), "").lowercase()
}

/** 회의 중 화면에 띄울 자료 한 건 */
class Hit(val chunk: Chunk, val score: Double, val keywords: List<String>)

/**
 * 고른 범위의 노트를 제목 단위로 잘라 두고, 최근 대화에서 뽑은 핵심 단어로 찾는다.
 * AI 없이 단어 기반으로 빠르게 찾는다(문장이 확정될 때마다).
 */
class ArchiveSearch(root: File, scopes: List<String>, private val dictTerms: List<String>) {
    val chunks = mutableListOf<Chunk>()

    init {
        val base = root.canonicalPath.length + 1
        val roots = if (scopes.isEmpty()) listOf(root) else scopes.map { File(root, it) }
        for (r in roots) {
            if (!r.exists()) continue
            r.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.forEach { f ->
                val path = f.canonicalPath.substring(base)
                if (f.length() > 400_000) return@forEach   // 아주 큰 노트(내보내기 덤프 등)는 건너뜀
                try { addFile(path, f.readText()) } catch (_: Throwable) {}
            }
        }
    }

    private fun addFile(path: String, raw: String) {
        var body = raw
        var title = path.substringAfterLast('/').removeSuffix(".md")
        var status = ""
        if (raw.startsWith("---")) {
            val end = raw.indexOf("\n---", 3)
            if (end > 0) {
                val fm = raw.substring(3, end)
                body = raw.substring(end + 4)
                Regex("(?m)^제목:\\s*\"?(.+?)\"?\\s*$").find(fm)?.let { title = it.groupValues[1] }
                Regex("(?m)^상태:\\s*\"?(.+?)\"?\\s*$").find(fm)?.let { status = it.groupValues[1] }
            }
        }
        var w = 1.0
        if (path.startsWith("보관/")) w *= 0.4
        if (path.startsWith("대화/")) w *= 0.7
        if (path.startsWith("00-수신함/")) w *= 0.8
        if (status.contains("확정")) w *= 1.3
        if (status.contains("대체") || status.contains("폐기")) w *= 0.5

        // 제목(#)마다 나누고, 너무 긴 구간은 1500자 단위로 다시 나눔
        val parts = mutableListOf<Pair<String, StringBuilder>>()
        var cur = "" to StringBuilder()
        for (line in body.lines()) {
            val m = Regex("^#{1,4}\\s+(.+)").find(line)
            if (m != null) {
                if (cur.second.isNotBlank() || cur.first.isNotEmpty()) parts += cur
                cur = m.groupValues[1].trim() to StringBuilder()
            } else cur.second.append(line).append('\n')
        }
        parts += cur
        for ((h, sb) in parts) {
            val text = sb.toString().trim()
            if (text.isEmpty() && h.isEmpty()) continue
            var i = 0
            do {
                val piece = text.substring(i, minOf(text.length, i + 1500))
                chunks += Chunk(chunks.size, path, title, if (h == title) "" else h, piece, w)
                i += 1500
            } while (i < text.length)
        }
    }

    // ---------------- 핵심 단어 뽑기 ----------------

    private val josa = listOf(
        "에서는", "으로는", "에게는", "이라고", "라고", "께서", "에서", "으로", "에게", "한테", "까지", "부터", "처럼", "이랑", "하고",
        "은", "는", "이", "가", "을", "를", "에", "로", "과", "와", "도", "만", "의", "께", "랑", "요",
    ).sortedByDescending { it.length }
    private val verbEnd = listOf("습니다", "니다", "어요", "아요", "해요", "었다", "했다", "하는", "하고", "해서", "하면", "는데", "지만", "겠다", "거든", "네요", "죠", "하지", "을까", "ㄹ까", "던데", "라던데", "까요", "세요")
    private val stop = setOf(
        "그래서", "그런데", "그리고", "그러면", "그럼", "이번", "오늘", "내일", "어제", "저희", "우리", "이거", "그거", "저거", "지금", "여기", "거기",
        "네", "아니", "맞습니다", "이제", "약간", "조금", "많이", "정말", "진짜", "그냥", "혹시", "일단", "다음", "이렇게", "그렇게", "어떻게", "뭔가",
        "때문", "정도", "부분", "생각", "얘기", "말씀", "관련", "경우", "하나", "두", "세", "이런", "저런", "그런", "있어요", "없어요", "됩니다",
        "확인", "필요", "진행", "문제", "예정", "정리", "내용", "사항", "건은", "보니까", "같은", "같아요",
    )

    fun keywords(text: String): List<String> {
        val out = linkedSetOf<String>()
        val flat = text.replace(Regex("\\s"), "")
        // 용어사전 단어는 그대로 핵심 단어로
        dictTerms.forEach { t -> if (flat.contains(t.replace(Regex("\\s"), ""))) out += t.replace(Regex("\\s"), "") }
        for (raw in text.split(Regex("[\\s.,!?·~…\"'“”‘’()\\[\\]:;/]+"))) {
            var w = raw.trim()
            if (w.length < 2) continue
            if (verbEnd.any { w.endsWith(it) }) continue
            for (j in josa) if (w.length - j.length >= 2 && w.endsWith(j)) { w = w.dropLast(j.length); break }
            if (w.length < 2 || w in stop) continue
            out += w.lowercase()
        }
        // 아카이브에서는 EAMS를 "EA시스템"으로 쓰므로 둘을 같은 말로 찾는다
        val l = out.map { it.lowercase() }
        if ("eams" in l && "ea시스템" !in l) out += "ea시스템"
        if ("ea시스템" in l && "eams" !in l) out += "eams"
        return out.map { it.lowercase() }.distinct()
    }

    /** 최근 대화로 자료 찾기. 상위 limit개 */
    fun search(recentText: String, limit: Int = 5): List<Hit> {
        val kws = keywords(recentText)
        if (kws.isEmpty() || chunks.isEmpty()) return emptyList()
        val n = chunks.size.toDouble()
        val scores = HashMap<Int, Double>()
        val matched = HashMap<Int, MutableList<String>>()
        for (k in kws) {
            val hits = chunks.filter { it.flat.contains(k) }
            if (hits.isEmpty()) continue
            if (hits.size > n * 0.15 && hits.size > 30) continue           // 너무 흔한 단어는 버림
            val idf = ln((n + 1) / (hits.size + 0.5))
            val boost = if (dictTerms.any { it.replace(Regex("\\s"), "").lowercase() == k }) 2.0 else 1.0
            for (c in hits) {
                var cnt = 0; var p = c.flat.indexOf(k)
                while (p >= 0 && cnt < 10) { cnt++; p = c.flat.indexOf(k, p + k.length) }
                val inHead = if (c.head.contains(k)) 2.0 else 1.0
                scores[c.id] = (scores[c.id] ?: 0.0) + idf * boost * inHead * (1 + ln(cnt.toDouble()))
                matched.getOrPut(c.id) { mutableListOf() }.add(k)
            }
        }
        // 단어 하나만 겹친 구간은 점수를 깎아 우연한 일치를 줄임
        return scores.entries
            .map { (id, s) -> val m = matched[id]!!; Hit(chunks[id], s * chunks[id].weight * (if (m.size >= 2) 1.0 else 0.5), m) }
            .sortedByDescending { it.score }
            .distinctBy { it.chunk.path }
            .take(limit)
    }
}
