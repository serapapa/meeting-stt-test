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
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 일반 사용자 화면.
 *  첫 화면: 회의 종류 고르기(범위·참석자·지난 회의록) → 회의 화면(자료 목록·상세 + 작은 받아쓰기) → 회의 끝(기록 올리기)
 *  설정: 깃허브 연결, 아카이브 받기, 음성 모델, 용어사전, 시험 도구
 */
class MainActivity : Activity(), TranscriberListener {

    // ---- 색 (흰 바탕) ----
    private val cBg = Color.parseColor("#F7F8FA")
    private val cPaper = Color.WHITE
    private val cInk = Color.parseColor("#1D2430")
    private val cMuted = Color.parseColor("#6B7482")
    private val cLine = Color.parseColor("#E2E6EC")
    private val cAccent = Color.parseColor("#1F6FEB")
    private val cAccentSoft = Color.parseColor("#E8F0FE")
    private val cRec = Color.parseColor("#D93025")
    private val cNew = Color.parseColor("#FFF1A8")
    private val speakerColors = listOf("#1F6FEB", "#1E7A46", "#B26A00", "#8E44AD", "#C2185B", "#00838F").map { Color.parseColor(it) }

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private lateinit var models: ModelManager
    private lateinit var archive: ArchiveStore
    private val bg = Executors.newSingleThreadExecutor()
    private val searchPool = Executors.newSingleThreadExecutor()

    private val meetingModels = listOf(Models.VAD, Models.STREAM_KO, Models.SENSE_VOICE, Models.SPEAKER)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = ModelManager(this)
        archive = ArchiveStore(this)
        window.statusBarColor = cBg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        showHome()
        // 못 올린 기록이 있으면 조용히 다시 올려 본다
        if (archive.token.isNotBlank()) bg.execute { try { archive.flushPending() } catch (_: Throwable) {} }
    }

    override fun onBackPressed() {
        when (screen) {
            "meeting" -> {}                  // 회의 중에는 뒤로 가기로 나가지 않음
            "home" -> super.onBackPressed()
            else -> showHome()
        }
    }

    private var screen = "home"

    // ================= 화면 도우미 =================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val wide get() = resources.configuration.screenWidthDp >= 720

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(cPaper); cornerRadius = dp(14).toFloat(); setStroke(dp(1), cLine) }
    }

    private fun label(text: CharSequence, size: Float = 13f, color: Int = cMuted, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.2f)
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

    private fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, weight: Float = 0f, top: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun page(): LinearLayout = vbox().apply {
        setBackgroundColor(cBg)
        setPadding(dp(20), dp(18), dp(20), dp(14))
    }

    private fun editBox(value: String, hint: String, lines: Int = 1) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 15f; setTextColor(cInk)
        inputType = if (lines > 1) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_CLASS_TEXT
        minLines = lines
        background = GradientDrawable().apply { setColor(cBg); cornerRadius = dp(8).toFloat(); setStroke(dp(1), cLine) }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun missingModels() = meetingModels.filter { !models.isReady(it) }

    // ================= 첫 화면 =================

    private var types = mutableListOf<MeetingType>()
    private var selected: MeetingType? = null

    private fun showHome() {
        screen = "home"
        types = MeetingTypes.load(this)
        if (selected == null || types.none { it.id == selected?.id }) selected = types.firstOrNull()

        val root = page()
        val head = hbox()
        head.addView(vbox().apply {
            addView(label("회의 노트", 24f, cInk, true))
            addView(label(homeStatus(), 13f))
        }, lp(0, WRAP_CONTENT, 1f))
        head.addView(button("설정") { showSettings() }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)))
        root.addView(head)

        val body = if (wide) hbox().apply { gravity = Gravity.TOP } else vbox()
        // 회의 종류 목록
        val listCol = vbox()
        listCol.addView(label("회의 종류", 12f, cMuted, true))
        val listScroll = ScrollView(this)
        val list = vbox()
        types.forEach { t ->
            val c = card().apply {
                val on = t.id == selected?.id
                background = GradientDrawable().apply {
                    setColor(if (on) cAccentSoft else cPaper); cornerRadius = dp(14).toFloat(); setStroke(dp(if (on) 2 else 1), if (on) cAccent else cLine)
                }
                addView(label(t.name, 17f, cInk, true))
                addView(label(
                    (if (t.participants.isEmpty()) "참석자 미정" else "참석자 " + t.participants.joinToString(", ")) +
                        "\n자료 범위 " + (if (t.scopes.isEmpty()) "아카이브 전체" else t.scopes.joinToString(", ")), 13f), lp(top = 4))
                setOnClickListener { selected = t; showHome() }
            }
            list.addView(c, lp(top = 8))
        }
        list.addView(button("+ 회의 종류 추가") { showEditor(null) }, lp(top = 10).apply { height = dp(48) })
        listScroll.addView(list)
        listCol.addView(listScroll, lp(MATCH_PARENT, 0, 1f, top = 4))

        // 선택한 회의 종류 상세
        val detail = vbox()
        val sel = selected
        if (sel == null) {
            detail.addView(card().apply {
                addView(label("회의 종류를 먼저 만들어 주세요", 17f, cInk, true))
                addView(label("예: 'EA시스템 정기 회의' — 찾아볼 아카이브 폴더와 참석자를 정해 두면, 회의를 시작할 때 자동으로 세팅됩니다.", 14f), lp(top = 6))
            })
        } else {
            val dc = card()
            dc.addView(label(sel.name, 20f, cInk, true))
            dc.addView(label("참석자  " + (if (sel.participants.isEmpty()) "미정" else sel.participants.joinToString(", ")), 14f, cInk), lp(top = 6))
            dc.addView(label("자료 범위  " + (if (sel.scopes.isEmpty()) "아카이브 전체" else sel.scopes.joinToString(", ")), 14f, cInk), lp(top = 2))
            dc.addView(label("지난 회의", 12f, cMuted, true), lp(top = 14))
            val past = if (archive.hasArchive) PastMeetings.latest(archive.root, sel.name) else null
            dc.addView(label(
                when {
                    !archive.hasArchive -> "아카이브를 아직 받지 않았습니다. 설정에서 '아카이브 받기'를 눌러 주세요."
                    past == null -> "이 회의 종류로 남긴 기록이 아직 없습니다."
                    past.isMinutes -> "${past.date} 회의록\n\n${past.text}"
                    else -> "${past.date} 기록 (회의록은 다음 날 새벽에 정리됩니다)\n\n${past.text}"
                }, 15f, cInk), lp(top = 4))
            val btns = hbox()
            btns.addView(button("회의 시작", true) { startMeeting(sel) }, lp(0, dp(52), 1f))
            btns.addView(button("편집") { showEditor(sel) }, lp(0, dp(52), 0.4f).apply { leftMargin = dp(8) })
            dc.addView(btns, lp(top = 16))
            val miss = missingModels()
            if (miss.isNotEmpty()) dc.addView(label("음성 모델 ${miss.size}개가 아직 없습니다. 설정에서 내려받아 주세요.", 13f, cRec), lp(top = 8))
            detail.addView(ScrollView(this).apply { addView(dc) })
        }

        if (wide) {
            body.addView(listCol, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.4f))
            body.addView(detail, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.6f).apply { leftMargin = dp(16) })
        } else {
            body.addView(detail, lp())
            body.addView(listCol, lp(MATCH_PARENT, 0, 1f, top = 12))
        }
        root.addView(body, lp(MATCH_PARENT, 0, 1f, top = 14))
        setContentView(root)
    }

    private fun homeStatus(): String {
        val a = if (archive.lastSync > 0) "아카이브 " + SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(archive.lastSync)) + " 받음" else "아카이브 아직 안 받음"
        val m = missingModels().size
        val p = archive.pendingDir.listFiles()?.size ?: 0
        return a + (if (m > 0) " · 음성 모델 ${m}개 필요" else " · 음성 모델 준비됨") + (if (p > 0) " · 못 올린 기록 ${p}건" else "")
    }

    // ================= 회의 종류 편집 =================

    private fun showEditor(t: MeetingType?) {
        screen = "editor"
        val root = page()
        root.addView(label(if (t == null) "회의 종류 추가" else "회의 종류 편집", 22f, cInk, true))
        val sc = ScrollView(this)
        val col = vbox()
        col.addView(label("이름", 12f, cMuted, true), lp(top = 12))
        val etName = editBox(t?.name ?: "", "예: EA시스템 정기 회의")
        col.addView(etName, lp(top = 4))
        col.addView(label("참석자 (쉼표로 구분)", 12f, cMuted, true), lp(top = 12))
        val etPeople = editBox(t?.participants?.joinToString(", ") ?: "라이언", "예: 라이언, 전진영")
        col.addView(etPeople, lp(top = 4))
        col.addView(label("자료 범위 — 회의 중에 찾아볼 아카이브 폴더 (아무것도 고르지 않으면 전체)", 12f, cMuted, true), lp(top = 14))

        val checks = mutableListOf<Pair<String, CheckBox>>()
        val folders = archive.folders()
        if (folders.isEmpty()) col.addView(label("아카이브를 아직 받지 않아 폴더를 보여 줄 수 없습니다. 설정에서 '아카이브 받기'를 먼저 해 주세요.", 14f, cRec), lp(top = 6))
        val chosen = t?.scopes?.toSet() ?: emptySet()
        folders.forEach { (top, subs) ->
            val topBox = CheckBox(this).apply { text = top; textSize = 16f; setTextColor(cInk); isChecked = top in chosen; typeface = Typeface.DEFAULT_BOLD }
            checks += top to topBox
            col.addView(topBox, lp(top = 6))
            val subCol = vbox().apply { setPadding(dp(28), 0, 0, 0) }
            subs.forEach { s ->
                val cb = CheckBox(this).apply { text = s.substringAfter('/'); textSize = 14f; setTextColor(cInk); isChecked = s in chosen }
                checks += s to cb
                subCol.addView(cb)
            }
            topBox.setOnCheckedChangeListener { _, v -> subCol.alpha = if (v) 0.4f else 1f }
            subCol.alpha = if (topBox.isChecked) 0.4f else 1f
            col.addView(subCol)
        }
        sc.addView(col)
        root.addView(sc, lp(MATCH_PARENT, 0, 1f, top = 4))

        val btns = hbox()
        btns.addView(button("저장", true) {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) { etName.error = "이름을 넣어 주세요"; return@button }
            val picked = checks.filter { it.second.isChecked }.map { it.first }
            // 상위 폴더를 골랐으면 그 아래 폴더는 따로 적지 않음
            val scopes = picked.filter { p -> picked.none { q -> q != p && p.startsWith("$q/") } }
            val people = etPeople.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val nt = MeetingType(t?.id ?: UUID.randomUUID().toString(), name, scopes, people)
            val list = MeetingTypes.load(this)
            val i = list.indexOfFirst { it.id == nt.id }
            if (i >= 0) list[i] = nt else list += nt
            MeetingTypes.save(this, list)
            selected = nt
            showHome()
        }, lp(0, dp(50), 1f))
        if (t != null) {
            var armed = false
            lateinit var del: Button
            del = button("삭제") {
                if (!armed) { armed = true; del.text = "한 번 더 누르면 삭제"; del.setTextColor(cRec); return@button }
                MeetingTypes.save(this, MeetingTypes.load(this).filter { it.id != t.id })
                selected = null; showHome()
            }
            btns.addView(del, lp(0, dp(50), 0.6f).apply { leftMargin = dp(8) })
        }
        btns.addView(button("취소") { showHome() }, lp(0, dp(50), 0.6f).apply { leftMargin = dp(8) })
        root.addView(btns, lp(top = 10))
        setContentView(root)
    }

    // ================= 회의 화면 =================

    private var transcriber: Transcriber? = null
    private var meetingType: MeetingType? = null
    private var search: ArchiveSearch? = null
    private var startedAt = 0L
    private var startedDate = Date()
    private var recFile: File? = null
    private var fixer = TermFixer(TermFixer.DEFAULT_TERMS, TermFixer.DEFAULT_PAIRS, TermFixer.DEFAULT_KEEP)

    private val lines = mutableListOf<FinalResult>()
    private class Item(val hit: Hit, val foundAt: Long, var mentions: Int, var isNew: Boolean)
    private val items = mutableListOf<Item>()
    private var openItem: Item? = null
    private var unseen = 0

    private lateinit var mTitle: TextView
    private lateinit var mClock: TextView
    private lateinit var mStatus: TextView
    private lateinit var mStop: Button
    private lateinit var mLevel: ProgressBar
    private lateinit var listBox: LinearLayout
    private lateinit var listScroll: ScrollView
    private lateinit var detailBox: LinearLayout
    private lateinit var detailScroll: ScrollView
    private lateinit var newBanner: TextView
    private lateinit var tBox: LinearLayout
    private lateinit var tScroll: ScrollView
    private lateinit var tInterim: TextView

    private fun startMeeting(t: MeetingType) {
        if (missingModels().isNotEmpty()) { showSettings(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            meetingType = t
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        meetingType = t
        lines.clear(); items.clear(); openItem = null; unseen = 0
        fixer = TermFixer(prefs.getString("dictTerms", TermFixer.DEFAULT_TERMS)!!, prefs.getString("dictPairs", TermFixer.DEFAULT_PAIRS)!!, prefs.getString("dictKeep", TermFixer.DEFAULT_KEEP)!!)
        buildMeetingUi(t)

        // 자료 찾기 준비 (뒤에서)
        search = null
        if (archive.hasArchive) {
            mStatus.text = "말씀하셔도 됩니다 · 자료 준비 중…"
            searchPool.execute {
                val s = try { ArchiveSearch(archive.root, t.scopes, fixer.termList) } catch (_: Throwable) { null }
                ui.post { search = s; if (transcriber != null) mStatus.text = "받아쓰는 중 · 자료 ${s?.chunks?.size ?: 0}개 구간에서 찾음" }
            }
        }

        var mic = MicMode.values()[prefs.getInt("mic", 0).coerceIn(0, MicMode.values().size - 1)]
        if (mic == MicMode.UNPROCESSED) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != "true") mic = MicMode.RECOGNITION
        }
        val gains = listOf(1f, 2f, 3f, 4f, 6f, 8f)
        val opt = Options(
            mode = FinalMode.SENSE_VOICE, whisper = Models.WHISPER_SMALL, denoise = false, mic = mic,
            gain = gains[prefs.getInt("gain2", 4).coerceIn(0, gains.size - 1)],
            speakers = true, speakerThreshold = 0.5f,
        )
        startedDate = Date()
        val recDir = File(filesDir, "recordings").apply { mkdirs() }
        recFile = File(recDir, "회의_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(startedDate) + ".wav")
        startedAt = SystemClock.elapsedRealtime()
        val tr = Transcriber(models, opt, this, recordTo = recFile)
        transcriber = tr
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tr.start()
        ui.post(clockTick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val t = meetingType
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && t != null) startMeeting(t)
    }

    private fun buildMeetingUi(t: MeetingType) {
        screen = "meeting"
        val root = page()

        val top = hbox()
        mTitle = label(t.name, 20f, cInk, true)
        val tc = vbox()
        tc.addView(mTitle)
        mStatus = label("말씀하셔도 됩니다 · 모델 불러오는 중…", 13f)
        tc.addView(mStatus)
        top.addView(tc, lp(0, WRAP_CONTENT, 1f))
        mClock = label("00:00:00", 18f, cInk).apply { typeface = Typeface.MONOSPACE; setPadding(0, 0, dp(14), 0) }
        top.addView(mClock)
        mStop = button("회의 끝내기", true) { stopMeeting() }
        (mStop.background as GradientDrawable).setColor(cRec)
        top.addView(mStop, LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)))
        root.addView(top)
        mLevel = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(mLevel, lp(top = 6).apply { height = dp(5) })

        // 자료 영역 (위쪽 크게)
        val area = if (wide) hbox().apply { gravity = Gravity.TOP } else vbox()
        val listCard = card()
        listCard.addView(label("관련 자료 · 대화에 맞춰 계속 추가됩니다", 12f, cMuted, true))
        listScroll = ScrollView(this)
        listBox = vbox()
        listBox.addView(label("대화가 시작되면 관련 자료가 여기에 쌓입니다.", 14f), lp(top = 8))
        listScroll.addView(listBox)
        listCard.addView(listScroll, lp(MATCH_PARENT, 0, 1f, top = 6))

        val detailCard = card()
        newBanner = label("", 14f, cInk, true).apply {
            visibility = View.GONE; setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(cNew); cornerRadius = dp(8).toFloat() }
            setOnClickListener { listScroll.smoothScrollTo(0, 0); unseen = 0; visibility = View.GONE }
        }
        detailCard.addView(newBanner)
        detailScroll = ScrollView(this)
        detailBox = vbox()
        detailBox.addView(label("목록에서 자료를 누르면 내용이 여기에 보입니다.", 14f), lp(top = 8))
        detailScroll.addView(detailBox)
        detailCard.addView(detailScroll, lp(MATCH_PARENT, 0, 1f, top = 4))

        if (wide) {
            area.addView(listCard, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.4f))
            area.addView(detailCard, LinearLayout.LayoutParams(0, MATCH_PARENT, 0.6f).apply { leftMargin = dp(14) })
        } else {
            area.addView(listCard, lp(MATCH_PARENT, 0, 0.45f))
            area.addView(detailCard, lp(MATCH_PARENT, 0, 0.55f, top = 10))
        }
        root.addView(area, lp(MATCH_PARENT, 0, 1f, top = 10))

        // 받아쓰기 (아래쪽 작게)
        val tCard = card().apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        tCard.addView(label("받아쓰기", 12f, cMuted, true))
        tScroll = ScrollView(this)
        val tCol = vbox()
        tBox = vbox()
        tCol.addView(tBox)
        tInterim = label("", 15f, cMuted)
        tCol.addView(tInterim)
        tScroll.addView(tCol)
        tCard.addView(tScroll, lp(MATCH_PARENT, dp(if (wide) 130 else 110)))
        root.addView(tCard, lp(top = 10))
        setContentView(root)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.KOREA, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private val clockTick = object : Runnable {
        override fun run() {
            if (transcriber != null && screen == "meeting") {
                mClock.text = fmt(SystemClock.elapsedRealtime() - startedAt)
                ui.postDelayed(this, 500)
            }
        }
    }

    private fun speakerName(n: Int): String {
        // 참석자가 정해져 있으면 순서대로 이름을 임시로 붙인다(회의 후 바로잡기 예정)
        return "화자$n"
    }

    private fun lineText(r: FinalResult): CharSequence {
        val sb = SpannableStringBuilder()
        val t = fmt(r.startMs)
        sb.append(t).append("  ")
        sb.setSpan(ForegroundColorSpan(cMuted), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.8f), 0, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (r.speaker > 0) {
            val nm = speakerName(r.speaker); val s0 = sb.length
            sb.append(nm).append("  ")
            sb.setSpan(ForegroundColorSpan(speakerColors[(r.speaker - 1) % speakerColors.size]), s0, s0 + nm.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), s0, s0 + nm.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append(fixer.plainFix(r.text))
        return sb
    }

    // ---- 받아쓰기 결과 ----
    override fun onStatus(text: String) {
        ui.post {
            if (screen != "meeting") return@post
            mStatus.text = when {
                text == "받아쓰는 중" && search != null -> "받아쓰는 중 · 자료 ${search?.chunks?.size}개 구간에서 찾음"
                text == "받아쓰는 중" && !archive.hasArchive -> "받아쓰는 중 · 아카이브를 받지 않아 자료는 찾지 않습니다"
                else -> text
            }
        }
    }

    override fun onInterim(text: String) { ui.post { if (screen == "meeting") { tInterim.text = text; tScroll.post { tScroll.fullScroll(View.FOCUS_DOWN) } } } }

    override fun onFinal(r: FinalResult) {
        ui.post {
            if (screen != "meeting") { lines += r; return@post }
            lines += r
            tBox.addView(label(lineText(r), 15f, cInk), lp(top = 2))
            tScroll.post { tScroll.fullScroll(View.FOCUS_DOWN) }
            findMaterials()
        }
    }

    override fun onLevel(level: Float) { ui.post { if (screen == "meeting") mLevel.progress = (level * 100).toInt() } }
    override fun onProgress(percent: Int) {}
    override fun onError(message: String) { ui.post { if (screen == "meeting") mStatus.text = "오류: $message" } }

    override fun onFinished() {
        ui.post {
            transcriber = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            finishMeeting()
        }
    }

    // ---- 자료 찾기 ----
    private var searching = false
    private fun findMaterials() {
        val s = search ?: return
        if (searching || lines.isEmpty()) return
        val lastMs = lines.last().startMs
        val recent = lines.filter { it.startMs >= lastMs - 60_000 }.joinToString(" ") { fixer.plainFix(it.text) }
        searching = true
        val at = lines.last().startMs
        searchPool.execute {
            val hits = try { s.search(recent, 5) } catch (_: Throwable) { emptyList() }
            ui.post {
                searching = false
                if (screen != "meeting") return@post
                var added = 0
                for (h in hits.reversed()) {
                    if (h.score < 18) continue
                    val ex = items.firstOrNull { it.hit.chunk.path == h.chunk.path }
                    if (ex != null) { ex.mentions++; continue }
                    items.add(0, Item(h, at, 1, true)); added++
                }
                if (added > 0) {
                    renderList()
                    if (openItem != null) { unseen += added; newBanner.text = "새 자료 ${unseen}건 — 눌러서 목록 맨 위 보기"; newBanner.visibility = View.VISIBLE }
                } else if (hits.isNotEmpty()) renderList()
            }
        }
    }

    private fun renderList() {
        listBox.removeAllViews()
        items.forEach { it2 ->
            val c = card().apply {
                setPadding(dp(14), dp(10), dp(14), dp(10))
                val on = it2 === openItem
                background = GradientDrawable().apply {
                    setColor(if (on) cAccentSoft else if (it2.isNew) Color.parseColor("#FFFBE6") else cPaper)
                    cornerRadius = dp(12).toFloat(); setStroke(dp(if (on) 2 else 1), if (on) cAccent else cLine)
                }
                val h = it2.hit
                val title = SpannableStringBuilder()
                if (it2.isNew) { title.append("새로 "); title.setSpan(ForegroundColorSpan(cRec), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                title.append(h.chunk.title)
                addView(label(title, 16f, cInk, true))
                if (h.chunk.heading.isNotEmpty()) addView(label("› " + h.chunk.heading, 13f, cInk), lp(top = 2))
                addView(label(h.chunk.path.substringBeforeLast('/').split('/').takeLast(2).joinToString(" › "), 12f), lp(top = 2))
                addView(label(fmt(it2.foundAt) + " · " + h.keywords.take(4).joinToString(" · ") + (if (it2.mentions > 1) " · 다시 언급 ${it2.mentions - 1}회" else ""), 12f, cAccent), lp(top = 2))
                setOnClickListener { openDetail(it2) }
            }
            listBox.addView(c, lp(top = 6))
        }
    }

    private fun openDetail(item: Item) {
        item.isNew = false
        openItem = item
        renderList()
        val h = item.hit
        detailBox.removeAllViews()
        detailBox.addView(label(h.chunk.title, 20f, cInk, true))
        detailBox.addView(label(h.chunk.path, 12f), lp(top = 2))
        // 같은 노트의 전체 내용 보여 주기(길면 앞부분)
        val full = archive.read(h.chunk.path)?.let { md ->
            if (md.startsWith("---")) md.substringAfter("\n---", md).trimStart('-', '\n') else md
        } ?: h.chunk.text
        val shown = if (full.length > 12000) full.take(12000) + "\n\n… (뒷부분 생략)" else full
        val sb = SpannableStringBuilder(shown)
        val low = shown.lowercase()
        h.keywords.forEach { k ->
            var p = low.indexOf(k)
            while (p >= 0) { sb.setSpan(BackgroundColorSpan(cNew), p, p + k.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); p = low.indexOf(k, p + k.length) }
        }
        detailBox.addView(label(sb, 15f, cInk), lp(top = 10))
        // 해당 구간으로 이동
        detailScroll.post {
            val idx = if (h.chunk.heading.isNotEmpty()) shown.indexOf(h.chunk.heading) else -1
            val tv = detailBox.getChildAt(2) as TextView
            val layout = tv.layout
            if (idx > 0 && layout != null) detailScroll.scrollTo(0, tv.top + layout.getLineTop(layout.getLineForOffset(idx)))
            else detailScroll.scrollTo(0, 0)
        }
    }

    // ---- 회의 끝 ----
    private fun stopMeeting() {
        val t = transcriber ?: return
        t.stop()
        mStop.isEnabled = false
        mStop.text = "정리하는 중…"
    }

    private fun finishMeeting() {
        screen = "done"
        val t = meetingType ?: return
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(startedDate)
        val timeStr = SimpleDateFormat("HHmm", Locale.KOREA).format(startedDate)
        val path = "${ArchiveStore.MEETING_DIR}/${dateStr}_${timeStr}_${t.name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()}.md"
        val md = buildRecord(t, dateStr)

        val root = page()
        root.addView(label("회의를 마쳤습니다", 22f, cInk, true))
        val status = label("기록을 아카이브에 올리는 중…", 15f, cInk)
        root.addView(status, lp(top = 6))
        val sc = ScrollView(this)
        sc.addView(label(md.substringAfter("## 받아쓰기").substringBefore("\n## ").trim().ifEmpty { "받아쓴 내용이 없습니다." }, 15f, cInk))
        root.addView(card().apply { addView(sc, lp(MATCH_PARENT, 0, 1f)) }, lp(MATCH_PARENT, 0, 1f, top = 12))
        val btns = hbox()
        btns.addView(button("기록 공유") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "${t.name} 회의 기록"); putExtra(Intent.EXTRA_TEXT, md)
            }, "기록 공유"))
        }, lp(0, dp(50), 1f))
        btns.addView(button("처음 화면으로", true) { showHome() }, lp(0, dp(50), 1f).apply { leftMargin = dp(8) })
        root.addView(btns, lp(top = 10))
        setContentView(root)

        if (lines.isEmpty()) { status.text = "받아쓴 내용이 없어 기록을 올리지 않았습니다."; return }
        bg.execute {
            val msg = try {
                archive.upload(path, md, "회의 노트 앱: ${t.name} 회의 기록 ($dateStr)")
                "아카이브에 올렸습니다 · $path\n회의록은 내일 새벽 클로드가 정리합니다."
            } catch (e: Throwable) {
                archive.queueUpload(path, md)
                "지금은 올리지 못했습니다(${e.message}). 기록은 태블릿에 보관했다가 다음에 앱을 열 때 다시 올립니다."
            }
            ui.post { status.text = msg }
        }
    }

    private fun buildRecord(t: MeetingType, dateStr: String): String {
        val durMin = ((lines.lastOrNull()?.endMs ?: 0) / 60000) + 1
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("제목: \"$dateStr ${t.name} 회의 기록\"\n")
        sb.append("날짜: $dateStr\n")
        sb.append("시작: ").append(SimpleDateFormat("HH:mm", Locale.KOREA).format(startedDate)).append('\n')
        sb.append("길이: 약 ${durMin}분\n")
        sb.append("회의종류: \"${t.name}\"\n")
        sb.append("참석자: [").append(t.participants.joinToString(", ")).append("]\n")
        sb.append("자료범위: [").append(t.scopes.joinToString(", ")).append("]\n")
        sb.append("종류: 원본기록\n")
        sb.append("출처: 회의노트 앱\n")
        sb.append("---\n")
        sb.append("# $dateStr ${t.name} 회의 기록\n\n")
        sb.append("> 자동 받아쓰기 원본. 화자 번호는 목소리로 자동 구분한 것이라 실제 사람과 다를 수 있음. 녹음 파일은 태블릿에만 있음.\n\n")
        sb.append("## 받아쓰기\n")
        lines.forEach { r ->
            sb.append("- [").append(fmt(r.startMs)).append("] ")
            if (r.speaker > 0) sb.append(speakerName(r.speaker)).append(": ")
            sb.append(fixer.plainFix(r.text)).append('\n')
        }
        // 용어 교정 집계 → 새벽 정리 작업이 용어사전 보강에 씀
        val fixes = mutableMapOf<String, Int>()
        lines.forEach { r -> fixer.fix(r.text).filter { it.orig != null }.forEach { fixes["${it.orig} → ${it.text}"] = (fixes["${it.orig} → ${it.text}"] ?: 0) + 1 } }
        sb.append("\n## 용어 교정 (들린 말 → 고친 말)\n")
        if (fixes.isEmpty()) sb.append("- 없음\n") else fixes.forEach { (k, v) -> sb.append("- ").append(k).append(" (").append(v).append("회)\n") }
        sb.append("\n## 회의 중 띄운 자료\n")
        if (items.isEmpty()) sb.append("- 없음\n") else items.reversed().forEach { i ->
            sb.append("- [").append(fmt(i.foundAt)).append("] ").append(i.hit.chunk.path)
            if (i.hit.chunk.heading.isNotEmpty()) sb.append(" › ").append(i.hit.chunk.heading)
            sb.append(" (").append(i.hit.keywords.take(4).joinToString(", ")).append(")\n")
        }
        return sb.toString()
    }

    // ================= 설정 =================

    private fun showSettings() {
        screen = "settings"
        val root = page()
        val head = hbox()
        head.addView(label("설정", 24f, cInk, true), lp(0, WRAP_CONTENT, 1f))
        head.addView(button("닫기") { showHome() }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)))
        root.addView(head)
        val status = label("", 14f, cInk)
        root.addView(status, lp(top = 4))

        val sc = ScrollView(this)
        val col = if (wide) hbox().apply { gravity = Gravity.TOP } else vbox()
        val left = vbox(); val right = vbox()

        // 깃허브·아카이브
        val gh = card()
        gh.addView(label("아카이브 연결 (ryan-archive)", 12f, cMuted, true))
        gh.addView(label("깃허브 열쇠(토큰) — ryan-archive에만 '내용 읽기·쓰기' 권한을 준 열쇠를 넣어 주세요. 이 태블릿에만 저장됩니다.", 13f), lp(top = 4))
        val etTok = editBox(archive.token, "github_pat_로 시작하는 열쇠").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        gh.addView(etTok, lp(top = 6))
        gh.addView(button("열쇠 저장") { archive.token = etTok.text.toString(); status.text = "열쇠를 저장했습니다" }, lp(top = 6).apply { height = dp(44) })
        val syncInfo = label(homeStatus(), 13f)
        gh.addView(syncInfo, lp(top = 10))
        gh.addView(button("아카이브 받기 (와이파이 권장, 약 40MB)", true) {
            archive.token = etTok.text.toString()
            status.text = "아카이브 받는 중…"
            bg.execute {
                val msg = try {
                    archive.sync { m -> ui.post { status.text = m } }
                    val dmsg = syncDictionary()
                    val sent = archive.flushPending()
                    "아카이브 받기 끝 · $dmsg" + (if (sent > 0) " · 못 올렸던 기록 ${sent}건 올림" else "")
                } catch (e: Throwable) { "아카이브 받기 실패: ${e.message}" }
                ui.post { status.text = msg; syncInfo.text = homeStatus() }
            }
        }, lp(top = 6).apply { height = dp(48) })
        gh.addView(label("회의 전에 한 번 받아 두면, 회의 중에는 인터넷 없이도 자료를 찾습니다. 받을 때 용어사전도 아카이브 것으로 맞춥니다.", 12f), lp(top = 4))
        left.addView(gh)

        // 음성 모델
        val mc = card()
        mc.addView(label("음성 모델", 12f, cMuted, true))
        val mText = label("", 14f, cInk)
        fun refreshM() { mText.text = meetingModels.joinToString("\n") { (if (models.isReady(it)) "✓ " else "· ") + it.label + if (models.isReady(it)) "" else " — 아직 없음" } }
        refreshM()
        mc.addView(mText, lp(top = 4))
        mc.addView(button("필요한 모델 내려받기 (와이파이 권장)") {
            val todo = meetingModels.filter { !models.isReady(it) }
            if (todo.isEmpty()) { status.text = "이미 모두 받았습니다"; return@button }
            bg.execute {
                try {
                    todo.forEachIndexed { i, m ->
                        models.download(m) { got, total -> ui.post { status.text = "내려받는 중 ${i + 1}/${todo.size} · ${m.label} · ${got}MB" + if (total > 0) " / ${total}MB" else "" } }
                    }
                    ui.post { status.text = "음성 모델 준비됨"; refreshM() }
                } catch (e: Throwable) { ui.post { status.text = "내려받기 실패: ${e.message}"; refreshM() } }
            }
        }, lp(top = 8).apply { height = dp(44) })
        left.addView(mc, lp(top = 12))

        // 시험 도구
        val tc = card()
        tc.addView(label("시험 도구 (개발용 — 나중에 뺄 예정)", 12f, cMuted, true))
        tc.addView(label("인식 모델 비교, 원고 정확도, 마이크 방식·소리 크기 설정이 있습니다. 소리 크기와 마이크 방식은 회의에도 그대로 적용됩니다.", 13f), lp(top = 4))
        tc.addView(button("시험 도구 열기") { startActivity(Intent(this, TestActivity::class.java)) }, lp(top = 8).apply { height = dp(44) })
        left.addView(tc, lp(top = 12))

        // 용어사전
        val dc = card()
        dc.addView(label("내 용어사전", 12f, cMuted, true))
        dc.addView(label("회의 기록과 아카이브를 바탕으로 매일 새벽 클로드가 보강합니다. 여기서 고친 내용은 '아카이브에 올리기'를 눌러야 반영됩니다.", 13f), lp(top = 4))
        dc.addView(label("용어 — 한 줄에 하나, 영어·줄임말은 '용어 = 읽는 소리, 읽는 소리'", 12f), lp(top = 8))
        val etT = editBox(prefs.getString("dictTerms", TermFixer.DEFAULT_TERMS)!!, "", 6)
        dc.addView(etT, lp(top = 4))
        dc.addView(label("자주 틀리는 말 — 한 줄에 '틀린 말 → 바른 말'", 12f), lp(top = 8))
        val etP = editBox(prefs.getString("dictPairs", TermFixer.DEFAULT_PAIRS)!!, "", 3)
        dc.addView(etP, lp(top = 4))
        dc.addView(label("고치지 않을 말 — 쉼표로 구분", 12f), lp(top = 8))
        val etK = editBox(prefs.getString("dictKeep", TermFixer.DEFAULT_KEEP)!!, "", 1)
        dc.addView(etK, lp(top = 4))
        dc.addView(button("저장하고 아카이브에 올리기", true) {
            prefs.edit().putString("dictTerms", etT.text.toString()).putString("dictPairs", etP.text.toString()).putString("dictKeep", etK.text.toString()).apply()
            status.text = "용어사전 올리는 중…"
            bg.execute {
                val msg = try { uploadDictionary("회의 노트 앱: 용어사전 직접 수정"); "용어사전을 저장하고 아카이브에 올렸습니다" }
                catch (e: Throwable) { "태블릿에는 저장했지만 올리지 못했습니다: ${e.message}" }
                ui.post { status.text = msg }
            }
        }, lp(top = 8).apply { height = dp(46) })
        right.addView(dc)

        if (wide) {
            col.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f))
            col.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.5f).apply { leftMargin = dp(14) })
        } else {
            col.addView(left); col.addView(right, lp(top = 12))
        }
        sc.addView(col)
        root.addView(sc, lp(MATCH_PARENT, 0, 1f, top = 10))
        setContentView(root)
    }

    /** 아카이브의 용어사전을 가져오거나, 없으면 태블릿 것을 올린다 */
    private fun syncDictionary(): String {
        val md = archive.read(ArchiveStore.DICT_PATH)
        return if (md != null) {
            val d = DictionaryFile.parse(md)
            if (d.terms.isNotBlank()) {
                prefs.edit().putString("dictTerms", d.terms).putString("dictPairs", d.pairs).putString("dictKeep", d.keep).apply()
                "용어사전을 아카이브 것으로 맞췄습니다"
            } else "아카이브 용어사전이 비어 있어 그대로 둡니다"
        } else {
            uploadDictionary("회의 노트 앱: 용어사전 처음 올림")
            "용어사전을 아카이브에 처음 올렸습니다"
        }
    }

    private fun uploadDictionary(message: String) {
        val d = DictionaryFile.Dict(
            prefs.getString("dictTerms", TermFixer.DEFAULT_TERMS)!!,
            prefs.getString("dictPairs", TermFixer.DEFAULT_PAIRS)!!,
            prefs.getString("dictKeep", TermFixer.DEFAULT_KEEP)!!,
        )
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
        val log = DictionaryFile.changeLog(archive.read(ArchiveStore.DICT_PATH)) ?: "## 변경 기록\n- $today 회의노트 앱에서 처음 만듦"
        archive.upload(ArchiveStore.DICT_PATH, DictionaryFile.format(d, today, log), message)
    }

    override fun onDestroy() {
        transcriber?.stop()
        super.onDestroy()
    }
}
