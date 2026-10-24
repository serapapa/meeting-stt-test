package kr.moduit.meetingnote

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.max

/**
 * 말소리 감지로 소리를 문장 단위로 자른다.
 * 0.6초 이상 조용하면 끊고, 계속 말해도 12초가 넘으면 강제로 끊는다.
 * onSegment(소리, 시작 위치(샘플 수))
 */
class Segmenter(vadModel: String, private val onSegment: (FloatArray, Long) -> Unit) {
    private val sr = 16000
    private val win = 512
    private val preRollMax = (sr * 0.4).toInt()
    private val silenceEnd = (sr * 0.6).toInt()
    private val maxSeg = sr * 12

    private val vad = Vad(
        null,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadModel,
                threshold = 0.5f, minSilenceDuration = 0.3f, minSpeechDuration = 0.2f,
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
            send(seg, segStart)
            seg = FloatArray(0)
            segStart = pos + win
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
