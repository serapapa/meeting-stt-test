package kr.moduit.meetingnote

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/** 확정 인식 모델 만들기 (실시간 받아쓰기와 일괄 비교에서 같이 씀) */
object Recognizers {
    private val feat = FeatureConfig(sampleRate = 16000, featureDim = 80)

    fun create(models: ModelManager, spec: ModelSpec, threads: Int): OfflineRecognizer {
        val modelConfig = when (spec) {
            Models.WHISPER_SMALL, Models.WHISPER_TURBO -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = models.find(spec, "encoder", true),
                    decoder = models.find(spec, "decoder", true),
                    language = "ko", task = "transcribe",
                ),
                tokens = models.tokens(spec), numThreads = threads, modelType = "whisper",
            )
            Models.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = models.find(spec, "model", true), language = "ko", useInverseTextNormalization = true,
                ),
                tokens = models.tokens(spec), numThreads = threads,
            )
            Models.OFFLINE_KO -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = models.find(spec, "encoder", true),
                    decoder = models.find(spec, "decoder", false),
                    joiner = models.find(spec, "joiner", true),
                ),
                tokens = models.tokens(spec), numThreads = threads, modelType = "transducer",
            )
            else -> throw IllegalArgumentException("확정 인식 모델이 아닙니다: ${spec.label}")
        }
        return OfflineRecognizer(null, OfflineRecognizerConfig(featConfig = feat, modelConfig = modelConfig))
    }

    fun decode(rec: OfflineRecognizer, samples: FloatArray): String {
        val s = rec.createStream()
        s.acceptWaveform(samples, 16000)
        rec.decode(s)
        val t = rec.getResult(s).text.trim()
        s.release()
        return t
    }

    fun denoiser(models: ModelManager): OfflineSpeechDenoiser = OfflineSpeechDenoiser(
        null,
        OfflineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = models.onnx(Models.DENOISE)),
                numThreads = 2,
            ),
        ),
    )

    fun denoise(d: OfflineSpeechDenoiser, samples: FloatArray): FloatArray {
        val r = d.run(samples, 16000)
        return if (r.sampleRate == 16000) r.samples else samples
    }
}
