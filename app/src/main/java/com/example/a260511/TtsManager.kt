package com.example.a260511

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

object TtsManager {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                ready = true
                // 초기화 전에 요청된 말이 있으면 처리
                pending.forEach { speak(it) }
                pending.clear()
            }
        }
    }

    // 기존 음성 중단 후 새 음성 재생 (사양 0번)
    fun speak(text: String) {
        if (ready) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "id_${System.currentTimeMillis()}")
        } else {
            pending.add(text)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}