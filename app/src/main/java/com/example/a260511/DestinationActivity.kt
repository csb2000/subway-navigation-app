package com.example.a260511

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DestinationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_destination)

        TtsManager.speak("목적지 설정 화면입니다. 목적지를 선택하세요.")

        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            TtsManager.speak("목적지 설정을 취소합니다.")
            finish()
        }

        findViewById<LinearLayout>(R.id.itemStationExit).setOnClickListener {
            TtsManager.speak("역 출입구를 목적지로 설정하였습니다.")
            returnDestination("station_exit", "역 출입구")
        }

        findViewById<LinearLayout>(R.id.itemFareGate).setOnClickListener {
            TtsManager.speak("개찰구를 목적지로 설정하였습니다.")
            returnDestination("fare_gate", "개찰구")
        }

        findViewById<LinearLayout>(R.id.itemFloor1Stairs).setOnClickListener {
            TtsManager.speak("1층 계단을 목적지로 설정하였습니다.")
            returnDestination("floor1_stairs", "1층 계단")
        }

        findViewById<LinearLayout>(R.id.itemB1Stairs).setOnClickListener {
            TtsManager.speak("지하 계단을 목적지로 설정하였습니다.")
            returnDestination("b1_stairs", "지하 계단")
        }

        findViewById<LinearLayout>(R.id.itemB1Elevator).setOnClickListener {
            TtsManager.speak("지하 엘리베이터 앞을 목적지로 설정하였습니다.")
            returnDestination("b1_elevator", "지하 엘리베이터 앞")
        }

        findViewById<LinearLayout>(R.id.itemB1DownStairsFront).setOnClickListener {
            TtsManager.speak("하행 계단 앞을 목적지로 설정하였습니다.")
            returnDestination("b1_down_stairs_front", "하행 계단 앞")
        }

        findViewById<LinearLayout>(R.id.itemB1UpStairsFront).setOnClickListener {
            TtsManager.speak("상행 계단 앞을 목적지로 설정하였습니다.")
            returnDestination("b1_up_stairs_front", "상행 계단 앞")
        }

        findViewById<LinearLayout>(R.id.itemDownPlatform).setOnClickListener {
            TtsManager.speak("하행 승강장을 목적지로 설정하였습니다.")
            returnDestination("down_platform", "하행 승강장")
        }

        findViewById<LinearLayout>(R.id.itemUpPlatform).setOnClickListener {
            TtsManager.speak("상행 승강장을 목적지로 설정하였습니다.")
            returnDestination("up_platform", "상행 승강장")
        }
    }

    private fun returnDestination(nodeId: String, name: String) {
        val intent = Intent()
        intent.putExtra("destinationId", nodeId)
        intent.putExtra("destinationName", name)
        setResult(RESULT_OK, intent)
        finish()
    }
}
