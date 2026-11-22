package com.chronodisrupt.zoi

import android.service.voice.VoiceInteractionService

/**
 * Lightweight system entry point for Jane when the user selects her as
 * the device's default digital assistant. Heavy work belongs in the session.
 */
class JaneVoiceInteractionService : VoiceInteractionService()
