package com.example.a260511

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var currentNode = ""
    private var routeNodes = listOf<RouteNode>()
    private var selectedDestination = "down_platform"

    private val LOCATION_REQUEST_CODE = 100
    private var pendingLocate = false  // 권한 허용 후 자동 측위 진행용

    // 뷰 참조 보관 (권한 콜백에서 다시 쓰기 위해)
    private lateinit var tvLocation: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvWifi: TextView
    private lateinit var btnCheck: Button
    private lateinit var btnGo: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLocation = findViewById(R.id.tvCurrentLocation)
        tvStatus = findViewById(R.id.tvLocStatus)
        tvWifi = findViewById(R.id.tvWifiStrength)
        btnCheck = findViewById(R.id.btnCheckLocation)
        btnGo = findViewById(R.id.btnGoDirection)

        btnCheck.setOnClickListener {
            // 권한이 없으면 먼저 요청하고, 허용되면 자동으로 측위 진행
            if (!hasLocationPermission()) {
                pendingLocate = true
                requestLocationPermission()
                return@setOnClickListener
            }
            startLocate()
        }

        btnGo.setOnClickListener {
            if (currentNode.isEmpty() || routeNodes.isEmpty()) return@setOnClickListener
            val intent = Intent(this, DirectionCheckActivity::class.java)
            intent.putStringArrayListExtra("path", ArrayList(routeNodes.map { it.node }))
            intent.putStringArrayListExtra("edgeTypes", ArrayList(routeNodes.map { it.edge_to_next ?: "" }))
            intent.putExtra("currentNodeIndex", 0)
            startActivity(intent)
        }

        // 시작 시 미리 권한 요청 (있으면 콜백 안 옴)
        if (!hasLocationPermission()) {
            requestLocationPermission()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
                tvStatus.text = "위치 권한이 필요합니다. 설정에서 허용해주세요."
                btnCheck.isEnabled = true
            }
        }
    }

    private fun startLocate() {
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
            // 무시하고 진행 (에뮬레이터에서 막혀도 더미로 빠지게)
        }

        handler.postDelayed({
            val filtered = try {
                wifiManager.scanResults
                    .filter { it.level >= -90 }
                    .filter {
                        val ssid = it.SSID.lowercase()
                        !ssid.contains("iphone") && !ssid.contains("dryer") &&
                                !ssid.contains("android") && !ssid.contains("hotspot")
                    }
                    .map { WifiAp(it.BSSID, it.level) }
            } catch (e: Exception) {
                emptyList()
            }

            if (filtered.isEmpty() && retryCount < 3) {
                tvStatus.text = "재시도 중... (${retryCount + 1}/3)"
                locateWithRetry(retryCount + 1)
                return@postDelayed
            }

            // === 에뮬레이터 대응: 재시도 다 해도 AP가 0개면 더미 위치로 진행 ===
            if (filtered.isEmpty()) {
                tvWifi.text = "AP 0개 (에뮬레이터 더미 모드)"
                currentNode = "start_node"          // TODO: 서버가 인식하는 실제 출발 노드 ID로
                routeNodes = listOf(
                    RouteNode(node = "start_node", edge_to_next = "stairs", zone = "A", floor = "1"),
                    RouteNode(node = "mid_node", edge_to_next = null, zone = "A", floor = "1"),
                    RouteNode(node = "down_platform", edge_to_next = null, zone = "B", floor = "0")
                )
                tvLocation.text = currentNode
                tvStatus.text = "더미 위치 인식됨 (테스트용)"
                btnCheck.isEnabled = true
                btnGo.isEnabled = true
                return@postDelayed
            }
            // ===========================================================

            tvWifi.text = "AP ${filtered.size}개 감지"

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val locateRes = ApiClient.api.locate(LocateRequest(filtered))
                    currentNode = locateRes.node

                    val routeRes = ApiClient.api.route(RouteRequest(currentNode, selectedDestination))
                    routeNodes = routeRes.path

                    withContext(Dispatchers.Main) {
                        tvLocation.text = currentNode
                        tvStatus.text = "위치 인식됨"
                        btnCheck.isEnabled = true
                        btnGo.isEnabled = true
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "서버 에러: ${e.message}"
                        btnCheck.isEnabled = true
                    }
                }
            }
        }, 2000)
    }
}