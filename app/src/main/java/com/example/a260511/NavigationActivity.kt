package com.example.a260511

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class NavigationActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech

    private var path = listOf<String>()
    private var edgeTypes = listOf<String>()
    private var currentNodeIndex = 0
    private var collectTimer: Runnable? = null

    private val recentScanWindows = mutableListOf<List<WifiAp>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        path = intent.getStringArrayListExtra("path") ?: listOf()
        edgeTypes = intent.getStringArrayListExtra("edgeTypes") ?: listOf()
        currentNodeIndex = intent.getIntExtra("currentNodeIndex", 0)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts.language = Locale.KOREAN
        }

        updateNodeUI()

        findViewById<Button>(R.id.btnNodeArrived).setOnClickListener {
            onNodeArrived()
        }

        findViewById<Button>(R.id.btnStopNav).setOnClickListener {
            stopCollect()
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
    }

    // 화면이 활성화될 때 (이동 단계) 데이터 수집 시작
    override fun onResume() {
        super.onResume()
        if (collectTimer == null) {
            startLocationCollect()
        }
    }

    // 화면을 벗어날 때 (방향 탐색 중) 데이터 수집 완벽 중단
    override fun onPause() {
        super.onPause()
        stopCollect()
        recentScanWindows.clear() // 이전 구간의 찌꺼기 데이터 초기화
    }

    private fun startLocationCollect() {
        collectTimer = object : Runnable {
            override fun run() {
                scanAndLocateWithWindow()
                handler.postDelayed(this, 5000)
            }
        }
        handler.post(collectTimer!!)
    }

    private fun scanAndLocateWithWindow() {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiManager.startScan()

        handler.postDelayed({
            val scanResults = wifiManager.scanResults
            val currentRawScan = scanResults
                .filter { it.level >= -90 }
                .filter {
                    val ssid = it.SSID.lowercase()
                    !ssid.contains("iphone") && !ssid.contains("dryer") &&
                            !ssid.contains("android") && !ssid.contains("hotspot")
                }
                .map { WifiAp(it.BSSID, it.level) }

            if (currentRawScan.isEmpty()) return@postDelayed

            recentScanWindows.add(currentRawScan)
            if (recentScanWindows.size > 3) {
                recentScanWindows.removeAt(0)
            }

            val averagedPayload = computeSlidingAverage(recentScanWindows)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val res = ApiClient.api.locate(LocateRequest(averagedPayload))
                    val detectedNode = res.node

                    val nextIndex = currentNodeIndex + 1
                    if (nextIndex < path.size && detectedNode == path[nextIndex]) {
                        withContext(Dispatchers.Main) {
                            currentNodeIndex = nextIndex
                            onNodeArrived()
                        }
                    }
                } catch (e: Exception) {
                    // 오류 무시 후 루프 유지
                }
            }
        }, 2000)
    }

    private fun computeSlidingAverage(windows: List<List<WifiAp>>): List<WifiAp> {
        val apMap = mutableMapOf<String, MutableList<Int>>()
        for (window in windows) {
            for (ap in window) {
                apMap.getOrPut(ap.bssid) { mutableListOf() }.add(ap.rssi)
            }
        }
        return apMap.map { (bssid, rssiList) ->
            WifiAp(bssid, rssiList.average().toInt())
        }
    }

    private fun stopCollect() {
        collectTimer?.let {
            handler.removeCallbacks(it)
            collectTimer = null
        }
    }

    private fun onNodeArrived() {
        if (currentNodeIndex >= path.size - 1) {
            tts.speak("목적지에 도달하였습니다. 안내를 종료합니다.", TextToSpeech.QUEUE_FLUSH, null, null)
            stopCollect()
            startActivity(Intent(this, ArriveActivity::class.java))
            return
        }

        val isDanger = if (currentNodeIndex < edgeTypes.size) edgeTypes[currentNodeIndex] == "stairs" else false

        if (isDanger) {
            tts.speak("전방에 계단 진입 구간입니다. 발밑을 주의해 주세요.", TextToSpeech.QUEUE_FLUSH, null, null)
            findViewById<LinearLayout>(R.id.layoutDangerAlert).visibility = View.VISIBLE
        } else {
            tts.speak("다음 안내 지점에 진입했습니다. 이동 경로를 따라 계속 직진하세요.", TextToSpeech.QUEUE_FLUSH, null, null)
            findViewById<LinearLayout>(R.id.layoutDangerAlert).visibility = View.GONE
        }

        updateNodeUI()

        startActivity(Intent(this, DirectionCheckActivity::class.java).apply {
            putStringArrayListExtra("path", ArrayList(path))
            putStringArrayListExtra("edgeTypes", ArrayList(edgeTypes))
            putExtra("currentNodeIndex", currentNodeIndex)
        })
    }

    private fun updateNodeUI() {
        val currentNode = if (path.isNotEmpty()) path[currentNodeIndex] else "-"
        val nextNode = if (currentNodeIndex + 1 < path.size) path[currentNodeIndex + 1] else "목적지"

        findViewById<TextView>(R.id.tvNodeStatus).text = "${currentNodeIndex + 1}번째 인덱스 구역 이동 중"
        findViewById<TextView>(R.id.tvNodeProgressText).text = "현재 지점: $currentNode → 다음 목표: $nextNode"
        findViewById<TextView>(R.id.tvNextNodeInfo).text = "다음 구역: $nextNode"
        findViewById<TextView>(R.id.tvNodeLabel).text = "최종 목적지 승강장 방면"
        findViewById<TextView>(R.id.tvLastSent).text = "5초 주기 슬라이딩 평균 필터 작동 중"
    }

    override fun onDestroy() {
        stopCollect()
        tts.shutdown()
        super.onDestroy()
    }
}