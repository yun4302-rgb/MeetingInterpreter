package com.syyun.meetinginterpreter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var meetingName: EditText
    private lateinit var meetingPlace: EditText
    private lateinit var meetingTime: EditText
    private lateinit var languageSpinner: Spinner
    private lateinit var languagePackStatusText: TextView
    private lateinit var downloadLanguagePackButton: Button
    private lateinit var companyContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusHint: TextView
    private lateinit var timerText: TextView
    private lateinit var deviceStatusText: TextView
    private lateinit var transcriptTitle: TextView
    private lateinit var interimText: TextView
    private lateinit var transcriptContainer: LinearLayout
    private lateinit var translationContainer: LinearLayout
    private lateinit var translationCard: View
    private lateinit var modelStatusText: TextView
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var finishButton: Button
    private lateinit var downloadButton: Button
    private lateinit var keyPointsEdit: EditText
    private lateinit var decisionsEdit: EditText
    private lateinit var actionsEdit: EditText

    private val languages = listOf(
        LanguageOption("한국어 · 회의록 전용", "ko-KR"),
        LanguageOption("English → 한국어", "en-US"),
        LanguageOption("日本語 → 한국어", "ja-JP"),
        LanguageOption("中文（简体）→ 한국어", "zh-CN"),
        LanguageOption("Deutsch → 한국어", "de-DE"),
        LanguageOption("Français → 한국어", "fr-FR"),
        LanguageOption("Español → 한국어", "es-ES"),
        LanguageOption("Tiếng Việt → 한국어", "vi-VN")
    )

    private var pendingStart = false
    private var packRecognizer: SpeechRecognizer? = null
    private var packTranslator: Translator? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(MeetingService.EXTRA_EVENT)) {
                MeetingService.EVENT_STATUS -> {
                    val message = intent.getStringExtra(MeetingService.EXTRA_MESSAGE).orEmpty()
                    statusHint.text = message
                    if (message == "회의 종료") {
                        setControls("stopped")
                        createSummary()
                    } else setControls(MeetingStore.sessionState(this@MainActivity))
                }
                MeetingService.EVENT_MODEL -> modelStatusText.text = intent.getStringExtra(MeetingService.EXTRA_MESSAGE)
                MeetingService.EVENT_ENTRY -> renderEntries()
                MeetingService.EVENT_INTERIM -> interimText.text = intent.getStringExtra(MeetingService.EXTRA_MESSAGE).orEmpty()
                MeetingService.EVENT_TIMER -> timerText.text = MeetingService.formatElapsed(intent.getLongExtra(MeetingService.EXTRA_ELAPSED, 0L))
                MeetingService.EVENT_ERROR -> {
                    val message = intent.getStringExtra(MeetingService.EXTRA_MESSAGE).orEmpty()
                    statusText.text = "●  확인 필요"
                    statusHint.text = message
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        bindViews()
        setupLanguageSpinner()
        restoreState()
        setupActions()
        renderEntries()
        setControls(MeetingStore.sessionState(this))
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.appRoot)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(MeetingService.ACTION_EVENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        renderEntries()
        setControls(MeetingStore.sessionState(this))
    }

    override fun onStop() {
        saveMetadata()
        unregisterReceiver(receiver)
        super.onStop()
    }

    private fun bindViews() {
        meetingName = findViewById(R.id.meetingName)
        meetingPlace = findViewById(R.id.meetingPlace)
        meetingTime = findViewById(R.id.meetingTime)
        languageSpinner = findViewById(R.id.languageSpinner)
        languagePackStatusText = findViewById(R.id.languagePackStatusText)
        downloadLanguagePackButton = findViewById(R.id.downloadLanguagePackButton)
        companyContainer = findViewById(R.id.companyContainer)
        statusText = findViewById(R.id.statusText)
        statusHint = findViewById(R.id.statusHint)
        timerText = findViewById(R.id.timerText)
        deviceStatusText = findViewById(R.id.deviceStatusText)
        transcriptTitle = findViewById(R.id.transcriptTitle)
        interimText = findViewById(R.id.interimText)
        transcriptContainer = findViewById(R.id.transcriptContainer)
        translationContainer = findViewById(R.id.translationContainer)
        translationCard = findViewById(R.id.translationCard)
        modelStatusText = findViewById(R.id.modelStatusText)
        startButton = findViewById(R.id.startButton)
        pauseButton = findViewById(R.id.pauseButton)
        finishButton = findViewById(R.id.finishButton)
        downloadButton = findViewById(R.id.downloadButton)
        keyPointsEdit = findViewById(R.id.keyPointsEdit)
        decisionsEdit = findViewById(R.id.decisionsEdit)
        actionsEdit = findViewById(R.id.actionsEdit)
    }

    private fun setupLanguageSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages.map { it.label }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        languageSpinner.adapter = adapter
        languageSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val korean = languages[position].tag == "ko-KR"
                translationCard.visibility = if (korean) View.GONE else View.VISIBLE
                startButton.text = if (korean) "●  회의록 기록 시작" else "●  통역·기록 시작"
                transcriptTitle.text = if (korean) "한국어 음성 인식" else "${languages[position].label.substringBefore(" →")} 음성 인식"
                languagePackStatusText.text = if (korean) {
                    "한국어 음성 인식팩을 기기에 미리 설치합니다."
                } else {
                    "${languages[position].label}: 음성 인식팩과 한국어 번역팩을 설치합니다."
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun setupActions() {
        findViewById<Button>(R.id.addCompanyButton).setOnClickListener { addCompanyRow("", "") }
        findViewById<Button>(R.id.clearButton).setOnClickListener { confirmClear() }
        meetingTime.setOnClickListener { chooseDateTime() }
        downloadLanguagePackButton.setOnClickListener { downloadSelectedLanguagePack() }
        startButton.setOnClickListener { requestStart() }
        pauseButton.setOnClickListener {
            val state = MeetingStore.sessionState(this)
            val action = if (state == "paused") MeetingService.ACTION_RESUME else MeetingService.ACTION_PAUSE
            startService(Intent(this, MeetingService::class.java).setAction(action))
            setControls(if (state == "paused") "running" else "paused")
        }
        finishButton.setOnClickListener {
            startService(Intent(this, MeetingService::class.java).setAction(MeetingService.ACTION_STOP))
            setControls("stopped")
            createSummary()
        }
        downloadButton.setOnClickListener { exportWord() }
    }

    private fun downloadSelectedLanguagePack() {
        val selected = languages[languageSpinner.selectedItemPosition]
        downloadLanguagePackButton.isEnabled = false
        languagePackStatusText.text = "${selected.label}: 음성 인식팩 확인 중…"

        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            languagePackStatusText.text = "이 휴대폰은 Android 온디바이스 음성 인식을 지원하지 않습니다."
            downloadLanguagePackButton.isEnabled = true
            return
        }

        packRecognizer?.destroy()
        packRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { packRecognizer?.triggerModelDownload(languagePackIntent(selected.tag)) }
                .onFailure {
                    languagePackStatusText.text = "음성팩 요청 실패: ${it.localizedMessage ?: "휴대폰 음성 설정을 확인하세요"}"
                }
        }

        if (selected.tag == "ko-KR") {
            languagePackStatusText.text = if (Build.VERSION.SDK_INT >= 33) {
                "한국어 음성 인식팩 다운로드를 요청했습니다. 시스템 안내를 완료해 주세요."
            } else {
                "Android 12에서는 휴대폰 설정의 음성 입력 메뉴에서 한국어팩을 설치해 주세요."
            }
            downloadLanguagePackButton.isEnabled = true
            return
        }

        val source = TranslateLanguage.fromLanguageTag(selected.tag.substringBefore('-'))
        if (source == null) {
            languagePackStatusText.text = "선택한 번역 언어는 지원되지 않습니다."
            downloadLanguagePackButton.isEnabled = true
            return
        }

        packTranslator?.close()
        packTranslator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build()
        )
        languagePackStatusText.text = "${selected.label}: 번역팩 다운로드 중… 앱을 닫지 마세요."
        packTranslator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnSuccessListener {
                languagePackStatusText.text = "✓ 번역팩 준비 완료 · 음성팩 시스템 다운로드 요청 완료"
                downloadLanguagePackButton.isEnabled = true
                Toast.makeText(this, "${selected.label} 언어팩 준비가 완료되었습니다.", Toast.LENGTH_LONG).show()
            }
            ?.addOnFailureListener { error ->
                languagePackStatusText.text = "번역팩 다운로드 실패: ${error.localizedMessage ?: "인터넷 연결을 확인하세요"}"
                downloadLanguagePackButton.isEnabled = true
            }
    }

    private fun languagePackIntent(languageTag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    override fun onDestroy() {
        packRecognizer?.destroy()
        packRecognizer = null
        packTranslator?.close()
        packTranslator = null
        super.onDestroy()
    }

    private fun requestStart() {
        if (meetingName.text.toString().trim().isEmpty()) {
            meetingName.error = "회의명을 입력해 주세요"
            meetingName.requestFocus()
            return
        }
        saveMetadata()
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) {
            pendingStart = true
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
        } else startMeetingService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST || !pendingStart) return
        pendingStart = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startMeetingService()
        else AlertDialog.Builder(this)
            .setTitle("마이크 권한이 필요합니다")
            .setMessage("설정 → 애플리케이션 → 실시간 통역 회의록 → 권한에서 마이크를 허용해 주세요.")
            .setPositiveButton("확인", null).show()
    }

    private fun startMeetingService() {
        val language = languages[languageSpinner.selectedItemPosition].tag
        val intent = Intent(this, MeetingService::class.java).setAction(MeetingService.ACTION_START)
            .putExtra(MeetingService.EXTRA_LANGUAGE, language)
        startForegroundService(intent)
        setControls("preparing")
        statusHint.text = if (language == "ko-KR") "온디바이스 한국어 음성 인식을 준비합니다" else "온디바이스 언어팩을 확인하고 있습니다"
    }

    private fun setControls(state: String) {
        val busy = state in listOf("running", "paused", "preparing")
        startButton.isEnabled = !busy
        pauseButton.isEnabled = busy && state != "preparing"
        finishButton.isEnabled = busy
        languageSpinner.isEnabled = !busy
        pauseButton.text = if (state == "paused") "계속하기" else "일시정지"
        statusText.text = when (state) {
            "running" -> "●  회의 진행 중"
            "paused" -> "●  일시정지"
            "preparing" -> "●  언어팩 준비 중"
            "stopped" -> "●  회의 종료"
            else -> "●  회의 준비"
        }
        deviceStatusText.text = if (busy) "화면 꺼짐 방지 적용 · 백그라운드 기록 실행 중" else "온디바이스 서비스 준비"
        if (busy && state != "paused") window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        timerText.text = MeetingService.formatElapsed(MeetingStore.elapsed(this))
    }

    private fun addCompanyRow(company: String, people: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val companyEdit = EditText(this).apply {
            hint = "업체명"
            setText(company)
            background = getDrawable(R.drawable.bg_input)
            setTextColor(getColor(R.color.ink))
            textSize = 14f
            tag = "company"
            layoutParams = LinearLayout.LayoutParams(0, dp(48), .38f).apply { marginEnd = dp(6) }
        }
        val peopleEdit = EditText(this).apply {
            hint = "참석자 이름"
            setText(people)
            background = getDrawable(R.drawable.bg_input)
            setTextColor(getColor(R.color.ink))
            textSize = 14f
            tag = "people"
            layoutParams = LinearLayout.LayoutParams(0, dp(48), .52f).apply { marginEnd = dp(6) }
        }
        val remove = Button(this).apply {
            text = "×"
            textSize = 18f
            setTextColor(Color.rgb(157, 51, 64))
            background = getDrawable(R.drawable.bg_secondary)
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(46))
            setOnClickListener { companyContainer.removeView(row) }
        }
        row.addView(companyEdit); row.addView(peopleEdit); row.addView(remove)
        companyContainer.addView(row)
    }

    private fun companyEntries(): List<CompanyEntry> = (0 until companyContainer.childCount).mapNotNull { index ->
        val row = companyContainer.getChildAt(index) as? LinearLayout ?: return@mapNotNull null
        val company = row.findViewWithTag<EditText>("company")?.text?.toString()?.trim().orEmpty()
        val people = row.findViewWithTag<EditText>("people")?.text?.toString()?.trim().orEmpty()
        if (company.isBlank() && people.isBlank()) null else CompanyEntry(company, people)
    }

    private fun saveMetadata() {
        MeetingStore.saveMetadata(
            this, meetingName.text.toString(), meetingPlace.text.toString(), meetingTime.text.toString(),
            languages[languageSpinner.selectedItemPosition].tag, companyEntries(),
            keyPointsEdit.text.toString(), decisionsEdit.text.toString(), actionsEdit.text.toString()
        )
    }

    private fun restoreState() {
        val data = MeetingStore.metadata(this)
        meetingName.setText(data["name"])
        meetingPlace.setText(data["place"])
        meetingTime.setText(data["time"].takeUnless { it.isNullOrBlank() } ?: nowLabel())
        val selected = languages.indexOfFirst { it.tag == data["language"] }.coerceAtLeast(0)
        languageSpinner.setSelection(selected)
        keyPointsEdit.setText(data["keyPoints"])
        decisionsEdit.setText(data["decisions"])
        actionsEdit.setText(data["actions"])
        val companies = MeetingStore.companies(this)
        (companies.ifEmpty { listOf(CompanyEntry("자사", ""), CompanyEntry("협력사", "")) })
            .forEach { addCompanyRow(it.company, it.people) }
    }

    private fun renderEntries() {
        val entries = MeetingStore.entries(this)
        transcriptContainer.removeAllViews()
        translationContainer.removeAllViews()
        entries.forEach { entry ->
            transcriptContainer.addView(entryView(entry.time, entry.original, false))
            if (entry.translation.isNotBlank() && languages[languageSpinner.selectedItemPosition].tag != "ko-KR") {
                translationContainer.addView(entryView(entry.time, entry.translation, true))
            }
        }
        interimText.text = ""
        downloadButton.isEnabled = entries.isNotEmpty()
    }

    private fun entryView(time: String, content: String, translated: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = getDrawable(if (translated) R.drawable.bg_secondary else R.drawable.bg_input)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            addView(TextView(this@MainActivity).apply { text = time; textSize = 11f; setTextColor(Color.rgb(132,144,160)) })
            addView(TextView(this@MainActivity).apply { text = content; textSize = 16f; setTextColor(getColor(R.color.ink)); setPadding(0,dp(3),0,0) })
        }
    }

    private fun createSummary() {
        val entries = MeetingStore.entries(this)
        val lines = entries.map { it.translation.ifBlank { it.original } }.distinct().filter { it.length > 5 }
        if (keyPointsEdit.text.isBlank()) keyPointsEdit.setText(lines.take(8).joinToString("\n") { "• $it" })
        val decisions = lines.filter { it.contains(Regex("결정|확정|합의|승인|진행|채택")) }
        if (decisionsEdit.text.isBlank()) decisionsEdit.setText(decisions.take(6).joinToString("\n") { "• $it" }.ifBlank { "• 별도 결정 사항 없음" })
        val actions = lines.filter { it.contains(Regex("요청|담당|검토|확인|공유|제출|일정|까지")) }
        if (actionsEdit.text.isBlank()) actionsEdit.setText(actions.take(6).joinToString("\n") { "□ $it" }.ifBlank { "□ 후속 조치 확인 필요" })
        saveMetadata()
    }

    private fun exportWord() {
        saveMetadata()
        val entries = MeetingStore.entries(this)
        if (entries.isEmpty()) { Toast.makeText(this, "저장할 회의 기록이 없습니다", Toast.LENGTH_SHORT).show(); return }
        val title = meetingName.text.toString().trim().ifBlank { "회의록" }
        val language = languages[languageSpinner.selectedItemPosition].label
        val participantRows = companyEntries().joinToString("") { "<tr><td>${html(it.company)}</td><td>${html(it.people)}</td></tr>" }
        val recordRows = entries.joinToString("") { entry ->
            val translated = entry.translation.takeIf { it.isNotBlank() && it != entry.original }
            "<tr><td>${html(entry.time)}</td><td>${html(entry.original)}</td><td>${html(translated ?: "-")}</td></tr>"
        }
        val document = """<!doctype html><html><head><meta charset="utf-8"><style>
            body{font-family:'Malgun Gothic',sans-serif;color:#182230;line-height:1.55;margin:38px}h1{color:#102a43;border-bottom:3px solid #1677ff;padding-bottom:10px}h2{color:#102a43;margin-top:25px}table{width:100%;border-collapse:collapse}th,td{border:1px solid #b9c5d1;padding:8px;text-align:left}th{background:#eaf1f7}.box{white-space:pre-wrap;background:#f4f7fa;padding:14px}</style></head><body>
            <h1>${html(title)}</h1><table><tr><th>회의 일시</th><td>${html(meetingTime.text.toString())}</td><th>장소</th><td>${html(meetingPlace.text.toString())}</td></tr><tr><th>언어</th><td colspan="3">${html(language)}</td></tr></table>
            <h2>참석자</h2><table><tr><th>업체</th><th>참석자</th></tr>$participantRows</table>
            <h2>핵심 논의</h2><div class="box">${html(keyPointsEdit.text.toString())}</div>
            <h2>결정 사항</h2><div class="box">${html(decisionsEdit.text.toString())}</div>
            <h2>Action Item</h2><div class="box">${html(actionsEdit.text.toString())}</div>
            <h2>전체 기록</h2><table><tr><th>시간</th><th>원문</th><th>한국어 통역</th></tr>$recordRows</table></body></html>"""
        val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "${safeName}_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date())}.doc")
            put(MediaStore.Downloads.MIME_TYPE, "application/msword")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/MeetingMinutes")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { Toast.makeText(this, "파일을 만들 수 없습니다", Toast.LENGTH_LONG).show(); return }
        contentResolver.openOutputStream(uri)?.use { output ->
            output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            output.write(document.toByteArray(Charsets.UTF_8))
        }
        Toast.makeText(this, "Download/MeetingMinutes에 Word 회의록을 저장했습니다", Toast.LENGTH_LONG).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("새 회의를 시작할까요?")
            .setMessage("현재 회의 정보와 기록이 모두 삭제됩니다. 필요한 경우 먼저 Word 파일을 저장하세요.")
            .setNegativeButton("취소", null)
            .setPositiveButton("모두 지우기") { _, _ -> clearMeeting() }.show()
    }

    private fun clearMeeting() {
        startService(Intent(this, MeetingService::class.java).setAction(MeetingService.ACTION_CLEAR))
        MeetingStore.clear(this)
        meetingName.setText(""); meetingPlace.setText(""); meetingTime.setText(nowLabel())
        languageSpinner.setSelection(1)
        companyContainer.removeAllViews(); addCompanyRow("자사", ""); addCompanyRow("협력사", "")
        keyPointsEdit.setText(""); decisionsEdit.setText(""); actionsEdit.setText("")
        interimText.text = ""; renderEntries(); timerText.text = "00:00:00"; setControls("idle")
    }

    private fun chooseDateTime() {
        val calendar = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                meetingTime.setText("%04d-%02d-%02d %02d:%02d".format(year, month + 1, day, hour, minute))
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun nowLabel(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date())
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun html(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    data class LanguageOption(val label: String, val tag: String)

    companion object { private const val PERMISSION_REQUEST = 1001 }
}
