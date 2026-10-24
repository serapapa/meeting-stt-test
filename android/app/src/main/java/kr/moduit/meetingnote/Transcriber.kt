package kr.moduit.meetingnote

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/** 확정 인식 방식 */
enum class FinalMode(val label: String) {
    ENSEMBLE("세 모델 합치기: 위스퍼 큰 것+작은 것+SenseVoice (권장)"),
    WHISPER("위스퍼만"),
    SENSE_VOICE("SenseVoice만"),
    OFFLINE_KO("한국어 전용만"),
    NONE("확정 없이 실시간 결과 그대로"),
}

/** 마이크 소리 받는 방식 */
enum class MicMode(val label: String, val source: Int) {
    UNPROCESSED("원음 (걸러 내지 않음)", MediaRecorder.AudioSource.UNPROCESSED),
    RECOGNITION("음성인식용 (약하게 걸러 냄)", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    MIC("일반 마이크", MediaRecorder.AudioSource.MIC),
}

data class Options(
    val mode: FinalMode,
    val whisper: ModelSpec,
    val denoise: Boolean,
    val mic: MicMode,
    val gain: Float,
)

/** 확정 인식에 들어가는 모델 이름 (우선순위 순) */
object Engines {
    const val WHISPER_L = "위스퍼(큰 것)"
    const val WHISPER_S = "위스퍼(작은 것)"
    const val SENSE_VOICE = "SenseVoice"
    const val OFFLINE_KO = "한국어 전용"
    val ORDER = listOf(WHISPER_L, WHISPER_S, SENSE_VOICE, OFFLINE_KO)
    fun whisperName(spec: ModelSpec) = if (spec == Models.WHISPER_TURBO) WHISPER_L else WHISPER_S

    fun needed(o: Options): List<ModelSpec> {
        val l = mutableListOf(Models.VAD, Models.STREAM_KO)
        when (o.mode) {
            FinalMode.ENSEMBLE -> { l += Models.WHISPER_TURBO; l += Models.WHISPER_SMALL; l += Models.SENSE_VOICE }
            FinalMode.WHISPER -> l += o.whisper
            FinalMode.SENSE_VOICE -> l += Models.SENSE_VOICE
            FinalMode.OFFLINE_KO -> l += Models.OFFLINE_KO
            FinalMode.NONE -> {}
        }
        if (o.denoise) l += Models.DENOISE
        return l
    }
}

data class FinalResult(
    val text: String,
    val uncertain: Set<Int>,
    val hyps: Map<String, String>,
    val startMs: Long,
    val endMs: Long,
    val latencyMs: Long,
)

interface TranscriberListener {
    fun onStatus(text: String)
    fun onInterim(text: String)
    fun onFinal(r: FinalResult)
    fun onLevel(level: Float)
    fun onProgress(percent: Int)
    fun onError(message: String)
    fun onFinished()
}

/**
 * 마이크(또는 저장된 녹음)에서 소리를 받아
 *  1) 실시간 모델로 회색 자막을 띄우고
 *  2) 말소리 구간을 잘라 확정 모델(들)로 다시 인식해 검은 글씨로 확정한다.
 */
class Transcriber(
    private val models: ModelManager,
    private val opt: Options,
    private val listener: TranscriberListener,
    private val inputWav: File? = null,
    private val recordTo: File? = null,
) {
    private val sr = 16000
    @Volatile private var running = false
    private var thread: Thread? = null

    private val segWorker = Executors.newSingleThreadExecutor()
    private val pool = Executors.newFixedThreadPool(3)

    private var online: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var segmenter: Segmenter? = null
    private var denoiser: OfflineSpeechDenoiser? = null
    private val engines = linkedMapOf<String, OfflineRecognizer>()

    fun start() {
        running = true
        thread = Thread({ run() }, "audio").also { it.start() }
    }

    /** 멈추라고 알린다. 남은 확정 인식이 끝나면 onFinished가 불린다 */
    fun stop() {
        running = false
    }

    private fun run() {
        try {
            if (inputWav != null) { load(); fromFile(inputWav) } else fromMic()
        } catch (t: Throwable) {
            listener.onError(t.message ?: t.toString())
        } finally {
            segWorker.shutdown()
            segWorker.awaitTermination(120, TimeUnit.SECONDS)
            pool.shutdown()
            listOf<() -> Unit>(
                { stream?.release() }, { online?.release() }, { segmenter?.release() }, { denoiser?.release() },
            ).forEach { try { it() } catch (_: Throwable) {} }
            engines.values.forEach { try { it.release() } catch (_: Throwable) {} }
            listener.onInterim("")
            listener.onFinished()
        }
    }

    // ---------------- 모델 불러오기 ----------------

    private fun load() {
        listener.onStatus("모델 불러오는 중…")
        val feat = FeatureConfig(sampleRate = sr, featureDim = 80)

        if (inputWav == null) {
            val st = Models.STREAM_KO
            online = OnlineRecognizer(
                null,
                OnlineRecognizerConfig(
                    featConfig = feat,
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = models.find(st, "encoder", true),
                            decoder = models.find(st, "decoder", false),
                            joiner = models.find(st, "joiner", true),
                        ),
                        tokens = models.tokens(st), numThreads = 2, modelType = "zipformer",
                    ),
                    enableEndpoint = false,
                ),
            )
            stream = online!!.createStream()
        }

        segmenter = Segmenter(models.onnx(Models.VAD)) { samples, start -> emit(samples, start) }
        if (opt.denoise) denoiser = Recognizers.denoiser(models)

        val th = if (opt.mode == FinalMode.ENSEMBLE) 2 else 4
        when (opt.mode) {
            FinalMode.ENSEMBLE -> {
                engines[Engines.WHISPER_L] = Recognizers.create(models, Models.WHISPER_TURBO, th)
                engines[Engines.WHISPER_S] = Recognizers.create(models, Models.WHISPER_SMALL, th)
                engines[Engines.SENSE_VOICE] = Recognizers.create(models, Models.SENSE_VOICE, th)
            }
            FinalMode.WHISPER -> engines[Engines.whisperName(opt.whisper)] = Recognizers.create(models, opt.whisper, th)
            FinalMode.SENSE_VOICE -> engines[Engines.SENSE_VOICE] = Recognizers.create(models, Models.SENSE_VOICE, th)
            FinalMode.OFFLINE_KO -> engines[Engines.OFFLINE_KO] = Recognizers.create(models, Models.OFFLINE_KO, th)
            FinalMode.NONE -> {}
        }
    }

    private var lastInterim = ""

    private fun emit(samples: FloatArray, segStart: Long) {
        val startMs = segStart * 1000 / sr
        val endMs = startMs + samples.size * 1000L / sr
        val snapshot = lastInterim
        online?.let { on -> stream?.let { on.reset(it) } }
        lastInterim = ""
        if (online != null) listener.onInterim("")
        val endedAt = System.currentTimeMillis()
        if (samples.size < sr / 4) return
        segWorker.execute { recognize(samples, snapshot, startMs, endMs, endedAt) }
    }

    private fun recognize(samples: FloatArray, snapshot: String, startMs: Long, endMs: Long, endedAt: Long) {
        try {
            if (engines.isEmpty()) {
                if (snapshot.isNotBlank()) listener.onFinal(FinalResult(snapshot, emptySet(), emptyMap(), startMs, endMs, 0))
                return
            }
            val clean = denoiser?.let { Recognizers.denoise(it, samples) } ?: samples
            val futures = engines.map { (name, rec) -> name to pool.submit(Callable { Recognizers.decode(rec, clean) }) }
            val got = futures.associate { (name, f) -> name to (try { f.get(120, TimeUnit.SECONDS) } catch (t: Throwable) { "" }) }
            val hyps = LinkedHashMap<String, String>()
            Engines.ORDER.forEach { n -> got[n]?.let { hyps[n] = it } }
            val ordered = hyps.values.toList()
            val combined = Combiner.combine(ordered)
            val text = combined.text.ifBlank { snapshot }
            if (text.isNotBlank()) {
                listener.onFinal(FinalResult(text, combined.uncertain, hyps, startMs, endMs, System.currentTimeMillis() - endedAt))
            }
        } catch (t: Throwable) {
            listener.onError("확정 인식 오류: ${t.message}")
        }
    }

    // ---------------- 소리 받기 ----------------

    /**
     * 시작하자마자 마이크를 연다. 소리는 따로 받는 스레드가 큐에 모아 두고,
     * 이 스레드는 모델을 불러온 뒤 쌓인 소리부터 차례로 처리한다.
     */
    @SuppressLint("MissingPermission")
    private fun fromMic() {
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = sr / 10
        val ar = AudioRecord(opt.mic.source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, chunk * 2 * 4))
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw Exception("마이크를 열지 못했습니다. 설정에서 다른 마이크 방식을 골라 보세요.")
        }
        val queue = LinkedBlockingQueue<FloatArray>()
        val writer = recordTo?.let { WavWriter(it) }
        ar.startRecording()
        val capture = Thread({
            val shorts = ShortArray(chunk)
            var tick = 0
            try {
                while (running) {
                    val n = ar.read(shorts, 0, shorts.size)
                    if (n <= 0) continue
                    val samples = FloatArray(n)
                    var peak = 0f
                    for (i in 0 until n) {
                        val v = (shorts[i] / 32768f) * opt.gain
                        val c = if (v > 1f) 1f else if (v < -1f) -1f else v
                        samples[i] = c
                        val a = abs(c); if (a > peak) peak = a
                    }
                    if (++tick % 2 == 0) listener.onLevel(peak)
                    try { writer?.write(samples) } catch (_: Throwable) {}
                    queue.put(samples)
                }
            } finally {
                try { ar.stop() } catch (_: Throwable) {}
                ar.release()
                try { writer?.close() } catch (_: Throwable) {}
            }
        }, "capture")
        capture.start()
        listener.onStatus("말씀하셔도 됩니다 · 모델 불러오는 중…")

        try {
            load()
            listener.onStatus("받아쓰는 중")
            while (running || capture.isAlive || queue.isNotEmpty()) {
                val samples = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val on = online!!; val st = stream!!
                st.acceptWaveform(samples, sr)
                while (on.isReady(st)) on.decode(st)
                val interim = on.getResult(st).text.trim()
                if (interim != lastInterim) { lastInterim = interim; listener.onInterim(interim) }
                segmenter!!.feed(samples)
            }
        } finally {
            running = false
            capture.join(3000)
            segmenter?.flush()
            listener.onStatus("정리하는 중…")
        }
    }

    private fun fromFile(file: File) {
        val r = WavReader16k(file)
        listener.onStatus("녹음 다시 인식 중…")
        var done = 0L
        var lastPct = -1
        try {
            while (running) {
                val samples = r.read(sr / 10) ?: break
                segmenter!!.feed(samples)
                done += samples.size
                val pct = if (r.totalSamples > 0) (done * 100 / r.totalSamples).toInt() else 0
                if (pct != lastPct) { lastPct = pct; listener.onProgress(pct) }
            }
        } finally {
            r.close()
            segmenter?.flush()
        }
    }
}
