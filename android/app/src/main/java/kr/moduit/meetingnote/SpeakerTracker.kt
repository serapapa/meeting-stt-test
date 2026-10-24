package kr.moduit.meetingnote

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.sqrt

/**
 * 문장마다 목소리 특징을 뽑아, 지금까지 나온 화자들과 비교해 같은 사람이면 그 번호를,
 * 아니면 새 번호를 준다. (회의 중 바로 붙이는 간이 화자 구분)
 */
class SpeakerTracker(modelPath: String, private val threshold: Float) {
    private val extractor = SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(model = modelPath, numThreads = 2))
    private val centroids = mutableListOf<FloatArray>()
    private val counts = mutableListOf<Int>()

    /** 화자 번호(1부터). 너무 짧은 문장은 판단하지 않고 0 */
    fun assign(samples: FloatArray): Int {
        if (samples.size < 16000 * 1) return 0
        val s = extractor.createStream()
        s.acceptWaveform(samples, 16000)
        s.inputFinished()
        if (!extractor.isReady(s)) { s.release(); return 0 }
        val emb = normalize(extractor.compute(s))
        s.release()

        var best = -1; var bestSim = -1f
        centroids.forEachIndexed { i, c ->
            val sim = dot(c, emb)
            if (sim > bestSim) { bestSim = sim; best = i }
        }
        if (best >= 0 && bestSim >= threshold) {
            // 평균 목소리를 조금씩 갱신
            val n = counts[best]
            val c = centroids[best]
            for (k in c.indices) c[k] = (c[k] * n + emb[k]) / (n + 1)
            centroids[best] = normalize(c)
            counts[best] = n + 1
            return best + 1
        }
        centroids += emb
        counts += 1
        return centroids.size
    }

    fun release() { try { extractor.release() } catch (_: Throwable) {} }

    private fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }
    private fun normalize(v: FloatArray): FloatArray {
        var n = 0f; for (x in v) n += x * x
        val d = sqrt(n).takeIf { it > 0 } ?: 1f
        return FloatArray(v.size) { v[it] / d }
    }
}
