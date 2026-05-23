package com.example.a260511

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class DirectionCheckActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator
    private lateinit var tts: TextToSpeech

    private var targetAzimuth = 0f
    private var currentAzimuth = 0f
    private var correctSeconds = 0
    private val requiredSeconds = 3

    private var path = listOf<String>()
    private var edgeTypes = listOf<String>()
    private var currentNodeIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_direction_check)

        path = intent.getStringArrayListExtra("path") ?: listOf()
        edgeTypes = intent.getStringArrayListExtra("edgeTypes") ?: listOf()
        currentNodeIndex = intent.getIntExtra("currentNodeIndex", 0)

        val currentNode = if (path.isNotEmpty()) path[currentNodeIndex] else ""
        val nextNode = if (currentNodeIndex + 1 < path.size) path[currentNodeIndex + 1] else ""

        val isDanger = if (currentNodeIndex < edgeTypes.size) edgeTypes[currentNodeIndex] == "stairs" else false

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.KOREAN
                if (isDanger) tts.speak("계단 구간입니다. 주의하세요.", TextToSpeech.QUEUE_FLUSH, null, null)
                else tts.speak("정면 방향을 맞춰주세요.", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }

        findViewById<TextView>(R.id.tvDestInfo).text = "다음 구역: $nextNode${if (isDanger) " [계단 주의]" else ""}"

        val btnStart = findViewById<Button>(R.id.btnStartMove)
        btnStart.isEnabled = false
        btnStart.setOnClickListener {
            startActivity(Intent(this, NavigationActivity::class.java).apply {
                putStringArrayListExtra("path", ArrayList(path))
                putStringArrayListExtra("edgeTypes", ArrayList(edgeTypes))
                putExtra("currentNodeIndex", currentNodeIndex)
            })
        }

        if (currentNode.isNotEmpty() && nextNode.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val res = ApiClient.api.direction(DirectionRequest(currentNode, nextNode))
                    targetAzimuth = res.angle
                } catch (e: Exception) {
                    targetAzimuth = 180f
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            findViewById<Button>(R.id.btnStartMove).isEnabled = true
            findViewById<TextView>(R.id.tvDirectionStatus).text = "방향 감지 준비 완료 (센서 생략)"
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        vibrator.cancel()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ORIENTATION) {
            currentAzimuth = event.values[0]
            updateDirectionUI(currentAzimuth)
        }
    }

    private fun updateDirectionUI(azimuth: Float) {
        val diff = abs(azimuth - targetAzimuth).let {
            if (it > 180) 360 - it else it
        }

        val tvStatus = findViewById<TextView>(R.id.tvDirectionStatus)
        val tvAngle = findViewById<TextView>(R.id.tvAngleDiff)
        val tvVib = findViewById<TextView>(R.id.tvVibrationInfo)
        val btnStart = findViewById<Button>(R.id.btnStartMove)

        tvAngle.text = "목표각과의 차이: ${diff.toInt()}°"

        when {
            diff <= 15 -> {
                correctSeconds++
                tvStatus.text = "올바른 방향입니다! ($correctSeconds / $requiredSeconds 초)"
                tvVib.text = "강한 진동 알림 중"
                vibrate(500, 500)
                if (correctSeconds >= requiredSeconds) {
                    vibrator.cancel()
                    btnStart.isEnabled = true
                    tvStatus.text = "방향 고정 완료! 출발하세요."
                    tts.speak("방향이 확인되었습니다. 출발 버튼을 눌러주세요.", TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
            diff <= 30 -> {
                correctSeconds = 0
                tvStatus.text = "조금만 더 몸을 돌려보세요."
                tvVib.text = "약한 패스형 진동 중"
                vibrate(200, 800)
                btnStart.isEnabled = false
            }
            else -> {
                correctSeconds = 0
                tvStatus.text = "몸을 회전하여 방향을 찾으세요."
                tvVib.text = "진동 없음"
                btnStart.isEnabled = false
                vibrator.cancel()
            }
        }
    }

    private fun vibrate(onMs: Long, offMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pattern = longArrayOf(0, onMs, offMs)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, onMs, offMs), 0)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}