package com.example.a260511

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private var currentNode: String = ""
    private val fallbackNode = "station_exit"
    private val maxRetries = 3

    private lateinit var tvLocation: TextView
    private lateinit var tvStatus: TextView

    private val LOCATION_PERMISSION_REQUEST = 100
    private var pendingLocationCheck = false

    private val destinationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
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

        TtsManager.speak("지하철 네비게이션 홈 화면입니다. 핸드폰 중심을 기준으로 상단 목적지 설정, 하단 현 위치 확인 입니다.")

        findViewById<Button>(R.id.btnSetDestination).setOnClickListener {
            TtsManager.speak("목적지 설정 화면입니다.")
            if (currentNode.isEmpty()) currentNode = fallbackNode
            destinationLauncher.launch(Intent(this, DestinationActivity::class.java))
        }

        findViewById<Button>(R.id.btnCheckLocation).setOnClickListener {
            if (!hasLocationPermission()) {
                pendingLocationCheck = true
                requestLocationPermission()
                return@setOnClickListener
            }
            checkLocationWithRetry()
        }

        if (!hasLocationPermission()) {
            requestLocationPermission()
        }
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
            LOCATION_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && pendingLocationCheck) {
                pendingLocationCheck = false
                checkLocationWithRetry()
            } else if (!granted) {
                pendingLocationCheck = false
                tvStatus.text = "위치 권한이 필요합니다."
                TtsManager.speak("위치 권한이 필요합니다.")
            }
        }
    }

    private fun checkLocationWithRetry() {
        TtsManager.speak("위치를 확인합니다.")
        tvStatus.text = "위치 측정 중..."
        Log.d("MainActivity", "위치 측정 시작")

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        var attemptCount = 0

        // [FIX] BroadcastReceiver로 스캔 완료 이벤트 수신
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                attemptCount++
                Log.d("MainActivity", "스캔 완료 (시도 $attemptCount, success=$success)")
                tvStatus.text = "위치 측정 중... ($attemptCount/$maxRetries)"

                val wifiList = try {
                    filterWifi(wifiManager.scanResults).also {
                        Log.d("MainActivity", "필터링 후 AP ${it.size}개")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "스캔 결과 읽기 오류: ${e.message}")
                    emptyList()
                }

                if (wifiList.isNotEmpty()) {
                    // AP 잡혔으면 수신 해제 후 서버 전송
                    unregisterReceiver(this)
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val node = ApiClient.api.locate(LocateRequest(wifiList)).node
                            Log.d("MainActivity", "서버 위치 인식 성공: $node")
                            withContext(Dispatchers.Main) {
                                applyNode(node, measured = true)
                                TtsManager.speak("현 위치는 ${nodeNameMap[node] ?: node}으로 추정됩니다.")
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "서버 locate 실패: ${e.message}")
                            withContext(Dispatchers.Main) {
                                applyNode(fallbackNode, measured = false)
                                TtsManager.speak("서버 연결에 실패하였습니다. 기본 위치로 진행합니다.")
                            }
                        }
                    }
                } else if (attemptCount >= maxRetries) {
                    // 3회 실패 → fallback
                    unregisterReceiver(this)
                    Log.w("MainActivity", "AP 없음 - $maxRetries 회 실패 → fallback")
                    applyNode(fallbackNode, measured = false)
                    TtsManager.speak("위치 측정에 실패하였습니다. 기본 위치인 ${nodeNameMap[fallbackNode]}으로 진행합니다.")
                } else {
                    // 재시도
                    Log.w("MainActivity", "AP 없음 - 재시도 중...")
                    tvStatus.postDelayed({ wifiManager.startScan() }, 1000)
                }
            }
        }

        registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        wifiManager.startScan()
    }

    private fun filterWifi(scanResults: List<android.net.wifi.ScanResult>): List<WifiAp> {
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

    private fun startRoute(destId: String, destName: String) {
        if (currentNode.isEmpty()) currentNode = fallbackNode
        Toast.makeText(this, "경로 탐색 중...", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "경로 탐색 시작: $currentNode → $destId")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val res = ApiClient.api.route(RouteRequest(currentNode, destId))
                val path = ArrayList(res.path.map { it.node })
                val edgeTypes = ArrayList(res.path.map { it.edge_to_next ?: "" })
                Log.d("MainActivity", "경로 탐색 성공: ${path.size}개 노드 - $path")

                withContext(Dispatchers.Main) {
                    if (path.isEmpty()) {
                        Toast.makeText(this@MainActivity, "경로를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                        TtsManager.speak("경로를 찾을 수 없습니다.")
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
                Log.e("MainActivity", "경로 탐색 실패: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "경로 탐색 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    TtsManager.speak("서버 연결에 실패하였습니다. 잠시 후 다시 시도해주세요.")
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