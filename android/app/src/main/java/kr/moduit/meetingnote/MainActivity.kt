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
import android.text.style.BackgroundColorSpan
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
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
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
    private val cDoubt = Color.parseColor("#FFF1A8")

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var models: ModelManager
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val recDir by lazy { File(filesDir, "recordings").apply { mkdirs() } }

    private lateinit var btnMain: Button
    private lateinit var statusView: TextView
    private lateinit var clockView: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var modelStatus: TextView
    private lateinit var btnDownload: Button
    private lateinit var spMode: Spinner
    private lateinit var spWhisper: Spinner
    private lateinit var spDenoise: Spinner
    private lateinit var spMic: Spinner
    private lateinit var spGain: Spinner
    private lateinit var cbRecord: CheckBox
    private lateinit var spRecs: Spinner
    private lateinit var btnRerun: Button
    private lateinit var btnRecDelete: Button
    private lateinit var btnBatch: Button
    private lateinit var btnAllModels: Button
    private var batch: BatchTester? = null
    private lateinit var linesBox: LinearLayout
    private lateinit var interimView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var scriptBox: LinearLayout
    private lateinit var compareView: TextView
    private lateinit var btnClear: Button
    private lateinit var statsView: TextView

    private var transcriber: Transcriber? = null
    private var rerunning = false
    private var startedAt = 0L
    private var sessionOffset = 0L
    private var downloading = false
    private var clearArmed = false
    private var recDeleteArmed = false
    private var recFiles = listOf<File>()

    private val lines = mutableListOf<FinalResult>()
    private val lineOffsets = mutableListOf<Long>()
    private val latencies = mutableListOf<Long>()

    private val gains = listOf(1f, 2f, 3f, 4f, 6f, 8f)
    private val whispers = listOf(Models.WHISPER_SMALL, Models.WHISPER_TURBO)

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
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setContentView(buildUi())
        refreshModels()
        refreshRecordings()
        ui.post(clockTick)
    }

    // ---------------- 화면 구성 도우미 ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { setColor(cPaper); cornerRadius = dp(14).toFloat(); setStroke(dp(1), cLine) }
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

    // ---------------- 화면 ----------------

    private fun buildUi(): View {
        val wide = resources.configuration.screenWidthDp >= 720

        // ===== 오른쪽(결과) 영역 =====
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

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
        right.addView(head)

        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        right.addView(levelBar, lp(top = 6).apply { height = dp(6) })

        // 결과 (원고 비교·일괄 비교)
        compareView = label("", 14f, cInk).apply {
            visibility = View.GONE; setPadding(dp(14), dp(10), dp(14), dp(10))
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply { setColor(cAccentSoft); cornerRadius = dp(12).toFloat() }
        }
        right.addView(compareView, lp(top = 10))

        val logCard = card()
        val logHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        logHead.addView(label("받아쓰기 기록 · 문장을 누르면 모델별 결과", 12f, cMuted, true), lp(0, WRAP_CONTENT, 1f))
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
        right.addView(logCard, lp(MATCH_PARENT, 0, 1f, top = 10))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(button("기록 공유·저장") { share() }, lp(0, dp(48), 1f))
        btnClear = button("기록 지우기") { clearLog() }
        bottom.addView(btnClear, lp(0, dp(48), 1f).apply { leftMargin = dp(8) })
        right.addView(bottom, lp(top = 10))

        // ===== 왼쪽(설정) 영역 =====
        val leftScroll = ScrollView(this)
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        leftScroll.addView(left)

        val setCard = card()
        setCard.addView(label("설정 (다음 시작부터 적용)", 12f, cMuted, true))
        setCard.addView(label("확정 인식 방식", 13f), lp(top = 6))
        spMode = spinner(FinalMode.values().map { it.label }, "mode2", 0) { refreshModels() }
        setCard.addView(spMode)
        setCard.addView(label("위스퍼 크기", 13f), lp(top = 6))
        spWhisper = spinner(listOf("작은 것 (빠름)", "큰 것 (정확, 느림)"), "whisper", 0) { refreshModels() }
        setCard.addView(spWhisper)
        setCard.addView(label("잡음 제거", 13f), lp(top = 6))
        spDenoise = spinner(listOf("끔", "켬 (확정 인식 전에 잡음을 걷어 냄)"), "denoise", 0) { refreshModels() }
        setCard.addView(spDenoise)
        setCard.addView(label("마이크 방식", 13f), lp(top = 6))
        spMic = spinner(MicMode.values().map { it.label }, "mic", 0) {}
        setCard.addView(spMic)
        setCard.addView(label("소리 키우기", 13f), lp(top = 6))
        spGain = spinner(gains.map { "${it.toInt()}배" }, "gain2", 4) {}
        setCard.addView(spGain)
        setCard.addView(label("위 막대가 말할 때 절반쯤 차면 적당합니다.", 12f), lp(top = 4))

        val modelCard = card()
        modelCard.addView(label("음성 모델 (지금 설정에 필요한 것)", 12f, cMuted, true))
        modelStatus = label("", 14f, cInk)
        modelCard.addView(modelStatus, lp(top = 4))
        btnDownload = button("필요한 모델 내려받기 (와이파이 권장)") { downloadNeeded(neededModels()) }
        modelCard.addView(btnDownload, lp(top = 8).apply { height = dp(44) })

        val recCard = card()
        recCard.addView(label("녹음으로 비교하기", 12f, cMuted, true))
        cbRecord = CheckBox(this).apply {
            text = "받아쓰는 동안 녹음 파일도 남기기"; setTextColor(cInk); textSize = 14f
            isChecked = prefs.getBoolean("record", true)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean("record", v).apply() }
        }
        recCard.addView(cbRecord, lp(top = 4))
        spRecs = Spinner(this)
        recCard.addView(spRecs, lp(top = 6))
        btnBatch = button("이 녹음으로 모든 방식 비교", true) { runBatch() }
        recCard.addView(btnBatch, lp(top = 6).apply { height = dp(48) })
        recCard.addView(label("내려받은 모든 모델로 각각 인식하고, 잡음 제거 켬·끔과 세 모델 합치기까지 한 번에 비교합니다. 결과는 오른쪽 위에 표로 나옵니다.", 12f), lp(top = 4))
        btnAllModels = button("비교용 모델 모두 내려받기 (약 1.9GB)") { downloadNeeded(allCompareModels()) }
        recCard.addView(btnAllModels, lp(top = 6).apply { height = dp(44) })
        val recBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRerun = button("지금 설정으로 다시 인식") { rerun() }
        recBtns.addView(btnRerun, lp(0, dp(44), 1f))
        btnRecDelete = button("녹음 지우기") { deleteRecording() }
        recBtns.addView(btnRecDelete, lp(0, dp(44), 1f).apply { leftMargin = dp(8) })
        recCard.addView(recBtns, lp(top = 6))

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
        scriptBox.addView(button("받아쓴 기록을 원고와 비교하기") { compare() }, lp(top = 10).apply { height = dp(44) })
        scCard.addView(scriptBox)

        left.addView(scCard)
        left.addView(recCard, lp(top = 12))
        left.addView(setCard, lp(top = 12))
        left.addView(modelCard, lp(top = 12))

        // ===== 배치 =====
        val root = LinearLayout(this).apply {
            setBackgroundColor(cBg)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        if (wide) {
            root.orientation = LinearLayout.HORIZONTAL
            root.addView(leftScroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.38f))
            root.addView(right, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.62f).apply { leftMargin = dp(16) })
        } else {
            root.orientation = LinearLayout.VERTICAL
            root.addView(right, lp(MATCH_PARENT, 0, 0.6f))
            root.addView(leftScroll, lp(MATCH_PARENT, 0, 0.4f, top = 12))
        }
        return root
    }

    private fun toggleScript(b: Button) {
        val show = scriptBox.visibility != View.VISIBLE
        scriptBox.visibility = if (show) View.VISIBLE else View.GONE
        b.text = if (show) "접기" else "펼치기"
    }

    // ---------------- 설정·모델 ----------------

    private fun options(): Options {
        var mic = MicMode.values()[spMic.selectedItemPosition]
        if (mic == MicMode.UNPROCESSED) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != "true") mic = MicMode.RECOGNITION
        }
        return Options(
            mode = FinalMode.values()[spMode.selectedItemPosition],
            whisper = whispers[spWhisper.selectedItemPosition],
            denoise = spDenoise.selectedItemPosition == 1,
            mic = mic,
            gain = gains[spGain.selectedItemPosition],
        )
    }

    private fun neededModels(): List<ModelSpec> = Engines.needed(options())

    private fun refreshModels() {
        if (!::spMic.isInitialized || !::modelStatus.isInitialized || !::btnAllModels.isInitialized) return
        val sb = StringBuilder()
        neededModels().forEach { m ->
            val ready = models.isReady(m)
            sb.append(if (ready) "✓ " else "· ").append(m.label)
            sb.append(if (ready) "  (${models.sizeMb(m)}MB)" else "  — 아직 없음").append('\n')
        }
        modelStatus.text = sb.toString().trim()
        val missing = neededModels().count { !models.isReady(it) }
        btnDownload.visibility = if (missing > 0 || downloading) View.VISIBLE else View.GONE
        btnDownload.isEnabled = !downloading
        val allMissing = allCompareModels().count { !models.isReady(it) }
        btnAllModels.visibility = if (allMissing > 0) View.VISIBLE else View.GONE
        btnAllModels.isEnabled = !downloading
        if (transcriber == null && !downloading && batch == null) {
            statusView.text = if (missing > 0) "모델 ${missing}개를 먼저 내려받아 주세요" else "준비됨"
        }
    }

    private fun allCompareModels() = listOf(Models.VAD, Models.SENSE_VOICE, Models.OFFLINE_KO, Models.WHISPER_SMALL, Models.WHISPER_TURBO, Models.DENOISE)

    private fun downloadNeeded(list: List<ModelSpec>) {
        if (downloading) return
        val todo = list.filter { !models.isReady(it) }
        if (todo.isEmpty()) { statusView.text = "이미 모두 내려받았습니다"; return }
        downloading = true
        refreshModels()
        Thread {
            try {
                todo.forEachIndexed { i, m ->
                    models.download(m) { got, total ->
                        ui.post { statusView.text = "내려받는 중 ${i + 1}/${todo.size} · ${m.label} · ${got}MB" + if (total > 0) " / ${total}MB" else "" }
                    }
                    ui.post { refreshModels() }
                }
                ui.post { downloading = false; refreshModels(); statusView.text = "준비됨" }
            } catch (t: Throwable) {
                ui.post { downloading = false; refreshModels(); statusView.text = "내려받기 실패: ${t.message}" }
            }
        }.start()
    }

    // ---------------- 녹음 파일 ----------------

    private fun refreshRecordings() {
        recFiles = (recDir.listFiles { f -> f.name.endsWith(".wav") }?.toList() ?: emptyList()).sortedByDescending { it.lastModified() }
        val names = if (recFiles.isEmpty()) listOf("남긴 녹음이 없습니다") else recFiles.map {
            val sec = (it.length() - 44) / 2 / 16000
            "${it.nameWithoutExtension}  (${sec / 60}분 ${sec % 60}초)"
        }
        spRecs.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val idle = transcriber == null && batch == null
        btnRerun.isEnabled = recFiles.isNotEmpty() && idle
        btnRecDelete.isEnabled = recFiles.isNotEmpty() && idle
        btnBatch.isEnabled = recFiles.isNotEmpty() && idle
    }

    private fun selectedRecording(): File? = recFiles.getOrNull(spRecs.selectedItemPosition)

    private fun rerun() {
        if (transcriber != null || batch != null) return
        val f = selectedRecording() ?: return
        val missing = neededModels().filter { !models.isReady(it) && it != Models.STREAM_KO }
        if (missing.isNotEmpty()) { statusView.text = "모델을 먼저 내려받아 주세요"; return }
        clearLogNow()
        rerunning = true
        startedAt = 0L; sessionOffset = 0L
        val t = Transcriber(models, options(), this, inputWav = f)
        transcriber = t
        setBusyUi(true, "다시 인식 중…")
        t.start()
    }

    private fun deleteRecording() {
        val f = selectedRecording() ?: return
        if (!recDeleteArmed) {
            recDeleteArmed = true
            btnRecDelete.text = "한 번 더 누르면 지웁니다"; btnRecDelete.setTextColor(cRec)
            ui.postDelayed({ recDeleteArmed = false; btnRecDelete.text = "녹음 지우기"; btnRecDelete.setTextColor(cInk) }, 3000)
            return
        }
        recDeleteArmed = false
        btnRecDelete.text = "녹음 지우기"; btnRecDelete.setTextColor(cInk)
        f.delete()
        refreshRecordings()
    }

    // ---------------- 녹음 하나로 모든 방식 비교 ----------------

    private fun runBatch() {
        if (transcriber != null || batch != null) return
        val f = selectedRecording() ?: return
        if (!models.isReady(Models.VAD)) { statusView.text = "말소리 구간 감지 모델을 먼저 내려받아 주세요"; return }
        clearLogNow()
        compareView.visibility = View.GONE
        val b = BatchTester(models, f) { msg -> ui.post { statusView.text = msg } }
        batch = b
        setBusyUi(true, "비교하는 중…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val res = try { Result.success(b.run()) } catch (t: Throwable) { Result.failure(t) }
            ui.post {
                batch = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setBusyUi(false, null)
                res.onSuccess { showBatch(it, f.nameWithoutExtension) }
                res.onFailure { statusView.text = "비교 실패: ${it.message}" }
            }
        }.start()
    }

    private fun showBatch(r: BatchResult, name: String) {
        val ref = plain(script.joinToString("") { it.second })
        data class Row(val key: String, val acc: Double, val hit: Int, val speed: Double)
        val rows = r.outputs.map { (key, outs) ->
            val (acc, hit) = scoreOf(outs.joinToString(""), ref)
            Row(key, acc, hit, (r.decodeMs[key] ?: 0) / 1000.0 / r.audioSec)
        }.sortedByDescending { it.acc }
        val sb = StringBuilder()
        sb.append(String.format(Locale.KOREA, "녹음 비교 · %s · %.0f초 · 문장 %d개\n", name, r.audioSec, r.segStartMs.size))
        sb.append("정확도   용어    처리시간  방식\n")
        rows.forEach {
            sb.append(String.format(Locale.KOREA, "%5.1f%%  %2d/%d  %5.2f배  %s\n", it.acc, it.hit, terms.size, it.speed, it.key))
        }
        sb.append("처리시간: 녹음 길이 대비 (1배 미만이면 실시간보다 빠름)")
        compareView.text = sb.toString()
        compareView.visibility = View.VISIBLE

        // 가장 좋은 방식의 문장들을 기록에 보여 줌
        val best = rows.firstOrNull() ?: return
        val outs = r.outputs[best.key] ?: return
        val unsure = r.uncertain[best.key]
        val mem = r.members[best.key]
        outs.forEachIndexed { i, t ->
            if (t.isBlank()) return@forEachIndexed
            val fr = FinalResult(t, unsure?.getOrNull(i) ?: emptySet(), mem?.getOrNull(i) ?: emptyMap(), r.segStartMs[i], r.segStartMs[i], 0)
            lines += fr; lineOffsets += 0L
            linesBox.addView(lineView(fr, 0), lp(top = 6))
        }
        statsView.text = "가장 정확한 방식: ${best.key}"
        statusView.text = "비교 끝"
    }

    // ---------------- 받아쓰기 ----------------

    private fun onMainClick() {
        if (batch != null) return
        val t = transcriber
        if (t != null) { if (!rerunning) { t.stop(); btnMain.isEnabled = false; btnMain.text = "정리하는 중…" }; return }
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
        val opt = options()
        val recFile = if (cbRecord.isChecked) {
            File(recDir, "녹음_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date()) + ".wav")
        } else null
        rerunning = false
        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        sessionOffset = SystemClock.elapsedRealtime() - startedAt
        val t = Transcriber(models, opt, this, recordTo = recFile)
        transcriber = t
        setBusyUi(true, null)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        t.start()
    }

    private fun setBusyUi(busy: Boolean, rerunText: String?) {
        if (busy) {
            if (rerunText != null) {
                btnMain.text = rerunText; btnMain.isEnabled = false
            } else {
                btnMain.text = "받아쓰기 정지"
                (btnMain.background as GradientDrawable).setColor(cRec)
            }
            btnRerun.isEnabled = false; btnDownload.isEnabled = false
            btnBatch.isEnabled = false; btnAllModels.isEnabled = false; btnRecDelete.isEnabled = false
        } else {
            btnMain.isEnabled = true
            btnMain.text = if (lines.isEmpty()) "받아쓰기 시작" else "받아쓰기 다시 시작"
            (btnMain.background as GradientDrawable).setColor(cAccent)
            levelBar.progress = 0
            btnDownload.isEnabled = !downloading
            refreshRecordings()
            refreshModels()
        }
    }

    override fun onDestroy() {
        transcriber?.stop()
        transcriber = null
        super.onDestroy()
    }

    // ---------------- 인식 결과 ----------------

    override fun onStatus(text: String) { ui.post { statusView.text = text } }

    override fun onInterim(text: String) {
        ui.post { interimView.text = text; scrollIfNear() }
    }

    override fun onFinal(r: FinalResult) {
        ui.post {
            lines += r
            lineOffsets += if (rerunning) 0L else sessionOffset
            if (r.latencyMs > 0) latencies += r.latencyMs
            linesBox.addView(lineView(r, lineOffsets.last()), lp(top = 6))
            updateStats()
            scrollIfNear()
        }
    }

    override fun onLevel(level: Float) { ui.post { levelBar.progress = (level * 100).toInt() } }

    override fun onProgress(percent: Int) { ui.post { statusView.text = "녹음 다시 인식 중 $percent%"; levelBar.progress = percent } }

    override fun onError(message: String) {
        ui.post {
            statusView.text = "오류: $message"
            transcriber?.stop()
        }
    }

    override fun onFinished() {
        ui.post {
            val wasRerun = rerunning
            transcriber = null
            rerunning = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setBusyUi(false, null)
            if (!statusView.text.startsWith("오류")) statusView.text = if (wasRerun) "다시 인식 끝" else "멈춤"
        }
    }

    private fun lineView(r: FinalResult, offset: Long): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val main = TextView(this).apply {
            val sb = SpannableStringBuilder()
            val t = fmt(r.startMs + offset)
            sb.append(t).append("   ")
            sb.setSpan(ForegroundColorSpan(cMuted), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.75f), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val base = sb.length
            sb.append(r.text)
            r.uncertain.forEach { i ->
                if (i in r.text.indices) sb.setSpan(BackgroundColorSpan(cDoubt), base + i, base + i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = sb; textSize = 17f; setTextColor(cInk); setLineSpacing(0f, 1.25f)
        }
        col.addView(main)
        if (r.hyps.size > 1) {
            val detail = TextView(this).apply {
                val sb = SpannableStringBuilder()
                Engines.ORDER.forEach { name ->
                    val h = r.hyps[name] ?: return@forEach
                    val s = sb.length
                    sb.append(name).append("  ")
                    sb.setSpan(ForegroundColorSpan(cAccent), s, s + name.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append(h.ifBlank { "(결과 없음)" }).append('\n')
                }
                text = sb.trim(); textSize = 14f; setTextColor(cMuted); visibility = View.GONE
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply { setColor(cBg); cornerRadius = dp(8).toFloat() }
            }
            col.addView(detail, lp(top = 4))
            col.setOnClickListener { detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        return col
    }

    private fun updateStats() {
        val avg = if (latencies.isEmpty()) "–" else String.format(Locale.KOREA, "%.1f초", latencies.average() / 1000.0)
        statsView.text = "문장 ${lines.size} · 확정까지 평균 $avg"
    }

    private fun scrollIfNear() {
        val child = logScroll.getChildAt(0) ?: return
        val near = child.height - (logScroll.scrollY + logScroll.height) < dp(200)
        if (near) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.KOREA, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private val clockTick = object : Runnable {
        override fun run() {
            if (startedAt > 0 && transcriber != null && !rerunning) clockView.text = fmt(SystemClock.elapsedRealtime() - startedAt)
            ui.postDelayed(this, 500)
        }
    }

    // ---------------- 기록 다루기 ----------------

    private fun transcriptText(): String =
        lines.indices.joinToString("\n") { i -> "[${fmt(lines[i].startMs + lineOffsets[i])}] ${lines[i].text}" }

    private fun share() {
        if (lines.isEmpty()) { statusView.text = "아직 공유할 기록이 없습니다"; return }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "회의 노트 기록")
            putExtra(Intent.EXTRA_TEXT, "회의 노트 기록 · 문장 ${lines.size}개\n\n" + transcriptText())
        }
        startActivity(Intent.createChooser(i, "기록 공유·저장"))
    }

    private fun clearLog() {
        if (!clearArmed) {
            clearArmed = true
            btnClear.text = "한 번 더 누르면 지웁니다"; btnClear.setTextColor(cRec)
            ui.postDelayed({ clearArmed = false; btnClear.text = "기록 지우기"; btnClear.setTextColor(cInk) }, 3000)
            return
        }
        clearArmed = false
        btnClear.text = "기록 지우기"; btnClear.setTextColor(cInk)
        clearLogNow()
    }

    private fun clearLogNow() {
        lines.clear(); lineOffsets.clear(); latencies.clear(); linesBox.removeAllViews(); interimView.text = ""
        compareView.visibility = View.GONE
        if (transcriber == null) { startedAt = 0L; clockView.text = "00:00:00" }
        updateStats()
    }

    // ---------------- 원고 비교 ----------------

    private fun plain(s: String) = s.replace(Regex("[\\s.,!?·~…\"'“”‘’()\\-:;]"), "")

    private val terms = listOf("EAMS", "주간보고", "기업은행", "화면설계서", "깃허브", "노션", "단위테스트", "요구사항", "전진영", "모두잇", "제안서", "클로드", "구글드라이브", "아카이브", "심지훈", "사티스")

    /** (글자 정확도 %, 잡힌 용어 수) */
    private fun scoreOf(text: String, ref: String): Pair<Double, Int> {
        val h = plain(text)
        if (h.isEmpty()) return 0.0 to 0
        val d = Combiner.distance(ref, h)
        return maxOf(0.0, 1.0 - d.toDouble() / ref.length) * 100 to terms.count { h.contains(it) }
    }

    private fun compare() {
        val ref = plain(script.joinToString("") { it.second })
        compareView.visibility = View.VISIBLE
        if (lines.isEmpty()) { compareView.text = "아직 받아쓴 기록이 없습니다. 원고를 읽은 뒤 눌러 주세요."; return }
        val sb = StringBuilder("원고 비교\n정확도   용어    방식\n")
        fun row(name: String, text: String) {
            val (acc, hit) = scoreOf(text, ref)
            sb.append(String.format(Locale.KOREA, "%5.1f%%  %2d/%d  %s\n", acc, hit, terms.size, name))
        }
        row("최종 결과", lines.joinToString("") { it.text })
        Engines.ORDER.forEach { name ->
            if (lines.any { it.hyps.containsKey(name) }) row(name, lines.joinToString("") { it.hyps[name] ?: "" })
        }
        sb.append("대략 95% 이상이면 회의록으로 쓸 만합니다")
        compareView.text = sb.toString()
    }
}
