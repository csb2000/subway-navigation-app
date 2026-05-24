package com.example.a260511

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    private var currentNode = ""
    private var routeNodes = listOf<RouteNode>()
    private var selectedDestination = ""
    private var selectedDestinationName = ""

    private val LOCATION_REQUEST_CODE = 100
    private var pendingLocate = false

    private lateinit var tvLocation: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvWifi: TextView
    private lateinit var tvDest: TextView
    private lateinit var btnCheck: Button
    private lateinit var btnGo: Button
    private lateinit var btnReset: Button
    private lateinit var btnSetDest: Button

    private val destinationMap = mapOf(
        "역 출입구" to "station_exit",
        "하행 승강장" to "down_platform",
        "상행 승강장" to "up_platform"
    )

    private val dummyRoute = listOf(
        RouteNode(node = "station_exit", edge_to_next = "flat", zone = "entrance", floor = "ground"),
        RouteNode(node = "fare_gate", edge_to_next = "flat", zone = "gate", floor = "1F"),
        RouteNode(node = "floor1_hall", edge_to_next = "flat", zone = "hall", floor = "1F"),
        RouteNode(node = "floor1_stairs", edge_to_next = "stairs", zone = "stairs", floor = "1F"),
        RouteNode(node = "stairs_mid", edge_to_next = "stairs", zone = "stairs", floor = "mid"),
        RouteNode(node = "b1_stairs", edge_to_next = "flat", zone = "stairs", floor = "B1"),
        RouteNode(node = "b1_elevator", edge_to_next = "flat", zone = "hall", floor = "B1"),
        RouteNode(node = "b1_down_stairs_front", edge_to_next = "branch", zone = "branch", floor = "B1"),
        RouteNode(node = "down_platform", edge_to_next = null, zone = "platform", floor = "B1")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.KOREAN
                ttsReady = true
                speak("지하철 네비게이션 홈 화면입니다. 핸드폰 중심을 기준으로 상단 목적지 설정, 하단 현 위치 확인 입니다.")
            }
        }

        tvLocation = findViewById(R.id.tvCurrentLocation)
        tvStatus = findViewById(R.id.tvLocStatus)
        tvWifi = findViewById(R.id.tvWifiStrength)
        tvDest = findViewById(R.id.tvSelectedDestination)
        btnSetDest = findViewById(R.id.btnSetDestination)
        btnCheck = findViewById(R.id.btnCheckLocation)
        btnGo = findViewById(R.id.btnGoDirection)
        btnReset = findViewById(R.id.btnReset)

        btnSetDest.setOnClickListener {
            speak("목적지 설정 화면입니다. 화면을 터치하여 목적지를 설정하세요.")
            val items = destinationMap.keys.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("목적지 선택")
                .setItems(items) { _, which ->
                    val name = items[which]
                    selectedDestination = destinationMap[name] ?: ""
                    selectedDestinationName = name
                    tvDest.text = name
                    speak("${name}을 목적지로 설정하였습니다. 이동 방향을 확인하세요.")
                }
                .show()
        }

        btnCheck.setOnClickListener {
            if (!hasLocationPermission()) {
                pendingLocate = true
                requestLocationPermission()
                return@setOnClickListener
            }
            startLocate()
        }

        btnGo.setOnClickListener {
            if (selectedDestination.isEmpty()) {
                speak("목적지를 먼저 설정해주세요.")
                return@setOnClickListener
            }
            if (currentNode.isEmpty() || routeNodes.isEmpty()) return@setOnClickListener
            val intent = Intent(this, DirectionCheckActivity::class.java)
            intent.putStringArrayListExtra("path", ArrayList(routeNodes.map { it.node }))
            intent.putStringArrayListExtra("edgeTypes", ArrayList(routeNodes.map { it.edge_to_next ?: "" }))
            intent.putExtra("currentNodeIndex", 0)
            intent.putExtra("isFirstNode", true)
            intent.putExtra("destinationName", selectedDestinationName)
            startActivity(intent)
        }

        btnReset.setOnClickListener {
            resetState()
        }

        if (!hasLocationPermission()) {
            requestLocationPermission()
        }
    }

    private fun resetState() {
        handler.removeCallbacksAndMessages(null)
        currentNode = ""
        routeNodes = listOf()
        selectedDestination = ""
        selectedDestinationName = ""
        pendingLocate = false
        tvLocation.text = "-"
        tvStatus.text = "대기 중"
        tvWifi.text = "-"
        tvDest.text = "미설정"
        btnCheck.isEnabled = true
        btnGo.isEnabled = false
        speak("초기 상태로 돌아갑니다. 홈 화면 입니다.")
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && pendingLocate) {
                pendingLocate = false
                startLocate()
            } else if (!granted) {
                pendingLocate = false
                tvStatus.text = "위치 권한이 필요합니다."
                btnCheck.isEnabled = true
            }
        }
    }

    private fun startLocate() {
        speak("위치를 확인합니다.")
        tvStatus.text = "위치 측정 중..."
        tvWifi.text = "스캔 중..."
        btnCheck.isEnabled = false
        btnGo.isEnabled = false
        locateWithRetry()
    }

    private fun locateWithRetry(retryCount: Int = 0) {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        if (!hasLocationPermission()) {
            tvStatus.text = "권한 오류"
            btnCheck.isEnabled = true
            return
        }

        try {
            wifiManager.startScan()
        } catch (e: Exception) {
            // 에뮬레이터에서 막혀도 진행
        }

        handler.postDelayed({
            val filtered = try {
                filterWifi(wifiManager.scanResults)
            } catch (e: Exception) {
                emptyList()
            }

            if (filtered.isEmpty() && retryCount < 3) {
                tvStatus.text = "재시도 중... (${retryCount + 1}/3)"
                locateWithRetry(retryCount + 1)
                return@postDelayed
            }

            if (filtered.isEmpty()) {
                tvWifi.text = "AP 0개 (더미 모드)"
                setDummyLocation()
                return@postDelayed
            }

            tvWifi.text = "AP ${filtered.size}개 감지"

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val locateRes = ApiClient.api.locate(LocateRequest(filtered))
                    currentNode = locateRes.node

                    val dest = if (selectedDestination.isNotEmpty()) selectedDestination else "down_platform"
                    val routeRes = ApiClient.api.route(RouteRequest(currentNode, dest))
                    routeNodes = routeRes.path

                    withContext(Dispatchers.Main) {
                        val koreanName = nodeNameMap[currentNode] ?: currentNode
                        tvLocation.text = koreanName
                        tvStatus.text = "위치 인식됨"
                        btnCheck.isEnabled = true
                        btnGo.isEnabled = true
                        speak("현 위치는 ${koreanName}으로 추정됩니다.")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (retryCount < 3) {
                            tvStatus.text = "서버 재시도 중... (${retryCount + 1}/3)"
                            locateWithRetry(retryCount + 1)
                        } else {
                            setDummyLocation()
                        }
                    }
                }
            }
        }, 2000)
    }

    private fun setDummyLocation() {
        currentNode = "station_exit"
        routeNodes = dummyRoute
        val koreanName = nodeNameMap[currentNode] ?: currentNode
        tvLocation.text = koreanName
        tvStatus.text = "더미 위치 인식됨 (테스트용)"
        btnCheck.isEnabled = true
        btnGo.isEnabled = true
        speak("현 위치는 ${koreanName}으로 추정됩니다.")
    }

    fun filterWifi(scanResults: List<android.net.wifi.ScanResult>): List<WifiAp> {
        return scanResults
            .filter { it.level >= -90 }
            .filter {
                val s = it.SSID.lowercase()
                !s.contains("[dryer]") &&
                        !s.contains("[system a/c]") &&
                        !s.contains("[washer]") &&
                        !s.contains("[refrigerator]") &&
                        !s.startsWith("direct-") &&
                        !s.contains("androidap") &&
                        !s.contains("iphone") &&
                        !s.endsWith("_guest")
            }
            .map { WifiAp(it.BSSID, it.level) }
    }

    private fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }
}