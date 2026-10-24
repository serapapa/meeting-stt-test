package kr.moduit.meetingnote

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 확정 인식에 쓸 모델 */
enum class FinalModel(val label: String) {
    SENSE_VOICE("SenseVoice (빠름)"),
    OFFLINE_KO("한국어 전용 (정확도 비교용)"),
    NONE("확정 인식 없이 실시간 결과 그대로"),
}

/** 마이크 소리 받는 방식 */
enum class MicMode(val label: String, val source: Int) {
    UNPROCESSED("원음 (걸러 내지 않음)", MediaRecorder.AudioSource.UNPROCESSED),
    RECOGNITION("음성인식용 (약하게 걸러 냄)", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    MIC("일반 마이크", MediaRecorder.AudioSource.MIC),
}

interface TranscriberListener {
    fun onStatus(text: String)
    fun onInterim(text: String)
    /** 한 사람이 말을 마친 구간의 확정 문장. startMs: 녹음 시작부터, latencyMs: 말이 끝난 뒤 확정까지 */
    fun onFinal(text: String, startMs: Long, endMs: Long, latencyMs: Long)
    fun onLevel(level: Float)
    fun onError(message: String)
}

class Transcriber(
    private val models: ModelManager,
    private val finalModel: FinalModel,
    private val micMode: MicMode,
    private val gain: Float,
    private val listener: TranscriberListener,
) {
    private val sampleRate = 16000
    @Volatile private var running = false
    private var thread: Thread? = null
    private val finalExecutor = Executors.newSingleThreadExecutor()

    private var online: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var vad: Vad? = null
    private var offline: OfflineRecognizer? = null

    fun start() {
        running = true
        thread = Thread({ runSafely() }, "audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(4000)
        thread = null
        finalExecutor.shutdown()
        finalExecutor.awaitTermination(15, TimeUnit.SECONDS)
        try { offline?.release() } catch (_: Throwable) {}
        offline = null
    }

    private fun runSafely() {
        try {
            loadModels()
            record()
        } catch (t: Throwable) {
            listener.onError(t.message ?: t.toString())
        } finally {
            try { stream?.release() } catch (_: Throwable) {}
            try { online?.release() } catch (_: Throwable) {}
            try { vad?.release() } catch (_: Throwable) {}
            stream = null; online = null; vad = null
        }
    }

    private fun loadModels() {
        listener.onStatus("모델 불러오는 중…")
        val cores = Runtime.getRuntime().availableProcessors()

        val st = Models.STREAM_KO
        online = OnlineRecognizer(
            null,
            OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = models.find(st, "encoder", true),
                        decoder = models.find(st, "decoder", false),
                        joiner = models.find(st, "joiner", true),
                    ),
                    tokens = models.tokens(st),
                    numThreads = 2,
                    modelType = "zipformer",
                ),
                enableEndpoint = false,
                decodingMethod = "greedy_search",
            ),
        )
        stream = online!!.createStream()

        vad = Vad(
            null,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = models.dir(Models.VAD).resolve("silero_vad.onnx").absolutePath,
                    threshold = 0.45f,
                    minSilenceDuration = 0.45f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 15f,
                ),
                sampleRate = sampleRate,
                numThreads = 1,
            ),
        )

        val threads = max(2, min(4, cores - 2))
        offline = when (finalModel) {
            FinalModel.SENSE_VOICE -> {
                val sv = Models.SENSE_VOICE
                OfflineRecognizer(
                    null,
                    OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(
                                model = models.find(sv, "model", true),
                                language = "ko",
                                useInverseTextNormalization = true,
                            ),
                            tokens = models.tokens(sv),
                            numThreads = threads,
                        ),
                    ),
                )
            }
            FinalModel.OFFLINE_KO -> {
                val ko = Models.OFFLINE_KO
                OfflineRecognizer(
                    null,
                    OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            transducer = OfflineTransducerModelConfig(
                                encoder = models.find(ko, "encoder", true),
                                decoder = models.find(ko, "decoder", false),
                                joiner = models.find(ko, "joiner", true),
                            ),
                            tokens = models.tokens(ko),
                            numThreads = threads,
                            modelType = "transducer",
                        ),
                    ),
                )
            }
            FinalModel.NONE -> null
        }
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = sampleRate / 10 // 0.1초
        val ar = AudioRecord(micMode.source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, chunk * 2 * 4))
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw Exception("마이크를 열지 못했습니다. 설정에서 다른 마이크 방식을 골라 보세요.")
        }
        ar.startRecording()
        listener.onStatus("받아쓰는 중")

        val shorts = ShortArray(chunk)
        val vadWindow = 512
        var vadBuf = FloatArray(0)
        var lastInterim = ""
        var totalSamples = 0L
        var levelTick = 0

        try {
            while (running) {
                val n = ar.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                val samples = FloatArray(n)
                var peak = 0f
                for (i in 0 until n) {
                    val v = (shorts[i] / 32768f) * gain
                    val c = if (v > 1f) 1f else if (v < -1f) -1f else v
                    samples[i] = c
                    val a = abs(c)
                    if (a > peak) peak = a
                }
                totalSamples += n
                if (++levelTick % 2 == 0) listener.onLevel(peak)

                // 1차: 실시간 자막
                val on = online!!; val st = stream!!
                st.acceptWaveform(samples, sampleRate)
                while (on.isReady(st)) on.decode(st)
                val interim = on.getResult(st).text.trim()
                if (interim != lastInterim) {
                    lastInterim = interim
                    listener.onInterim(interim)
                }

                // 말소리 구간 감지
                vadBuf += samples
                var off = 0
                while (vadBuf.size - off >= vadWindow) {
                    vad!!.acceptWaveform(vadBuf.copyOfRange(off, off + vadWindow))
                    off += vadWindow
                }
                if (off > 0) vadBuf = vadBuf.copyOfRange(off, vadBuf.size)

                while (!vad!!.empty()) {
                    val seg = vad!!.front()
                    vad!!.pop()
                    val startMs = seg.start * 1000L / sampleRate
                    val endMs = startMs + seg.samples.size * 1000L / sampleRate
                    val snapshot = lastInterim
                    on.reset(st)
                    lastInterim = ""
                    listener.onInterim("")
                    submitFinal(seg.samples, snapshot, startMs, endMs, System.currentTimeMillis())
                }
            }
        } finally {
            try { ar.stop() } catch (_: Throwable) {}
            ar.release()
            // 말하던 중에 정지하면 남은 구간도 확정
            try {
                vad?.flush()
                while (vad != null && !vad!!.empty()) {
                    val seg = vad!!.front(); vad!!.pop()
                    val startMs = seg.start * 1000L / sampleRate
                    submitFinal(seg.samples, lastInterim, startMs, startMs + seg.samples.size * 1000L / sampleRate, System.currentTimeMillis())
                }
            } catch (_: Throwable) {}
            listener.onStatus("멈춤")
        }
    }

    private fun submitFinal(samples: FloatArray, interimSnapshot: String, startMs: Long, endMs: Long, endedAt: Long) {
        val rec = offline
        if (rec == null) {
            if (interimSnapshot.isNotBlank()) listener.onFinal(interimSnapshot, startMs, endMs, 0)
            return
        }
        finalExecutor.execute {
            try {
                val s = rec.createStream()
                s.acceptWaveform(samples, sampleRate)
                rec.decode(s)
                val text = rec.getResult(s).text.trim()
                s.release()
                val shown = if (text.isNotBlank()) text else interimSnapshot
                if (shown.isNotBlank()) listener.onFinal(shown, startMs, endMs, System.currentTimeMillis() - endedAt)
            } catch (t: Throwable) {
                listener.onError("확정 인식 오류: ${t.message}")
            }
        }
    }
}
