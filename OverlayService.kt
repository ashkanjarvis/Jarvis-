package com.jarvis.assistant

import android.animation.ObjectAnimator
import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * سرویس اصلی جارویس.
 * - همیشه در پس‌زمینه فعال است (Foreground Service) و به کلمه‌ی فعال‌ساز گوش می‌دهد.
 * - وقتی "جارویس" شنیده شود، یک دایره‌ی متحرک بالای صفحه (روی همه‌ی برنامه‌ها) نمایش می‌دهد.
 * - بعد از آن به دستور بعدی گوش می‌دهد و آن را اجرا می‌کند (مثلاً باز کردن یک اپ).
 */
class OverlayService : Service(), RecognitionListener {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var ringAnimator: ObjectAnimator? = null

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private lateinit var tts: TextToSpeech
    private lateinit var commandProcessor: CommandProcessor

    // حالت‌های سرویس: در حال گوش دادن برای کلمه‌ی فعال‌ساز، یا در حال گوش دادن به دستور
    private enum class Mode { WAITING_FOR_WAKE_WORD, LISTENING_FOR_COMMAND }
    private var mode = Mode.WAITING_FOR_WAKE_WORD

    private val wakeWords = listOf("جارویس", "جارویز", "jarvis")

    companion object {
        const val CHANNEL_ID = "jarvis_channel"
        const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        commandProcessor = CommandProcessor(this)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // اگر صدای فارسی روی گوشی نصب نباشد، به‌طور خودکار fallback می‌شود
                tts.language = Locale("fa", "IR")
            }
        }

        startForeground(NOTIF_ID, buildNotification())
        setupSpeechRecognizer()
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- نوتیفیکیشن سرویس فورگراند ----------

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("جارویس فعال است")
            .setContentText("در حال گوش دادن برای کلمه‌ی «جارویس»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    // ---------- تشخیص گفتار ----------

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(this)

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
    }

    private fun startListening() {
        try {
            speechRecognizer.startListening(recognizerIntent)
        } catch (e: Exception) {
            // اگر خطا داد، چند لحظه بعد دوباره تلاش کن
            retryListeningLater()
        }
    }

    private fun retryListeningLater() {
        overlayView?.postDelayed({ startListening() }, 700)
            ?: android.os.Handler(mainLooper).postDelayed({ startListening() }, 700)
    }

    override fun onResults(results: android.os.Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.lowercase() ?: ""
        handleRecognizedText(text)
    }

    override fun onPartialResults(partialResults: android.os.Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.lowercase() ?: ""
        if (mode == Mode.WAITING_FOR_WAKE_WORD && containsWakeWord(text)) {
            // به محض شنیدن اسم، بلافاصله ظاهر شو (نیازی به منتظر ماندن برای نتیجه‌ی نهایی نیست)
            onWakeWordDetected()
        }
    }

    private fun handleRecognizedText(text: String) {
        when (mode) {
            Mode.WAITING_FOR_WAKE_WORD -> {
                if (containsWakeWord(text)) {
                    onWakeWordDetected()
                } else {
                    startListening()
                }
            }
            Mode.LISTENING_FOR_COMMAND -> {
                val cleaned = stripWakeWord(text)
                updateStatusText("در حال فکر کردن...")
                // process ممکن است async باشد (مثلاً وقتی از هوش مصنوعی جواب می‌گیرد)
                // callback همیشه روی ترد اصلی صدا زده می‌شود، پس مستقیم می‌شود UI/TTS را صدا زد
                commandProcessor.process(cleaned) { reply ->
                    speak(reply)
                    hideOverlay()
                    mode = Mode.WAITING_FOR_WAKE_WORD
                    startListening()
                }
            }
        }
    }

    private fun containsWakeWord(text: String): Boolean {
        return wakeWords.any { text.contains(it) }
    }

    private fun stripWakeWord(text: String): String {
        var result = text
        wakeWords.forEach { result = result.replace(it, "", ignoreCase = true) }
        return result.trim()
    }

    private fun onWakeWordDetected() {
        mode = Mode.LISTENING_FOR_COMMAND
        showOverlay()
        speak("بله؟")
        startListening()
    }

    override fun onError(error: Int) {
        // خطاهای رایج (سکوت، تایم‌اوت و ...) طبیعی هستند؛ فقط دوباره گوش بده
        retryListeningLater()
    }

    override fun onReadyForSpeech(params: android.os.Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}

    // ---------- افکت صوتی پاسخ (Text To Speech) ----------

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_utterance")
    }

    // ---------- نمایش دایره‌ی متحرک روی صفحه (Overlay) ----------

    private fun showOverlay() {
        if (overlayView != null) return

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_jarvis, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = 60

        windowManager.addView(overlayView, params)

        // انیمیشن چرخش پیوسته‌ی حلقه، دقیقاً شبیه واسط جارویس در فیلم
        val ring = overlayView?.findViewById<ImageView>(R.id.jarvis_ring)
        ringAnimator = ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f).apply {
            duration = 2200
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // انیمیشن ورود (بزرگ شدن از صفر)
        overlayView?.scaleX = 0f
        overlayView?.scaleY = 0f
        overlayView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(250)?.start()
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        view.animate().scaleX(0f).scaleY(0f).setDuration(200).withEndAction {
            ringAnimator?.cancel()
            try {
                windowManager.removeView(view)
            } catch (e: Exception) { /* ممکن است از قبل حذف شده باشد */ }
            overlayView = null
        }.start()
    }

    /** برای وضعیت "در حال گوش دادن..." روی متن پایین دایره */
    private fun updateStatusText(text: String) {
        overlayView?.findViewById<TextView>(R.id.jarvis_status_text)?.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        speechRecognizer.destroy()
        tts.stop()
        tts.shutdown()
    }
}
