package com.example.a260511

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NavigationActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())

    private var path = listOf<String>()
    private var edgeTypes = listOf<String>()
    private var currentNodeIndex = 0
    private var destinationName = ""
    private var collectTimer: Runnable? = null

    private val recentScanWindows = mutableListOf<List<WifiAp>>()
    private val visitedNodes = mutableSetOf<String>()

    private var nextNodeConfirmCount = 0
    private val requiredConfirmCount = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        path = intent.getStringArrayListExtra("path") ?: listOf()
        edgeTypes = intent.getStringArrayListExtra("edgeTypes") ?: listOf()
        currentNodeIndex = intent.getIntExtra("currentNodeIndex", 0)
        destinationName = intent.getStringExtra("destinationName") ?: ""

        val savedVisited = intent.getStringArrayListExtra("visitedNodes") ?: listOf()
        visitedNodes.addAll(savedVisited)
        path.getOrNull(currentNodeIndex)?.let { visitedNodes.add(it) }

        updateNodeUI()

        findViewById<Button>(R.id.btnNodeArrived).setOnClickListener {
            currentNodeIndex++
            nextNodeConfirmCount = 0
            path.getOrNull(currentNodeIndex)?.let { visitedNodes.add(it) }
            onNodeArrived()
        }

        findViewById<Button>(R.id.btnStopNav).setOnClickListener {
            stopCollect()
            TtsManager.stop()
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
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
                handler.postDelayed(this, 3000)
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
                    val res = ApiClient.api.locate(LocateRequest(averagedPayload, visitedNodes.toList()))
                    Log.d("NavigationActivity", "passed: ${visitedNodes.toList()}, detected: ${res.node}")
                    val detectedNode = res.node
                    val nextIndex = currentNodeIndex + 1

                    if (detectedNode in visitedNodes) {
                        Log.w("NavigationActivity", "지나온 노드 무시: $detectedNode")
                        nextNodeConfirmCount = 0
                        return@launch
                    }

                    val validNodes = setOf(
                        path.getOrNull(currentNodeIndex),
                        path.getOrNull(currentNodeIndex + 1)
                    )

                    if (detectedNode !in validNodes) {
                        Log.w("NavigationActivity", "범위 밖 노드 무시: $detectedNode")
                        nextNodeConfirmCount = 0
                        return@launch
                    }

                    if (nextIndex < path.size && detectedNode == path[nextIndex]) {
                        nextNodeConfirmCount++
                        Log.d("NavigationActivity", "다음 노드 감지 $nextNodeConfirmCount/$requiredConfirmCount: $detectedNode")

                        if (nextNodeConfirmCount >= requiredConfirmCount) {
                            withContext(Dispatchers.Main) {
                                currentNodeIndex = nextIndex
                                nextNodeConfirmCount = 0
                                path.getOrNull(currentNodeIndex)?.let { visitedNodes.add(it) }
                                onNodeArrived()
                            }
                        }
                    } else {
                        nextNodeConfirmCount = 0
                    }
                } catch (e: Exception) {
                    Log.e("NavigationActivity", "locate 실패: ${e.message}")
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
            TtsManager.speak("목적지에 도달하였습니다. 초기화면으로 돌아갑니다.")
            stopCollect()
            handler.postDelayed({
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                startActivity(intent)
            }, 3000)
            return
        }

        val isDanger = if (currentNodeIndex < edgeTypes.size) edgeTypes[currentNodeIndex] == "stairs" else false
        // [FIX] 현재 위치명 TTS
        val koreanName = nodeNameMap[path[currentNodeIndex]] ?: path[currentNodeIndex]

        if (isDanger) {
            TtsManager.speak("${koreanName}에 도달하였습니다. 계단 구간입니다. 주의하세요.")
            findViewById<LinearLayout>(R.id.layoutDangerAlert).visibility = View.VISIBLE
        } else {
            TtsManager.speak("${koreanName}에 도달하였습니다. 다음 구역 이동 방향을 확인하세요.")
            findViewById<LinearLayout>(R.id.layoutDangerAlert).visibility = View.GONE
        }

        updateNodeUI()

        val nextIntent = Intent(this, DirectionCheckActivity::class.java)
        nextIntent.putStringArrayListExtra("path", ArrayList(path))
        nextIntent.putStringArrayListExtra("edgeTypes", ArrayList(edgeTypes))
        nextIntent.putExtra("currentNodeIndex", currentNodeIndex)
        nextIntent.putExtra("isFirstNode", false)
        nextIntent.putExtra("destinationName", destinationName)
        nextIntent.putStringArrayListExtra("visitedNodes", ArrayList(visitedNodes))
        startActivity(nextIntent)
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
        findViewById<TextView>(R.id.tvLastSent).text = "3초 주기 위치 수집 중..."
    }

    override fun onDestroy() {
        stopCollect()
        super.onDestroy()
    }
}