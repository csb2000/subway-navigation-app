package com.example.a260511

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class DirectionCheckActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())

    private var targetAzimuth = 0f
    private var currentAzimuth = 0f

    private var correctStartTime = -1L
    private val requiredMs = 3000L
    private var directionConfirmed = false

    private var path = listOf<String>()
    private var edgeTypes = listOf<String>()
    private var currentNodeIndex = 0
    private var isFirstNode = false
    private var destinationName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_direction_check)

        path = intent.getStringArrayListExtra("path") ?: listOf()
        edgeTypes = intent.getStringArrayListExtra("edgeTypes") ?: listOf()
        currentNodeIndex = intent.getIntExtra("currentNodeIndex", 0)
        isFirstNode = intent.getBooleanExtra("isFirstNode", false)
        destinationName = intent.getStringExtra("destinationName") ?: ""

        val currentNode = if (path.isNotEmpty()) path[currentNodeIndex] else ""
        val nextNode = if (currentNodeIndex + 1 < path.size) path[currentNodeIndex + 1] else ""
        val isDanger = if (currentNodeIndex < edgeTypes.size) edgeTypes[currentNodeIndex] == "stairs" else false

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.KOREAN
                ttsReady = true
                when {
                    isDanger -> speak("계단 구간입니다. 주의하세요.")
                    isFirstNode && destinationName.isNotEmpty() -> speak("${destinationName}을 목적지로 설정하였습니다. 이동 방향을 확인하세요.")
                    else -> speak("다음 노드에 도달하였습니다. 다음 구역 이동 방향을 확인하세요.")
                }
            }
        }

        val koreanNext = nodeNameMap[nextNode] ?: nextNode
        findViewById<TextView>(R.id.tvDestInfo).text =
            "다음: $koreanNext${if (isDanger) " ⚠ 계단" else ""}"

        val btnStart = findViewById<Button>(R.id.btnStartMove)
        btnStart.isEnabled = false
        btnStart.setOnClickListener {
            val navIntent = Intent(this, NavigationActivity::class.java)
            navIntent.putStringArrayListExtra("path", ArrayList(path))
            navIntent.putStringArrayListExtra("edgeTypes", ArrayList(edgeTypes))
            navIntent.putExtra("currentNodeIndex", currentNodeIndex)
            navIntent.putExtra("destinationName", destinationName)
            startActivity(navIntent)
        }

        findViewById<TextView>(R.id.btnCancelDirection).setOnClickListener {
            tts.stop()
            val mainIntent = Intent(this, MainActivity::class.java)
            mainIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(mainIntent)
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
        if (directionConfirmed) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            findViewById<Button>(R.id.btnStartMove).isEnabled = true
            findViewById<TextView>(R.id.tvDirectionStatus).text = "방향 확인 준비됨"
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
        if (directionConfirmed) return

        val diff = abs(azimuth - targetAzimuth).let {
            if (it > 180) 360 - it else it
        }

        val tvStatus = findViewById<TextView>(R.id.tvDirectionStatus)
        val tvAngle = findViewById<TextView>(R.id.tvAngleDiff)
        val tvVib = findViewById<TextView>(R.id.tvVibrationInfo)
        val btnStart = findViewById<Button>(R.id.btnStartMove)

        tvAngle.text = "각도 차이: ${diff.toInt()}°"

        when {
            diff <= 15 -> {
                if (correctStartTime == -1L) {
                    correctStartTime = System.currentTimeMillis()
                }
                val elapsed = System.currentTimeMillis() - correctStartTime
                val remaining = ((requiredMs - elapsed) / 1000) + 1

                tvStatus.text = "올바른 방향! (${remaining}초 유지)"
                tvVib.text = "진동 2회/초"
                vibrate(500, 500)

                if (elapsed >= requiredMs) {
                    directionConfirmed = true
                    vibrator.cancel()
                    sensorManager.unregisterListener(this)
                    btnStart.isEnabled = true
                    tvStatus.text = "방향 확인 완료!"
                    speak("방향이 일치합니다. 다음 구역에 도달할때까지 직진하세요.")
                    // [FIX] 1.5초 후 자동으로 이동 시작
                    handler.postDelayed({
                        btnStart.performClick()
                    }, 1500)
                }
            }
            diff <= 30 -> {
                correctStartTime = -1L
                tvStatus.text = "조금만 더 돌려주세요"
                tvVib.text = "진동 1회/초"
                btnStart.isEnabled = false
                vibrate(300, 700)
            }
            else -> {
                correctStartTime = -1L
                tvStatus.text = "몸을 천천히 돌려주세요"
                tvVib.text = "진동 없음"
                btnStart.isEnabled = false
                vibrator.cancel()
            }
        }
    }

    private fun vibrate(onMs: Long, offMs: Long) {
        val pattern = longArrayOf(0, onMs, offMs)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }

    private fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts.shutdown()
        super.onDestroy()
    }
}