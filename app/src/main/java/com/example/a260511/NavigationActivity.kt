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
    private var destinationName = ""
    private var collectTimer: Runnable? = null

    private val recentScanWindows = mutableListOf<List<WifiAp>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        path = intent.getStringArrayListExtra("path") ?: listOf()
        edgeTypes = intent.getStringArrayListExtra("edgeTypes") ?: listOf()
        currentNodeIndex = intent.getIntExtra("currentNodeIndex", 0)
        destinationName = intent.getStringExtra("destinationName") ?: ""

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

    override fun onResume() {
        super.onResume()
        if (collectTimer == null) startLocationCollect()
    }

    override fun onPause() {
        super.onPause()
        stopCollect()
        recentScanWindows.clear()
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
            val currentRawScan = wifiManager.scanResults
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

            if (currentRawScan.isEmpty()) return@postDelayed

            recentScanWindows.add(currentRawScan)
            if (recentScanWindows.size > 3) recentScanWindows.removeAt(0)

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
            tts.speak("목적지에 도달하였습니다. 초기화면으로 돌아갑니다.", TextToSpeech.QUEUE_FLUSH, null, null)
            stopCollect()
            handler.postDelayed({
                startActivity(Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                })
            }, 3000)
            return
        }

        val isDanger = if (currentNodeIndex < edgeTypes.size) edgeTypes[currentNodeIndex] == "stairs" else false
        val koreanName = nodeNameMap[path[currentNodeIndex]] ?: path[currentNodeIndex]

        if (isDanger) {
            tts.speak("계단 구간입니다. 주의하세요.", TextToSpeech.QUEUE_FLUSH, null, null)
            findViewById<LinearLayout>(R.id.layoutDangerAlert).visibility = View.VISIBLE
        } else {
            tts.speak("${koreanName}에 도달하였습니다. 다음 구역 이동 방향을 확인하세요.", TextToSpeech.QUEUE_FLUSH, null, null)
            findViewById<LinearLayout>(R.id.layoutDangerAlert).visibility = View.GONE
        }

        updateNodeUI()

        startActivity(Intent(this, DirectionCheckActivity::class.java).apply {
            putStringArrayListExtra("path", ArrayList(path))
            putStringArrayListExtra("edgeTypes", ArrayList(edgeTypes))
            putExtra("currentNodeIndex", currentNodeIndex)
            putExtra("isFirstNode", false)
            putExtra("destinationName", destinationName)
        })
    }

    private fun updateNodeUI() {
        val currentNode = if (path.isNotEmpty()) path[currentNodeIndex] else "-"
        val nextNode = if (currentNodeIndex + 1 < path.size) path[currentNodeIndex + 1] else "목적지"
        val koreanCurrent = nodeNameMap[currentNode] ?: currentNode
        val koreanNext = nodeNameMap[nextNode] ?: nextNode

        findViewById<TextView>(R.id.tvNodeStatus).text = "${currentNodeIndex + 1}번째 노드 이동 중"
        findViewById<TextView>(R.id.tvNodeProgressText).text = "현재: $koreanCurrent → 다음: $koreanNext"
        findViewById<TextView>(R.id.tvNextNodeInfo).text = "다음: $koreanNext"
        findViewById<TextView>(R.id.tvNodeLabel).text = "${destinationName}까지"
        findViewById<TextView>(R.id.tvLastSent).text = "5초 주기 위치 수집 중..."
    }

    override fun onDestroy() {
        stopCollect()
        tts.shutdown()
        super.onDestroy()
    }
}