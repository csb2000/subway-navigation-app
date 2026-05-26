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

        TtsManager.speak("목적지 설정 화면입니다. 역 출입구, 하행 승강장, 상행 승강장 중 선택하세요.")

        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            TtsManager.speak("목적지 설정을 취소합니다.")
            finish()
        }

        findViewById<LinearLayout>(R.id.itemStationExit).setOnClickListener {
            TtsManager.speak("역 출입구를 목적지로 설정하였습니다.")
            returnDestination("station_exit", "역 출입구")
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