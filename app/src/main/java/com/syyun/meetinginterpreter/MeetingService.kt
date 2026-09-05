package com.syyun.meetinginterpreter

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var active = false
    private var paused = false
    private var sourceTag = "en-US"
    private var sessionStartedAt = 0L
    private var accumulated = 0L
    private var restartPending = false

    private val timerTask = object : Runnable {
        override fun run() {
            if (active) {
                val elapsed = accumulated + if (!paused) SystemClock.elapsedRealtime() - sessionStartedAt else 0L
                MeetingStore.setSession(this@MeetingService, if (paused) "paused" else "running", elapsed)
                broadcast(EVENT_TIMER, elapsed = elapsed)
                if (!paused) updateNotification(elapsed)
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent.getStringExtra(EXTRA_LANGUAGE) ?: "en-US")
            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME -> resumeSession()
            ACTION_STOP -> stopSession()
            ACTION_CLEAR -> { stopSession(); MeetingStore.clear(this) }
        }
        return START_NOT_STICKY
    }

    private fun startSession(language: String) {
        if (active) return
        sourceTag = language
        active = true
        paused = false
        accumulated = 0L
        sessionStartedAt = SystemClock.elapsedRealtime()
        startAsForeground("언어팩과 음성 인식을 준비하고 있습니다")
        acquireWakeLock()
        MeetingStore.setSession(this, "preparing", 0L)
        handler.removeCallbacks(timerTask)
        handler.post(timerTask)
        broadcast(EVENT_STATUS, message = "온디바이스 기능 준비 중")
        prepareTranslationAndRecognizer()
    }

    private fun prepareTranslationAndRecognizer() {
        if (sourceTag == "ko-KR") {
            broadcast(EVENT_MODEL, message = "한국어 회의록 모드")
            createAndStartRecognizer()
            return
        }
        val sourceLanguage = TranslateLanguage.fromLanguageTag(sourceTag.substringBefore('-'))
        if (sourceLanguage == null) {
            broadcast(EVENT_ERROR, message = "선택한 번역 언어를 지원하지 않습니다")
            stopSession()
            return
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
        translator?.close()
        translator = Translation.getClient(options)
        broadcast(EVENT_MODEL, message = "번역 언어팩 확인 중 · 최초 1회 다운로드")
        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnSuccessListener {
                broadcast(EVENT_MODEL, message = "온디바이스 번역 준비 완료")
                createAndStartRecognizer()
            }
            ?.addOnFailureListener { error ->
                broadcast(EVENT_ERROR, message = "언어팩 다운로드 실패: ${error.localizedMessage ?: "인터넷 연결 확인"}")
                stopSession()
            }
    }

    private fun createAndStartRecognizer() {
        if (!active || paused) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            broadcast(EVENT_ERROR, message = "마이크 권한이 필요합니다")
            stopSession()
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            broadcast(EVENT_ERROR, message = "온디바이스 음성 인식 언어팩이 없습니다. 휴대폰 설정에서 음성 인식 언어팩을 설치해 주세요.")
            stopSession()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        if (Build.VERSION.SDK_INT >= 33) runCatching { recognizer?.triggerModelDownload(recognitionIntent()) }
        startListening()
    }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, sourceTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, sourceTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
    }

    private fun startListening() {
        if (!active || paused || restartPending) return
        runCatching { recognizer?.startListening(recognitionIntent()) }
            .onSuccess { broadcast(EVENT_STATUS, message = "온디바이스 음성 인식 중") }
            .onFailure { scheduleRestart(700L) }
    }

    private fun scheduleRestart(delay: Long = 450L) {
        if (!active || paused || restartPending) return
        restartPending = true
        handler.postDelayed({
            restartPending = false
            if (active && !paused) startListening()
        }, delay)
    }

    private fun handleFinal(text: String) {
        if (text.isBlank()) { scheduleRestart(); return }
        val entry = MeetingEntry(
            id = System.currentTimeMillis(),
            time = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date()),
            original = text.trim(),
            translation = if (sourceTag == "ko-KR") text.trim() else ""
        )
        MeetingStore.append(this, entry)
        broadcast(EVENT_ENTRY)
        if (sourceTag == "ko-KR") {
            scheduleRestart()
            return
        }
        translator?.translate(entry.original)
            ?.addOnSuccessListener { translated ->
                MeetingStore.updateTranslation(this, entry.id, translated)
                broadcast(EVENT_ENTRY)
            }
            ?.addOnFailureListener { error ->
                MeetingStore.updateTranslation(this, entry.id, "[번역 실패] ${entry.original}")
                broadcast(EVENT_ERROR, message = "번역 실패: ${error.localizedMessage ?: "언어팩 확인"}")
                broadcast(EVENT_ENTRY)
            }
        scheduleRestart()
    }

    private fun pauseSession() {
        if (!active || paused) return
        accumulated += SystemClock.elapsedRealtime() - sessionStartedAt
        paused = true
        MeetingStore.setSession(this, "paused", accumulated)
        recognizer?.cancel()
        releaseWakeLock()
        broadcast(EVENT_STATUS, message = "회의 일시정지")
        updateNotification(accumulated, "회의가 일시정지되었습니다")
    }

    private fun resumeSession() {
        if (!active || !paused) return
        paused = false
        sessionStartedAt = SystemClock.elapsedRealtime()
        acquireWakeLock()
        MeetingStore.setSession(this, "running", accumulated)
        broadcast(EVENT_STATUS, message = "온디바이스 음성 인식 중")
        startListening()
    }

    private fun stopSession() {
        if (!active) { stopSelf(); return }
        if (!paused) accumulated += SystemClock.elapsedRealtime() - sessionStartedAt
        active = false
        paused = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        translator?.close()
        translator = null
        releaseWakeLock()
        MeetingStore.setSession(this, "stopped", accumulated)
        broadcast(EVENT_STATUS, message = "회의 종료", elapsed = accumulated)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:meeting").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.notification_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(message: String, elapsed: Long = 0L): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wave)
            .setContentTitle("실시간 통역 회의록 · ${formatElapsed(elapsed)}")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun startAsForeground(message: String) {
        val n = notification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIFICATION_ID, n)
    }

    private fun updateNotification(elapsed: Long, message: String = "화면이 꺼져도 통역과 기록을 계속합니다") {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message, elapsed))
    }

    private fun broadcast(event: String, message: String = "", elapsed: Long = -1L) {
        sendBroadcast(Intent(ACTION_EVENT).setPackage(packageName).apply {
            putExtra(EXTRA_EVENT, event)
            putExtra(EXTRA_MESSAGE, message)
            if (elapsed >= 0L) putExtra(EXTRA_ELAPSED, elapsed)
        })
    }

    override fun onReadyForSpeech(params: Bundle?) { broadcast(EVENT_STATUS, message = "말씀하세요 · 온디바이스 인식 중") }
    override fun onBeginningOfSpeech() { broadcast(EVENT_STATUS, message = "음성 감지 중") }
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        broadcast(EVENT_INTERIM, message = text)
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        handleFinal(text)
    }

    override fun onError(error: Int) {
        if (!active || paused) return
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                broadcast(EVENT_ERROR, message = "마이크 권한이 없습니다")
                stopSession()
            }
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                broadcast(EVENT_ERROR, message = "선택한 음성 언어팩을 휴대폰 설정에서 내려받아 주세요")
                stopSession()
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(1000L)
            else -> scheduleRestart(500L)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        translator?.close()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.syyun.meetinginterpreter.START"
        const val ACTION_PAUSE = "com.syyun.meetinginterpreter.PAUSE"
        const val ACTION_RESUME = "com.syyun.meetinginterpreter.RESUME"
        const val ACTION_STOP = "com.syyun.meetinginterpreter.STOP"
        const val ACTION_CLEAR = "com.syyun.meetinginterpreter.CLEAR"
        const val ACTION_EVENT = "com.syyun.meetinginterpreter.EVENT"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_EVENT = "event"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ELAPSED = "elapsed"
        const val EVENT_STATUS = "status"
        const val EVENT_MODEL = "model"
        const val EVENT_ENTRY = "entry"
        const val EVENT_INTERIM = "interim"
        const val EVENT_TIMER = "timer"
        const val EVENT_ERROR = "error"
        private const val CHANNEL_ID = "meeting_interpreter_service"
        private const val NOTIFICATION_ID = 4102

        fun formatElapsed(ms: Long): String {
            val total = ms / 1000
            return "%02d:%02d:%02d".format(total / 3600, total % 3600 / 60, total % 60)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
