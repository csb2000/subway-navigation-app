package com.example.a260511

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TtsManager.init(this)
    }
}