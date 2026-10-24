package kr.moduit.meetingnote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 회의 종류: 고르면 아카이브 범위와 참석자가 함께 정해진다 */
data class MeetingType(
    val id: String,
    val name: String,
    val scopes: List<String>,
    val participants: List<String>,
)

object MeetingTypes {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<MeetingType> {
        val arr = try { JSONArray(prefs(c).getString("meetingTypes", "[]")) } catch (_: Throwable) { JSONArray() }
        val out = mutableListOf<MeetingType>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += MeetingType(
                o.optString("id"), o.optString("name"),
                o.optJSONArray("scopes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                o.optJSONArray("participants")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            )
        }
        return out
    }

    fun save(c: Context, list: List<MeetingType>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("name", t.name)
                put("scopes", JSONArray(t.scopes)); put("participants", JSONArray(t.participants))
            })
        }
        prefs(c).edit().putString("meetingTypes", arr.toString()).apply()
    }
}

/** 아카이브의 용어사전 파일(_운영/회의노트-용어사전.md) 읽고 쓰기 */
object DictionaryFile {
    data class Dict(val terms: String, val pairs: String, val keep: String)

    fun parse(md: String): Dict {
        val sections = mutableMapOf<String, MutableList<String>>()
        var cur = ""
        for (line in md.lines()) {
            val h = Regex("^##\\s+(.+)").find(line)
            if (h != null) { cur = h.groupValues[1].trim(); continue }
            val b = Regex("^[-*]\\s+(.+)").find(line) ?: continue
            sections.getOrPut(cur) { mutableListOf() } += b.groupValues[1].trim()
        }
        fun pick(prefix: String) = sections.entries.firstOrNull { it.key.startsWith(prefix) }?.value ?: emptyList()
        return Dict(
            pick("용어").joinToString("\n"),
            pick("자주 틀리는 말").joinToString("\n"),
            pick("고치지 않을 말").joinToString(", "),
        )
    }

    fun format(d: Dict, today: String, keepLog: String?): String {
        val sb = StringBuilder()
        sb.append("---\n제목: 회의노트 용어사전\n갱신: ").append(today).append("\n출처: 회의노트 앱·클로드 새벽 정리\n---\n")
        sb.append("# 회의노트 용어사전\n\n")
        sb.append("회의노트 앱이 받아쓰기를 고칠 때 쓰는 사전. 앱과 매일 새벽 클로드 정리 작업이 함께 고친다.\n\n")
        sb.append("## 용어\n영어·줄임말은 `용어 = 읽는 소리, 읽는 소리`로 적는다.\n\n")
        d.terms.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { sb.append("- ").append(it).append('\n') }
        sb.append("\n## 자주 틀리는 말\n`틀린 말 → 바른 말`\n\n")
        d.pairs.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { sb.append("- ").append(it).append('\n') }
        sb.append("\n## 고치지 않을 말\n용어와 소리가 비슷하지만 원래 뜻대로 쓰는 말.\n\n")
        d.keep.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { sb.append("- ").append(it).append('\n') }
        if (!keepLog.isNullOrBlank()) sb.append('\n').append(keepLog.trim()).append('\n')
        return sb.toString()
    }

    /** 기존 파일의 "## 변경 기록" 이하를 그대로 보존 */
    fun changeLog(md: String?): String? {
        if (md == null) return null
        val i = md.indexOf("\n## 변경 기록")
        return if (i >= 0) md.substring(i + 1) else null
    }
}

/** 지난 회의록 찾기 */
object PastMeetings {
    class Summary(val date: String, val path: String, val text: String, val isMinutes: Boolean)

    private fun fm(md: String, key: String): String? =
        Regex("(?m)^$key:\\s*\"?(.+?)\"?\\s*$").find(md.substringBefore("\n---", ""))?.groupValues?.get(1)

    /** 이 회의 종류의 가장 최근 회의록(없으면 정리 전 원본 기록) */
    fun latest(root: File, typeName: String): Summary? {
        val dir = File(root, ArchiveStore.MEETING_DIR)
        val files = dir.listFiles { f -> f.name.endsWith(".md") }?.sortedByDescending { it.name } ?: return null
        var raw: Summary? = null
        for (f in files) {
            val md = try { f.readText() } catch (_: Throwable) { continue }
            if (fm(md, "회의종류") != typeName) continue
            val kind = fm(md, "종류") ?: ""
            val date = fm(md, "날짜") ?: f.name.take(10)
            if (kind.contains("회의록")) {
                val sec = section(md, "요약") ?: md.substringAfter("\n---").take(1200)
                val todo = section(md, "할 일")
                val txt = sec.trim() + (if (todo != null) "\n\n할 일\n" + todo.trim() else "")
                return Summary(date, f.path, txt, true)
            }
            if (raw == null) raw = Summary(date, f.path, (section(md, "받아쓰기") ?: "").lines().take(8).joinToString("\n"), false)
        }
        return raw
    }

    private fun section(md: String, name: String): String? {
        val m = Regex("(?m)^##\\s+$name.*$").find(md) ?: return null
        val rest = md.substring(m.range.last + 1)
        val next = Regex("(?m)^##\\s+").find(rest)
        return if (next != null) rest.substring(0, next.range.first) else rest
    }
}
