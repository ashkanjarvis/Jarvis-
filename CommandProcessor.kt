package com.jarvis.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * متن تشخیص داده شده از گفتار کاربر را می‌گیرد و تصمیم می‌گیرد چه کاری انجام شود:
 * 1) اگر دستور محلی شناخته‌شده باشد (مثل باز کردن یک اپ) → همان‌جا و بدون اینترنت انجام می‌شود.
 * 2) هر سوال یا دستور دیگری (عمومی، تخصصی، گیمینگ و ...) → به هوش مصنوعی سپرده می‌شود
 *    (در پس‌زمینه، بدون قفل کردن رابط کاربری) و با شخصیت "جارویس" جواب داده می‌شود.
 *
 * برای افزودن دستور محلیِ جدید (که نیاز به اینترنت ندارد) فقط کافیست
 * یک "if" جدید در تابع process اضافه کنی.
 */
class CommandProcessor(private val context: Context) {

    private val appLauncher = AppLauncher(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val openWords = listOf("باز کن", "باز کردن", "برو", "open", "launch")

    /**
     * @param command متنی که کاربر گفته
     * @param callback همیشه روی ترد اصلی (UI) صدا زده می‌شود، تا مستقیم بشود صحبتش کرد یا UI را آپدیت کرد
     */
    fun process(command: String, callback: (String) -> Unit) {
        val text = command.trim()
        if (text.isEmpty()) {
            callback("متوجه نشدم، دوباره بگو")
            return
        }

        val lower = text.lowercase()

        // 1) دستور محلی: باز کردن یک برنامه (سریع، بدون نیاز به اینترنت)
        val isOpenCommand = openWords.any { lower.contains(it) }
        if (isOpenCommand) {
            var appName = text
            for (w in openWords) {
                appName = appName.replace(w, "", ignoreCase = true)
            }
            appName = appName.trim()

            val opened = appLauncher.openApp(appName)
            callback(if (opened) "$appName را باز کردم" else "برنامه‌ی «$appName» پیدا نشد")
            return
        }

        // جای مناسب برای افزودن دستورهای محلیِ بیشتر در آینده، مثلاً:
        // if (lower.contains("ساعت")) { callback("ساعت الان ..."); return }

        // 2) هر چیز دیگری (سوال عمومی، تخصصی، تنظیمات گیم، هرچی) → از هوش مصنوعی بپرس
        askAi(text, callback)
    }

    private fun askAi(text: String, callback: (String) -> Unit) {
        val apiKey = PrefsHelper.getApiKey(context)
        if (apiKey.isBlank()) {
            callback("برای پاسخ به این سوال به کلید هوش مصنوعی نیاز دارم؛ یک کلید رایگان در تنظیمات وارد کن")
            return
        }

        executor.execute {
            val client = GeminiClient(apiKey)
            val reply = client.ask(text)
            mainHandler.post { callback(reply) }
        }
    }
}
