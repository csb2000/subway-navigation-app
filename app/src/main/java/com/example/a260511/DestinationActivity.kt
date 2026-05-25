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

        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<LinearLayout>(R.id.itemStationExit).setOnClickListener {
            returnDestination("station_exit", "역 출입구")
        }

        findViewById<LinearLayout>(R.id.itemDownPlatform).setOnClickListener {
            returnDestination("down_platform", "하행 승강장")
        }

        findViewById<LinearLayout>(R.id.itemUpPlatform).setOnClickListener {
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