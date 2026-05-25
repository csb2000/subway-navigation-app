package com.example.a260511

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private var currentNode: String = ""
    private val fallbackNode = "station_exit"
    private val maxRetries = 3

    private lateinit var tvLocation: TextView
    private lateinit var tvStatus: TextView

    private val destinationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 디버그용: 결과가 오는지 확인
        Toast.makeText(
            this,
            "결과 받음: code=${result.resultCode}, id=${result.data?.getStringExtra("destinationId")}",
            Toast.LENGTH_LONG
        ).show()

        if (result.resultCode == RESULT_OK) {
            val destId = result.data?.getStringExtra("destinationId") ?: return@registerForActivityResult
            val destName = result.data?.getStringExtra("destinationName") ?: ""
            startRoute(destId, destName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLocation = findViewById(R.id.tvCurrentLocation)
        tvStatus = findViewById(R.id.tvLocStatus)

        // 사양 1번: 홈 화면 진입 안내
        TtsManager.speak("지하철 네비게이션 홈 화면입니다. 핸드폰 중심을 기준으로 상단 목적지 설정, 하단 현 위치 확인 입니다.")

        // 목적지 설정
        findViewById<Button>(R.id.btnSetDestination).setOnClickListener {
            if (currentNode.isEmpty()) currentNode = fallbackNode
            destinationLauncher.launch(Intent(this, DestinationActivity::class.java))
        }

        // 사양 7번: 현 위치 확인
        findViewById<Button>(R.id.btnCheckLocation).setOnClickListener {
            checkLocationWithRetry()
        }
    }

    // 사양 7번 흐름
    private fun checkLocationWithRetry() {
        TtsManager.speak("위치를 확인합니다.")  // 7-1
        CoroutineScope(Dispatchers.Main).launch {
            for (attempt in 1..maxRetries) {
                tvStatus.text = "위치 측정 중... ($attempt/$maxRetries)"

                val wifiList: List<WifiAp> = try {
                    val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifiManager.scanResults.map { WifiAp(it.BSSID, it.level) }
                } catch (e: Exception) {
                    emptyList()
                }

                if (wifiList.isNotEmpty()) {
                    val node = try {
                        withContext(Dispatchers.IO) {
                            ApiClient.api.locate(LocateRequest(wifiList)).node  // 7-2
                        }
                    } catch (e: Exception) {
                        null
                    }
                    if (node != null) {
                        applyNode(node, measured = true)
                        val korean = nodeNameMap[node] ?: node
                        TtsManager.speak("현 위치는 ${korean}으로 추정됩니다.")  // 7-3
                        return@launch
                    }
                }
                if (attempt < maxRetries) delay(1000)
            }
            // 3회 실패 → 더미
            Toast.makeText(this@MainActivity, "측정 실패 - 기본 위치로 진행", Toast.LENGTH_SHORT).show()
            applyNode(fallbackNode, measured = false)
            val korean = nodeNameMap[fallbackNode] ?: fallbackNode
            TtsManager.speak("현 위치는 ${korean}으로 추정됩니다.")
        }
    }

    private fun startRoute(destId: String, destName: String) {
        if (currentNode.isEmpty()) currentNode = fallbackNode
        Toast.makeText(this, "경로 탐색 중...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val res = ApiClient.api.route(RouteRequest(currentNode, destId))
                val path = ArrayList(res.path.map { it.node })
                val edgeTypes = ArrayList(res.path.map { it.edge_to_next ?: "" })

                withContext(Dispatchers.Main) {
                    if (path.isEmpty()) {
                        Toast.makeText(this@MainActivity, "경로를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    val intent = Intent(this@MainActivity, DirectionCheckActivity::class.java)
                    intent.putStringArrayListExtra("path", path)
                    intent.putStringArrayListExtra("edgeTypes", edgeTypes)
                    intent.putExtra("currentNodeIndex", 0)
                    intent.putExtra("isFirstNode", true)
                    intent.putExtra("destinationName", destName)
                    startActivity(intent)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "경로 탐색 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applyNode(node: String, measured: Boolean) {
        currentNode = node
        tvLocation.text = nodeNameMap[node] ?: node
        tvStatus.text = if (measured) "위치 인식됨" else "기본 위치 (측정 실패)"
    }
}