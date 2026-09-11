package com.ld.microsoftttsbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var portInput: EditText
    private lateinit var backendSpinner: Spinner
    private lateinit var lanCheck: CheckBox
    private lateinit var status: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var batteryBtn: Button
    private lateinit var ttsEngineStatus: TextView
    private var systemTts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }
        root.addView(TextView(this).apply {
            text = "Microsoft TTS Bridge"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "为 Operit 提供微软免费 Translator TTS 的本地 HTTP 接口"
            textSize = 16f
        })

        portInput = EditText(this).apply {
            hint = "端口"
            setText("8765")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(portInput)

        backendSpinner = Spinner(this)
        backendSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("translator")
        )
        root.addView(backendSpinner)

        lanCheck = CheckBox(this).apply {
            text = "允许局域网访问（默认仅本机 127.0.0.1）"
            isChecked = false
        }
        root.addView(lanCheck)

        val start = Button(this).apply {
            text = "启动 Bridge"
            setOnClickListener { startBridge() }
        }
        root.addView(start)

        val stop = Button(this).apply {
            text = "停止 Bridge"
            setOnClickListener {
                stopService(Intent(this@MainActivity, BridgeService::class.java))
                status.text = "已停止"
            }
        }
        root.addView(stop)

        batteryStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, 20, 0, 0)
        }
        root.addView(batteryStatus)

        batteryBtn = Button(this).apply {
            text = "设置电池无限制（防后台冻结）"
            setOnClickListener { requestBatteryOptimizationExemption() }
        }
        root.addView(batteryBtn)

        ttsEngineStatus = TextView(this).apply {
            textSize = 14f
            setPadding(0, 20, 0, 0)
        }
        root.addView(ttsEngineStatus)

        root.addView(Button(this).apply {
            text = "打开系统 TTS 设置"
            setOnClickListener {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            }
        })

        root.addView(Button(this).apply {
            text = "用系统默认 TTS 试听"
            setOnClickListener { testSystemTts(forceFallback = false) }
        })

        root.addView(Button(this).apply {
            text = "测试原系统语音临时兜底"
            setOnClickListener { testSystemTts(forceFallback = true) }
        })

        status = TextView(this).apply {
            text = "未启动"
            textSize = 16f
            setPadding(0, 28, 0, 0)
        }
        root.addView(status)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateBatteryStatus()
        updateTtsEngineStatus()
    }

    override fun onDestroy() {
        systemTts?.shutdown()
        systemTts = null
        super.onDestroy()
    }

    private fun isBatteryOptimizedIgnored(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBatteryStatus() {
        if (isBatteryOptimizedIgnored()) {
            batteryStatus.text = "后台电池策略：无限制（正常，不会被系统冻结）"
            batteryBtn.visibility = View.GONE
        } else {
            batteryStatus.text = "后台电池策略：受限（MIUI/HyperOS 会在后台冻结进程导致超时）"
            batteryBtn.visibility = View.VISIBLE
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (!isBatteryOptimizedIgnored()) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {}
            }
        }
    }

    private fun updateTtsEngineStatus() {
        val defaultEngine = Settings.Secure.getString(contentResolver, "tts_default_synth")
        ttsEngineStatus.text = if (defaultEngine == packageName) {
            "系统 TTS：Microsoft TTS Bridge（当前默认）"
        } else {
            "系统 TTS：当前默认 ${defaultEngine ?: "未设置"}"
        }
    }

    private fun testSystemTts(forceFallback: Boolean) {
        systemTts?.shutdown()
        status.text = "正在初始化系统默认 TTS…"
        systemTts = TextToSpeech(this) { initStatus ->
            val tts = systemTts
            if (initStatus != TextToSpeech.SUCCESS || tts == null) {
                status.text = "系统 TTS 初始化失败：$initStatus"
                return@TextToSpeech
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread { status.text = "系统 TTS 正在播放…" }
                }

                override fun onDone(utteranceId: String?) {
                    runOnUiThread { status.text = "系统 TTS 试听完成" }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread { status.text = "系统 TTS 合成失败" }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    runOnUiThread { status.text = "系统 TTS 合成失败：$errorCode" }
                }
            })
            val languageResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                status.text = "系统 TTS 不支持简体中文：$languageResult"
                return@TextToSpeech
            }
            val params = Bundle().apply {
                if (forceFallback) {
                    putBoolean(MicrosoftTextToSpeechService.FORCE_FALLBACK_PARAM, true)
                }
            }
            val queued = tts.speak(
                if (forceFallback) "这是一次原系统语音临时兜底测试。"
                else "微软云端语音已经成为安卓系统语音引擎。",
                TextToSpeech.QUEUE_FLUSH,
                params,
                if (forceFallback) "system-fallback-smoke" else "microsoft-tts-bridge-smoke",
            )
            status.text = if (queued == TextToSpeech.SUCCESS) {
                "系统 TTS 请求已提交：${tts.defaultEngine}"
            } else {
                "系统 TTS 请求提交失败：$queued"
            }
        }
    }

    private fun startBridge() {
        val port = portInput.text.toString().toIntOrNull()
            ?: return status.setText("端口必须是数字")
        if (port !in 1024..65535) return status.setText("端口范围：1024-65535")
        val intent = Intent(this, BridgeService::class.java).apply {
            action = BridgeService.ACTION_START
            putExtra(BridgeService.EXTRA_PORT, port)
            putExtra(BridgeService.EXTRA_BACKEND, backendSpinner.selectedItem.toString())
            putExtra(BridgeService.EXTRA_BIND_ALL, lanCheck.isChecked)
        }
        ContextCompat.startForegroundService(this, intent)
        status.text = "启动中：${if (lanCheck.isChecked) "0.0.0.0" else "127.0.0.1"}:$port"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }
}
