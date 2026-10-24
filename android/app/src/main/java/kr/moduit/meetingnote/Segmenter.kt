package kr.moduit.meetingnote

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.max

/**
 * 말소리 감지로 소리를 문장 단위로 자른다.
 * 0.35초 이상 조용하면 끊고, 계속 말해서 12초가 넘으면 직전 2초 중 가장 조용한 곳에서 끊는다.
 * onSegment(소리, 시작 위치(샘플 수))
 */
class Segmenter(vadModel: String, private val onSegment: (FloatArray, Long) -> Unit) {
    private val sr = 16000
    private val win = 512
    private val preRollMax = (sr * 0.4).toInt()
    private val silenceEnd = (sr * 0.35).toInt()
    private val maxSeg = sr * 12

    private val vad = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadModel,
                threshold = 0.5f, minSilenceDuration = 0.15f, minSpeechDuration = 0.2f,
                windowSize = win, maxSpeechDuration = 20f,
            ),
            sampleRate = sr, numThreads = 1,
        ),
    )

    private var preRoll = FloatArray(0)
    private var seg = FloatArray(0)
    private var segStart = 0L
    private var speaking = false
    private var silenceRun = 0
    private var pos = 0L
    private var winBuf = FloatArray(0)

    fun feed(samples: FloatArray) {
        winBuf += samples
        var off = 0
        while (winBuf.size - off >= win) {
            val w = winBuf.copyOfRange(off, off + win)
            off += win
            vad.acceptWaveform(w)
            val speech = vad.isSpeechDetected()
            vad.clear()
            step(w, speech)
            pos += win
        }
        if (off > 0) winBuf = winBuf.copyOfRange(off, winBuf.size)
    }

    private fun step(w: FloatArray, speech: Boolean) {
        if (!speaking) {
            if (speech) {
                speaking = true
                seg = preRoll + w
                segStart = max(0L, pos - preRoll.size)
                silenceRun = 0
            } else {
                val p = preRoll + w
                preRoll = if (p.size > preRollMax) p.copyOfRange(p.size - preRollMax, p.size) else p
            }
            return
        }
        seg += w
        silenceRun = if (speech) 0 else silenceRun + w.size
        if (silenceRun >= silenceEnd) {
            send(seg.copyOfRange(0, max(0, seg.size - silenceRun + sr / 5)), segStart)
            speaking = false
            preRoll = FloatArray(0)
            seg = FloatArray(0)
        } else if (seg.size >= maxSeg) {
            // 단어 중간에서 자르지 않도록 마지막 2초에서 가장 조용한 512샘플 구간을 찾는다
            val from = max(0, seg.size - sr * 2)
            var cut = seg.size
            var minE = Double.MAX_VALUE
            var i = from
            while (i + win <= seg.size) {
                var e = 0.0
                for (k in i until i + win) e += (seg[k] * seg[k]).toDouble()
                if (e < minE) { minE = e; cut = i + win / 2 }
                i += win / 2
            }
            send(seg.copyOfRange(0, cut), segStart)
            seg = seg.copyOfRange(cut, seg.size)
            segStart += cut
        }
    }

    private fun send(samples: FloatArray, start: Long) {
        if (samples.size >= sr / 4) onSegment(samples, start)
    }

    fun flush() {
        if (speaking && seg.size > sr / 4) send(seg, segStart)
        speaking = false
        seg = FloatArray(0)
    }

    fun release() {
        try { vad.release() } catch (_: Throwable) {}
    }
}
