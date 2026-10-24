package kr.moduit.meetingnote

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TranscriberListener {

    // 색 (흰 바탕 기준)
    private val cBg = Color.parseColor("#F7F8FA")
    private val cPaper = Color.WHITE
    private val cInk = Color.parseColor("#1D2430")
    private val cMuted = Color.parseColor("#6B7482")
    private val cLine = Color.parseColor("#E2E6EC")
    private val cAccent = Color.parseColor("#1F6FEB")
    private val cAccentSoft = Color.parseColor("#E8F0FE")
    private val cRec = Color.parseColor("#D93025")
    private val cOk = Color.parseColor("#1E7A46")

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var models: ModelManager
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private lateinit var btnMain: Button
    private lateinit var statusView: TextView
    private lateinit var clockView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var modelBox: LinearLayout
    private lateinit var modelStatus: TextView
    private lateinit var btnDownload: Button
    private lateinit var spFinal: Spinner
    private lateinit var spMic: Spinner
    private lateinit var spGain: Spinner
    private lateinit var linesBox: LinearLayout
    private lateinit var interimView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var scriptBox: LinearLayout
    private lateinit var compareView: TextView
    private lateinit var btnClear: Button
    private lateinit var statsView: TextView

    private var transcriber: Transcriber? = null
    private var startedAt = 0L
    private var downloading = false
    private var clearArmed = false

    private data class Line(val startMs: Long, val text: String, val latencyMs: Long)
    private val lines = mutableListOf<Line>()
    private val latencies = mutableListOf<Long>()

    private val gains = listOf(1f, 2f, 3f, 4f, 6f)

    private val script = listOf(
        "라이언" to "이번 주 EAMS 주간보고는 금요일 오전에 기업은행 쪽에 올릴 예정입니다.",
        "상대" to "화면설계서 수정본은 깃허브에 올라가 있나요?",
        "라이언" to "네, 깃허브랑 노션 둘 다 올려 두었고 단위테스트 결과도 같이 정리했습니다.",
        "상대" to "요구사항 변경 건은 전진영 이사님 확인이 필요하지 않을까요?",
        "라이언" to "맞습니다. 전진영 이사님께는 오늘 오후에 따로 말씀드리겠습니다.",
        "상대" to "모두잇 쪽 제안서는 어떻게 되고 있어요?",
        "라이언" to "제안서 초안은 클로드로 정리해서 구글 드라이브 아카이브에 넣어 두었습니다. 심지훈 차장님이 보시고 사티스 일정과 맞춰 주시기로 했습니다.",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = ModelManager(this)
        window.statusBarColor = cBg
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildUi())
        refreshModels()
        ui.post(clockTick)
    }

    // ---------------- 화면 구성 ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            setColor(cPaper); cornerRadius = dp(14).toFloat(); setStroke(dp(1), cLine)
        }
    }

    private fun label(text: String, size: Float = 13f, color: Int = cMuted, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(text: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) Color.WHITE else cInk)
        background = GradientDrawable().apply {
            setColor(if (primary) cAccent else cPaper); cornerRadius = dp(10).toFloat()
            if (!primary) setStroke(dp(1), cLine)
        }
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { onClick() }
    }

    private fun spinner(items: List<String>, key: String, def: Int, onPick: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(prefs.getInt(key, def).coerceIn(0, items.size - 1))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putInt(key, pos).apply(); onPick(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        // 머리
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(label("회의 노트", 22f, cInk, true))
        statusView = label("모델을 확인하는 중", 13f)
        titleCol.addView(statusView)
        head.addView(titleCol, lp(0, WRAP_CONTENT, 1f))
        clockView = label("00:00:00", 18f, cInk).apply { typeface = Typeface.MONOSPACE; setPadding(0, 0, dp(12), 0) }
        head.addView(clockView)
        btnMain = button("받아쓰기 시작", true) { onMainClick() }
        head.addView(btnMain, LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)))
        root.addView(head)

        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        root.addView(levelBar, lp(top = 6).apply { height = dp(6) })

        // 위쪽 영역(스크롤): 모델·설정·원고
        val topScroll = ScrollView(this)
        val topCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topScroll.addView(topCol)

        // 모델
        modelBox = card()
        modelBox.addView(label("음성 모델", 12f, cMuted, true))
        modelStatus = label("", 14f, cInk)
        modelBox.addView(modelStatus, lp(top = 4))
        btnDownload = button("필요한 모델 내려받기 (와이파이 권장)") { downloadNeeded() }
        modelBox.addView(btnDownload, lp(top = 8).apply { height = dp(44) })
        topCol.addView(modelBox, lp(top = 12))

        // 설정
        val setCard = card()
        setCard.addView(label("설정 (다음 시작부터 적용)", 12f, cMuted, true))
        setCard.addView(label("확정 인식 모델", 13f), lp(top = 6))
        spFinal = spinner(FinalModel.values().map { it.label }, "final", 0) { refreshModels() }
        setCard.addView(spFinal)
        setCard.addView(label("마이크 방식", 13f), lp(top = 6))
        spMic = spinner(MicMode.values().map { it.label }, "mic", 0) {}
        setCard.addView(spMic)
        setCard.addView(label("소리 키우기", 13f), lp(top = 6))
        spGain = spinner(gains.map { "${it.toInt()}배" }, "gain", 1) {}
        setCard.addView(spGain)
        setCard.addView(label("위 막대가 말할 때 절반쯤 차면 적당합니다. 멀리 있는 사람 목소리가 약하면 키우세요.", 12f), lp(top = 4))
        topCol.addView(setCard, lp(top = 12))

        // 원고
        val scCard = card()
        val scHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        scHead.addView(label("시험용 원고 · 두 사람 대화", 12f, cAccent, true), lp(0, WRAP_CONTENT, 1f))
        lateinit var btnToggle: Button
        btnToggle = button("펼치기") { toggleScript(btnToggle) }
        scHead.addView(btnToggle, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)))
        scCard.addView(scHead)
        scriptBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        script.forEach { (who, say) ->
            val t = TextView(this).apply {
                val sb = SpannableStringBuilder()
                sb.append(who + "  ")
                sb.setSpan(ForegroundColorSpan(if (who == "라이언") cAccent else cOk), 0, who.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.8f), 0, who.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(say)
                text = sb; textSize = 18f; setTextColor(cInk); setLineSpacing(0f, 1.3f)
            }
            scriptBox.addView(t, lp(top = 8))
        }
        scriptBox.addView(button("원고와 비교하기") { compare() }, lp(top = 10).apply { height = dp(44) })
        compareView = label("", 14f, cInk).apply {
            visibility = View.GONE; setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(cAccentSoft); cornerRadius = dp(10).toFloat() }
        }
        scriptBox.addView(compareView, lp(top = 8))
        scCard.addView(scriptBox)
        topCol.addView(scCard, lp(top = 12))

        root.addView(topScroll, lp(MATCH_PARENT, 0, 0.42f))

        // 받아쓰기 기록
        val logCard = card()
        val logHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        logHead.addView(label("받아쓰기 기록", 12f, cMuted, true), lp(0, WRAP_CONTENT, 1f))
        statsView = label("", 12f)
        logHead.addView(statsView)
        logCard.addView(logHead)
        logScroll = ScrollView(this)
        val logCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        linesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logCol.addView(linesBox)
        interimView = label("", 17f, cMuted)
        logCol.addView(interimView, lp(top = 6))
        logScroll.addView(logCol)
        logCard.addView(logScroll, lp(MATCH_PARENT, 0, 1f, top = 6))
        root.addView(logCard, lp(MATCH_PARENT, 0, 0.58f, top = 12))

        // 아래 버튼
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(button("기록 공유·저장") { share() }, lp(0, dp(48), 1f))
        btnClear = button("기록 지우기") { clearLog() }
        bottom.addView(btnClear, lp(0, dp(48), 1f).apply { leftMargin = dp(8) })
        root.addView(bottom, lp(top = 10))

        return root
    }

    private fun toggleScript(it: Button) {
        val show = scriptBox.visibility != View.VISIBLE
        scriptBox.visibility = if (show) View.VISIBLE else View.GONE
        it.text = if (show) "접기" else "펼치기"
    }

    // ---------------- 모델 ----------------

    private fun neededModels(): List<ModelSpec> {
        val list = mutableListOf(Models.VAD, Models.STREAM_KO)
        when (FinalModel.values()[spFinal.selectedItemPosition]) {
            FinalModel.SENSE_VOICE -> list += Models.SENSE_VOICE
            FinalModel.OFFLINE_KO -> list += Models.OFFLINE_KO
            FinalModel.NONE -> {}
        }
        return list
    }

    private fun refreshModels() {
        if (!::spFinal.isInitialized) return
        val sb = StringBuilder()
        neededModels().forEach { m ->
            sb.append(if (models.isReady(m)) "✓ " else "· ").append(m.label)
            sb.append(if (models.isReady(m)) "  (${models.sizeMb(m)}MB)" else "  — 아직 없음").append('\n')
        }
        modelStatus.text = sb.toString().trim()
        val missing = neededModels().count { !models.isReady(it) }
        btnDownload.visibility = if (missing > 0 || downloading) View.VISIBLE else View.GONE
        btnDownload.isEnabled = !downloading
        if (transcriber == null && !downloading) {
            statusView.text = if (missing > 0) "모델 ${missing}개를 먼저 내려받아 주세요" else "준비됨"
        }
    }

    private fun downloadNeeded() {
        if (downloading) return
        downloading = true
        refreshModels()
        val todo = neededModels().filter { !models.isReady(it) }
        Thread {
            try {
                todo.forEachIndexed { i, m ->
                    models.download(m) { got, total ->
                        ui.post {
                            statusView.text = "내려받는 중 ${i + 1}/${todo.size} · ${m.label} · ${got}MB" + if (total > 0) " / ${total}MB" else ""
                        }
                    }
                    ui.post { statusView.text = "압축 푸는 중 · ${m.label}"; refreshModels() }
                }
                ui.post { downloading = false; refreshModels(); statusView.text = "준비됨" }
            } catch (t: Throwable) {
                ui.post { downloading = false; refreshModels(); statusView.text = "내려받기 실패: ${t.message}" }
            }
        }.start()
    }

    // ---------------- 녹음 ----------------

    private fun onMainClick() {
        if (transcriber != null) { stopRec(); return }
        val missing = neededModels().filter { !models.isReady(it) }
        if (missing.isNotEmpty()) { statusView.text = "모델을 먼저 내려받아 주세요"; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        startRec()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startRec()
        else statusView.text = "마이크 권한이 없어 받아쓸 수 없습니다. 설정 → 애플리케이션 → 회의 노트 → 권한에서 허용해 주세요."
    }

    private fun startRec() {
        var mic = MicMode.values()[spMic.selectedItemPosition]
        if (mic == MicMode.UNPROCESSED) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != "true") {
                mic = MicMode.RECOGNITION
                statusView.text = "이 기기는 원음 방식을 지원하지 않아 음성인식용 방식으로 받습니다"
            }
        }
        val t = Transcriber(models, FinalModel.values()[spFinal.selectedItemPosition], mic, gains[spGain.selectedItemPosition], this)
        transcriber = t
        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        sessionOffset = SystemClock.elapsedRealtime() - startedAt
        btnMain.text = "받아쓰기 정지"
        (btnMain.background as GradientDrawable).setColor(cRec)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        t.start()
    }

    private fun stopRec() {
        val t = transcriber ?: return
        transcriber = null
        btnMain.isEnabled = false
        btnMain.text = "정리하는 중…"
        Thread {
            t.stop()
            ui.post {
                btnMain.isEnabled = true
                btnMain.text = "받아쓰기 다시 시작"
                (btnMain.background as GradientDrawable).setColor(cAccent)
                levelBar.progress = 0
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                refreshModels()
            }
        }.start()
    }

    override fun onDestroy() {
        transcriber?.stop()
        transcriber = null
        super.onDestroy()
    }

    // ---------------- 인식 결과 ----------------

    override fun onStatus(text: String) { ui.post { if (transcriber != null || text == "멈춤") statusView.text = text } }

    override fun onInterim(text: String) {
        ui.post {
            interimView.text = text
            scrollIfNear()
        }
    }

    override fun onFinal(text: String, startMs: Long, endMs: Long, latencyMs: Long) {
        ui.post {
            val line = Line(startMs + offsetMs(), text, latencyMs)
            lines += line
            if (latencyMs > 0) latencies += latencyMs
            linesBox.addView(lineView(line), lp(top = 6))
            updateStats()
            scrollIfNear()
        }
    }

    override fun onLevel(level: Float) { ui.post { levelBar.progress = (level * 100).toInt() } }

    override fun onError(message: String) {
        ui.post {
            statusView.text = "오류: $message"
            if (transcriber != null) stopRec()
        }
    }

    /** 정지 후 다시 시작하면 녹음 시간이 0부터 다시 세어지므로, 기록 시간에 앞 녹음분을 더함 */
    private var sessionOffset = 0L
    private fun offsetMs(): Long = sessionOffset

    private fun lineView(l: Line) = TextView(this).apply {
        val sb = SpannableStringBuilder()
        val t = fmt(l.startMs)
        sb.append(t).append("   ")
        sb.setSpan(ForegroundColorSpan(cMuted), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.75f), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(l.text)
        textSize = 17f; setTextColor(cInk); setLineSpacing(0f, 1.25f)
        text = sb
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun updateStats() {
        val avg = if (latencies.isEmpty()) "–" else String.format(Locale.KOREA, "%.1f초", latencies.average() / 1000.0)
        statsView.text = "문장 ${lines.size} · 확정까지 평균 $avg"
    }

    private fun scrollIfNear() {
        val child = logScroll.getChildAt(0) ?: return
        val near = child.height - (logScroll.scrollY + logScroll.height) < dp(160)
        if (near) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.KOREA, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private val clockTick = object : Runnable {
        override fun run() {
            if (startedAt > 0 && transcriber != null) clockView.text = fmt(SystemClock.elapsedRealtime() - startedAt)
            ui.postDelayed(this, 500)
        }
    }

    // ---------------- 기록 다루기 ----------------

    private fun transcriptText(): String = lines.joinToString("\n") { "[${fmt(it.startMs)}] ${it.text}" }

    private fun share() {
        if (lines.isEmpty()) { statusView.text = "아직 공유할 기록이 없습니다"; return }
        val head = "회의 노트 기록 · 문장 ${lines.size}개\n\n"
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "회의 노트 기록")
            putExtra(Intent.EXTRA_TEXT, head + transcriptText())
        }
        startActivity(Intent.createChooser(i, "기록 공유·저장"))
    }

    private fun clearLog() {
        if (!clearArmed) {
            clearArmed = true
            btnClear.text = "한 번 더 누르면 지웁니다"
            btnClear.setTextColor(cRec)
            ui.postDelayed({ clearArmed = false; btnClear.text = "기록 지우기"; btnClear.setTextColor(cInk) }, 3000)
            return
        }
        clearArmed = false
        btnClear.text = "기록 지우기"; btnClear.setTextColor(cInk)
        lines.clear(); latencies.clear(); linesBox.removeAllViews(); interimView.text = ""
        compareView.visibility = View.GONE
        if (transcriber == null) { startedAt = 0L; clockView.text = "00:00:00" }
        updateStats()
    }

    // ---------------- 원고 비교 ----------------

    private fun plain(s: String) = s.replace(Regex("[\\s.,!?·~…\"'“”‘’()\\-]"), "")

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun compare() {
        val ref = plain(script.joinToString("") { it.second })
        val hyp = plain(lines.joinToString("") { it.text })
        compareView.visibility = View.VISIBLE
        if (hyp.isEmpty()) { compareView.text = "아직 받아쓴 기록이 없습니다. 원고를 읽은 뒤 눌러 주세요."; return }
        val d = editDistance(ref, hyp)
        val acc = maxOf(0.0, 1.0 - d.toDouble() / ref.length)
        val terms = listOf("EAMS", "주간보고", "기업은행", "화면설계서", "깃허브", "노션", "단위테스트", "요구사항", "전진영", "모두잇", "제안서", "클로드", "구글드라이브", "아카이브", "심지훈", "사티스")
        val all = plain(lines.joinToString("") { it.text })
        val hit = terms.count { all.contains(it) }
        compareView.text = String.format(
            Locale.KOREA,
            "글자 정확도 %.1f%%\n원고 %d자 · 받아씀 %d자 · 다르게 적힌 글자 %d자\n원고 속 용어 %d/%d개 잡힘\n대략 95%% 이상이면 회의록으로 쓸 만하다고 봅니다.",
            acc * 100, ref.length, hyp.length, d, hit, terms.size,
        )
    }
}
