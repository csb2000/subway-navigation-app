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

    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private val azimuthHistory = mutableListOf<Float>()

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

        // [FIX] 계단 구간 여부 체크
        val isStairs = stairsSegments.contains(Pair(currentNode, nextNode))

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.KOREAN
                ttsReady = true
                when {
                    isFirstNode -> speak("점자블록을 따라 이동하세요.")
                    // [FIX] 계단 구간 TTS
                    isStairs -> speak("계단 구간입니다. 보조 블럭을 따라 이동하세요.")
                    isDanger -> speak("계단 구간입니다. 주의하세요.")
                    else -> speak("다음 노드에 도달하였습니다. 다음 구역 이동 방향을 확인하세요.")
                }
            }
        }

        val koreanNext = nodeNameMap[nextNode] ?: nextNode
        findViewById<TextView>(R.id.tvDestInfo).text =
            "다음: $koreanNext${if (isDanger || isStairs) " ⚠ 계단" else ""}"

        val btnStart = findViewById<Button>(R.id.btnStartMove)

        // [FIX] 첫 노드 또는 계단 구간이면 방향 확인 스킵
        if (isFirstNode || isStairs) {
            directionConfirmed = true
            btnStart.isEnabled = true
            findViewById<TextView>(R.id.tvDirectionStatus).text =
                if (isStairs) "보조 블럭을 따라 이동하세요" else "점자블록을 따라 이동하세요"
            findViewById<TextView>(R.id.tvVibrationInfo).text = "-"
            handler.postDelayed({ btnStart.performClick() }, 10000)
        } else {
            btnStart.isEnabled = false
        }

        btnStart.setOnClickListener {
            val navIntent = Intent(this, NavigationActivity::class.java)
            navIntent.putStringArrayListExtra("path", ArrayList(path))
            navIntent.putStringArrayListExtra("edgeTypes", ArrayList(edgeTypes))
            navIntent.putExtra("currentNodeIndex", currentNodeIndex)
            navIntent.putExtra("destinationName", destinationName)
            navIntent.putStringArrayListExtra("visitedNodes", ArrayList(
                intent.getStringArrayListExtra("visitedNodes") ?: listOf()
            ))
            startActivity(navIntent)
        }

        findViewById<TextView>(R.id.btnCancelDirection).setOnClickListener {
            tts.stop()
            val mainIntent = Intent(this, MainActivity::class.java)
            mainIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(mainIntent)
        }

        if (!isFirstNode && !isStairs && currentNode.isNotEmpty() && nextNode.isNotEmpty()) {
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
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null ||
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) == null) {
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
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelerometerReading.set(event.values)
            Sensor.TYPE_MAGNETIC_FIELD -> magnetometerReading.set(event.values)
        }

        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) return

        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val rawAzimuth = (Math.toDegrees(orientationAngles[0].toDouble()).toFloat() + 360) % 360

        azimuthHistory.add(rawAzimuth)
        if (azimuthHistory.size > 5) azimuthHistory.removeAt(0)
        currentAzimuth = azimuthHistory.average().toFloat()

        updateDirectionUI(currentAzimuth)
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
                if (correctStartTime == -1L) correctStartTime = System.currentTimeMillis()
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
                    handler.postDelayed({ btnStart.performClick() }, 10000)
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

    private fun FloatArray.set(values: FloatArray) {
        values.forEachIndexed { i, v -> this[i] = v }
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