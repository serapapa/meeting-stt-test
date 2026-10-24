package kr.moduit.meetingnote

import java.io.File

/** 일괄 비교 결과: 방식 이름 → 문장별 결과, 걸린 시간 */
class BatchResult(
    val segStartMs: List<Long>,
    val audioSec: Double,
    val outputs: LinkedHashMap<String, List<String>>,
    val decodeMs: LinkedHashMap<String, Long>,
    /** 합치기 방식의 문장별 불확실 글자 위치 */
    val uncertain: Map<String, List<Set<Int>>>,
    /** 문장별 모델 결과 (화면에 보여 줄 때 씀): 방식 이름 → 문장별 {모델 이름 → 결과} */
    val members: Map<String, List<Map<String, String>>>,
)

/**
 * 녹음 하나를 문장으로 한 번만 자른 뒤, 내려받아 둔 모든 확정 모델로 각각 인식하고
 * (잡음 제거 모델이 있으면 켠 것·끈 것 둘 다) 세 모델 합치기 조합도 만들어 비교한다.
 * 기기 부담을 줄이려고 모델은 하나씩 불러 쓰고 바로 내려놓는다.
 */
class BatchTester(
    private val models: ModelManager,
    private val wav: File,
    private val progress: (String) -> Unit,
) {
    @Volatile var cancelled = false

    fun run(): BatchResult {
        // 1) 문장 나누기
        progress("녹음을 문장으로 나누는 중…")
        val segs = mutableListOf<Pair<Long, FloatArray>>()
        val seg = Segmenter(models.onnx(Models.VAD)) { s, start -> segs += start to s }
        val r = WavReader16k(wav)
        try {
            while (true) { val s = r.read(1600) ?: break; seg.feed(s) }
        } finally { r.close(); seg.flush(); seg.release() }
        if (segs.isEmpty()) throw Exception("녹음에서 말소리를 찾지 못했습니다")
        val audioSec = r.totalSamples / 16000.0

        // 2) 잡음 제거 버전 만들기 (모델이 있으면)
        val variants = linkedMapOf("" to segs.map { it.second })
        if (models.isReady(Models.DENOISE)) {
            progress("잡음 제거 버전 만드는 중…")
            val d = Recognizers.denoiser(models)
            try { variants[" · 잡음 제거"] = segs.map { Recognizers.denoise(d, it.second) } } finally { d.release() }
        }

        // 3) 모델별 인식
        val specs = listOf(Models.WHISPER_TURBO, Models.WHISPER_SMALL, Models.SENSE_VOICE, Models.OFFLINE_KO).filter { models.isReady(it) }
        if (specs.isEmpty()) throw Exception("확정 인식 모델을 하나 이상 내려받아 주세요")
        val outputs = LinkedHashMap<String, List<String>>()
        val decodeMs = LinkedHashMap<String, Long>()
        val total = specs.size * variants.size
        var step = 0
        for (spec in specs) {
            if (cancelled) break
            val rec = Recognizers.create(models, spec, 4)
            try {
                for ((suffix, audio) in variants) {
                    step++
                    val key = spec.label + suffix
                    val t0 = System.currentTimeMillis()
                    val out = audio.mapIndexed { i, a ->
                        if (cancelled) return@mapIndexed ""
                        progress("모델별 인식 $step/$total · $key · 문장 ${i + 1}/${audio.size}")
                        Recognizers.decode(rec, a)
                    }
                    outputs[key] = out
                    decodeMs[key] = System.currentTimeMillis() - t0
                }
            } finally { rec.release() }
        }

        // 4) 세 모델 합치기 조합
        val uncertain = mutableMapOf<String, List<Set<Int>>>()
        val members = mutableMapOf<String, List<Map<String, String>>>()
        for (w in listOf(Models.WHISPER_TURBO, Models.WHISPER_SMALL)) {
            for (suffix in variants.keys) {
                val wk = w.label + suffix; val sk = Models.SENSE_VOICE.label + suffix; val kk = Models.OFFLINE_KO.label + suffix
                val wo = outputs[wk] ?: continue; val so = outputs[sk] ?: continue; val ko = outputs[kk] ?: continue
                val key = "합치기 [${w.label}+SenseVoice+한국어 전용]$suffix"
                val combined = segs.indices.map { i -> Combiner.combine(listOf(wo[i], so[i], ko[i])) }
                outputs[key] = combined.map { it.text }
                uncertain[key] = combined.map { it.uncertain }
                members[key] = segs.indices.map { i -> mapOf(Engines.WHISPER to wo[i], Engines.SENSE_VOICE to so[i], Engines.OFFLINE_KO to ko[i]) }
                // 실제로는 세 모델이 동시에 돌기 때문에 가장 느린 모델 시간에 가깝다
                decodeMs[key] = maxOf(decodeMs[wk] ?: 0, decodeMs[sk] ?: 0, decodeMs[kk] ?: 0)
            }
        }

        return BatchResult(segs.map { it.first * 1000 / 16000 }, audioSec, outputs, decodeMs, uncertain, members)
    }
}
