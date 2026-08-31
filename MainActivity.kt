package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var jarvisRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnOverlay = findViewById<Button>(R.id.btn_grant_overlay)
        val btnMic = findViewById<Button>(R.id.btn_grant_mic)
        val btnToggle = findViewById<Button>(R.id.btn_toggle_jarvis)
        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val etApiKey = findViewById<EditText>(R.id.et_api_key)
        val btnSaveKey = findViewById<Button>(R.id.btn_save_key)

        etApiKey.setText(PrefsHelper.getApiKey(this))

        btnSaveKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "کلید را وارد کن", Toast.LENGTH_SHORT).show()
            } else {
                PrefsHelper.saveApiKey(this, key)
                Toast.makeText(this, "کلید ذخیره شد", Toast.LENGTH_SHORT).show()
            }
        }

        btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "قبلاً اجازه داده شده", Toast.LENGTH_SHORT).show()
            }
        }

        btnMic.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            } else {
                Toast.makeText(this, "قبلاً اجازه داده شده", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggle.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "اول اجازه‌ی نمایش روی برنامه‌ها را بده", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "اول اجازه‌ی میکروفون را بده", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            jarvisRunning = !jarvisRunning
            if (jarvisRunning) {
                val serviceIntent = Intent(this, OverlayService::class.java)
                ContextCompat.startForegroundService(this, serviceIntent)
                btnToggle.text = getString(R.string.stop_jarvis)
                tvStatus.text = "جارویس فعال است — بگو «جارویس»"
            } else {
                stopService(Intent(this, OverlayService::class.java))
                btnToggle.text = getString(R.string.start_jarvis)
                tvStatus.text = "جارویس خاموش است"
            }
        }
    }
}
