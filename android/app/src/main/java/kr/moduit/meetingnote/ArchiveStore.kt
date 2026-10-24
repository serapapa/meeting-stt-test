package kr.moduit.meetingnote

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream

/**
 * ryan-archive(깃허브 비공개 저장소)와 주고받기.
 * - sync(): 저장소 전체를 zip으로 받아 .md 파일만 기기 안에 풀어 둔다
 * - upload(): 파일 하나를 저장소에 올린다(새로 만들거나 덮어씀)
 */
class ArchiveStore(private val context: Context) {
    val repo = "serapapa/ryan-archive"
    val root: File = File(context.filesDir, "archive")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var token: String
        get() = prefs.getString("ghToken", "") ?: ""
        set(v) { prefs.edit().putString("ghToken", v.trim()).apply() }

    val lastSync: Long get() = prefs.getLong("archiveSyncAt", 0)
    val hasArchive: Boolean get() = File(root, "시작.md").exists() || (root.listFiles()?.isNotEmpty() ?: false)

    companion object {
        const val DICT_PATH = "_운영/회의노트-용어사전.md"
        const val MEETING_DIR = "대화/회의"
    }

    private fun open(url: String, method: String = "GET"): HttpURLConnection {
        if (token.isBlank()) throw Exception("설정에서 깃허브 열쇠(토큰)를 먼저 넣어 주세요")
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000; c.readTimeout = 120000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        return c
    }

    private fun failMessage(code: Int) = when (code) {
        401 -> "깃허브 열쇠가 맞지 않습니다. 설정에서 다시 넣어 주세요"
        403 -> "깃허브 열쇠에 이 저장소 권한이 없습니다 (내용 읽기·쓰기 권한 필요)"
        404 -> "저장소를 찾지 못했습니다. 열쇠가 ryan-archive에 접근할 수 있는지 확인해 주세요"
        else -> "깃허브 응답 오류 ($code)"
    }

    /** 저장소 전체를 받아 .md만 풀어 둔다. progress(받은 MB) */
    fun sync(progress: (String) -> Unit) {
        var url = "https://api.github.com/repos/$repo/zipball"
        var conn = open(url)
        conn.instanceFollowRedirects = false
        var hops = 0
        while (conn.responseCode in 300..399) {
            url = conn.getHeaderField("Location") ?: throw Exception("다운로드 주소 이동 정보가 없습니다")
            conn.disconnect()
            // 깃허브가 넘겨주는 임시 주소에는 열쇠를 다시 붙이지 않는다
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000; conn.readTimeout = 120000
            if (++hops > 5) throw Exception("다운로드 주소가 너무 많이 바뀝니다")
        }
        if (conn.responseCode != 200) throw Exception(failMessage(conn.responseCode))

        val tmp = File(context.filesDir, "archive.tmp")
        tmp.deleteRecursively(); tmp.mkdirs()
        var bytes = 0L; var count = 0
        ZipInputStream(conn.inputStream.buffered(1 shl 16)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.substringAfter('/', "")   // 맨 앞 "serapapa-ryan-archive-xxxx/" 제거
                if (e.isDirectory || name.isEmpty() || !name.endsWith(".md")) continue
                val out = File(tmp, name)
                if (!out.canonicalPath.startsWith(tmp.canonicalPath)) continue
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { o -> bytes += zip.copyTo(o) }
                if (++count % 200 == 0) progress("아카이브 받는 중 · 노트 ${count}개 · ${bytes / 1048576}MB")
            }
        }
        conn.disconnect()
        if (count == 0) throw Exception("받은 노트가 없습니다")
        root.deleteRecursively()
        tmp.renameTo(root)
        prefs.edit().putLong("archiveSyncAt", System.currentTimeMillis()).apply()
        progress("아카이브 받기 끝 · 노트 ${count}개")
    }

    private fun encodePath(path: String) =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** 파일 올리기 (있으면 덮어씀) */
    fun upload(path: String, content: String, message: String) {
        val api = "https://api.github.com/repos/$repo/contents/${encodePath(path)}"
        var sha: String? = null
        val g = open(api)
        if (g.responseCode == 200) sha = JSONObject(g.inputStream.bufferedReader().readText()).optString("sha", null)
        else if (g.responseCode != 404) throw Exception(failMessage(g.responseCode))
        g.disconnect()

        val body = JSONObject().apply {
            put("message", message)
            put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            if (sha != null) put("sha", sha)
        }
        val p = open(api, "PUT")
        p.doOutput = true
        p.setRequestProperty("Content-Type", "application/json")
        p.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = p.responseCode
        p.disconnect()
        if (code !in 200..201) throw Exception(failMessage(code))
        // 기기 안 사본도 같이 갱신
        File(root, path).apply { parentFile?.mkdirs(); writeText(content) }
    }

    fun read(path: String): String? = File(root, path).takeIf { it.exists() }?.readText()

    /** 범위 고르기용 폴더 목록: 1단계와 그 아래 2단계 */
    fun folders(): List<Pair<String, List<String>>> {
        val tops = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name } ?: return emptyList()
        return tops.map { t ->
            t.name to (t.listFiles { f -> f.isDirectory }?.map { "${t.name}/${it.name}" }?.sorted() ?: emptyList())
        }
    }

    /** 아직 못 올린 회의 기록 (인터넷이 안 될 때 모아 둠) */
    val pendingDir: File get() = File(context.filesDir, "pending").apply { mkdirs() }

    fun queueUpload(path: String, content: String) {
        File(pendingDir, path.replace('/', '∕')).writeText(content)
    }

    /** 모아 둔 기록 올리기. 올린 개수 */
    fun flushPending(): Int {
        var n = 0
        pendingDir.listFiles()?.forEach { f ->
            try {
                upload(f.name.replace('∕', '/'), f.readText(), "회의 노트 앱: 회의 기록 올림")
                f.delete(); n++
            } catch (_: Throwable) {}
        }
        return n
    }
}
