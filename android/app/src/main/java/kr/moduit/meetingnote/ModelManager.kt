package kr.moduit.meetingnote

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 앱이 쓰는 음성 모델. 처음 한 번 내려받아 기기 안에 보관한다. */
data class ModelSpec(
    val key: String,
    val label: String,
    val url: String,
    val isArchive: Boolean,
    val dirName: String,
    /** 압축을 풀 때 int8이 아닌 큰 위스퍼 원본 파일은 건너뜀 */
    val skipFullPrecision: Boolean = false,
)

object Models {
    private const val ASR = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    private const val ENH = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/"

    val VAD = ModelSpec("vad", "말소리 구간 감지", ASR + "silero_vad.onnx", false, "vad")
    val STREAM_KO = ModelSpec(
        "stream_ko", "실시간 한국어 인식 (회색 자막)",
        ASR + "sherpa-onnx-streaming-zipformer-korean-2024-06-16.tar.bz2", true,
        "sherpa-onnx-streaming-zipformer-korean-2024-06-16",
    )
    val SENSE_VOICE = ModelSpec(
        "sense_voice", "SenseVoice",
        ASR + "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2", true,
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
    )
    val OFFLINE_KO = ModelSpec(
        "offline_ko", "한국어 전용",
        ASR + "sherpa-onnx-zipformer-korean-2024-06-24.tar.bz2", true,
        "sherpa-onnx-zipformer-korean-2024-06-24",
    )
    val WHISPER_SMALL = ModelSpec(
        "whisper_small", "위스퍼 (작은 것)",
        ASR + "sherpa-onnx-whisper-small.tar.bz2", true,
        "sherpa-onnx-whisper-small", skipFullPrecision = true,
    )
    val WHISPER_TURBO = ModelSpec(
        "whisper_turbo", "위스퍼 (큰 것)",
        ASR + "sherpa-onnx-whisper-turbo.tar.bz2", true,
        "sherpa-onnx-whisper-turbo", skipFullPrecision = true,
    )
    val DENOISE = ModelSpec("denoise", "잡음 제거", ENH + "gtcrn_simple.onnx", false, "denoise")
}

class ModelManager(private val context: Context) {
    val root: File = File(context.filesDir, "models").apply { mkdirs() }

    fun dir(spec: ModelSpec) = File(root, spec.dirName)
    private fun marker(spec: ModelSpec) = File(dir(spec), ".done")
    fun isReady(spec: ModelSpec) = marker(spec).exists()

    fun sizeMb(spec: ModelSpec): Long =
        if (!dir(spec).exists()) 0 else dir(spec).walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)

    /** 내려받기. progress(받은 MB, 전체 MB 또는 -1) */
    fun download(spec: ModelSpec, progress: (Long, Long) -> Unit) {
        val tmp = File(context.cacheDir, spec.key + ".part")
        tmp.delete()
        var url = URL(spec.url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw Exception("다운로드 주소 이동 정보가 없습니다")
                url = URL(url, loc)
                conn.disconnect()
                if (++redirects > 8) throw Exception("다운로드 주소가 너무 많이 바뀝니다")
                continue
            }
            if (code != 200) throw Exception("${spec.label} 다운로드 실패 (서버 응답 $code)")
            break
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1 shl 16)
                var got = 0L
                var lastMb = -1L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    val mb = got / (1024 * 1024)
                    if (mb != lastMb) {
                        lastMb = mb
                        progress(mb, if (total > 0) total / (1024 * 1024) else -1)
                    }
                }
            }
        }
        conn.disconnect()

        val target = dir(spec)
        target.deleteRecursively()
        if (spec.isArchive) {
            extractTarBz2(tmp, root, spec.skipFullPrecision)
        } else {
            target.mkdirs()
            tmp.copyTo(File(target, spec.url.substringAfterLast('/')), overwrite = true)
        }
        tmp.delete()
        if (!target.exists()) throw Exception("${spec.label}: 압축을 풀었지만 모델 폴더를 찾지 못했습니다")
        marker(spec).writeText("ok")
    }

    private fun extractTarBz2(file: File, dest: File, skipFull: Boolean) {
        val destPath = dest.canonicalPath
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(file.inputStream(), 1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name
                val out = File(dest, name)
                if (!out.canonicalPath.startsWith(destPath)) continue
                if (name.contains("/test_wavs/")) continue
                if (skipFull && name.endsWith(".onnx") && !name.contains(".int8.")) continue
                if (skipFull && name.endsWith(".weights")) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it) }
                }
            }
        }
    }

    /** 모델 폴더에서 이름에 key가 들어간 .onnx 파일 찾기 (int8 우선 여부 지정) */
    fun find(spec: ModelSpec, key: String, preferInt8: Boolean): String {
        val files = dir(spec).walkTopDown().filter { it.isFile && it.name.contains(key) && it.name.endsWith(".onnx") }.toList()
        val int8 = files.filter { it.name.contains(".int8.") }
        val plain = files.filter { !it.name.contains(".int8.") }
        val pick = if (preferInt8) (int8.firstOrNull() ?: plain.firstOrNull()) else (plain.firstOrNull() ?: int8.firstOrNull())
        return pick?.absolutePath ?: throw Exception("${spec.label} 모델에서 '$key' 파일을 찾지 못했습니다")
    }

    fun tokens(spec: ModelSpec): String =
        dir(spec).walkTopDown().firstOrNull { it.isFile && it.name.endsWith("tokens.txt") }?.absolutePath
            ?: throw Exception("${spec.label} 모델에서 tokens.txt 파일을 찾지 못했습니다")

    fun onnx(spec: ModelSpec): String =
        dir(spec).walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".onnx") }?.absolutePath
            ?: throw Exception("${spec.label} 모델 파일을 찾지 못했습니다")
}
