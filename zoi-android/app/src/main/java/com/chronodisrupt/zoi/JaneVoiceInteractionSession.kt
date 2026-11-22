package com.chronodisrupt.zoi

import android.content.Intent
import android.graphics.Color
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class JaneVoiceInteractionSession(context: android.content.Context) :
    VoiceInteractionSession(context) {

    override fun onCreateContentView(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 32, 40, 32)
            setBackgroundColor(Color.rgb(11, 16, 24))
        }

        val title = TextView(context).apply {
            text = "JANE"
            textSize = 25f
            letterSpacing = 0.28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val description = TextView(context).apply {
            text = "Your assistant is ready."
            textSize = 14f
            setTextColor(Color.rgb(170, 184, 201))
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 24)
        }
        val openButton = TextView(context).apply {
            text = "OPEN JANE"
            textSize = 14f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(20, 17, 14))
            setPadding(36, 20, 36, 20)
            setBackgroundColor(Color.rgb(255, 168, 93))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val launch = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startVoiceActivity(launch)
                hide()
            }
        }
        container.addView(title)
        container.addView(description)
        container.addView(openButton)
        return container
    }
}
