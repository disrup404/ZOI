package com.chronodisrupt.zoi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * User-enabled, on-device wake-word listener.
 *
 * Wake-word audio is processed locally by sherpa-onnx. Only after "Jane"
 * is detected does Android speech recognition capture the user's command and
 * send its transcript to Jane Core.
 */
class WakeWordService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var keywordSpotter: KeywordSpotter? = null
    @Volatile private var keywordStream: OnlineStream? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var recordingThread: Thread? = null
    @Volatile private var listeningForWakeWord = false
    @Volatile private var serviceActive = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var wakeRestartPending = false
    private var commandPartialTranscript: String? = null

    override fun onCreate() {
        super.onCreate()
        serviceActive = true
        createNotificationChannel()
        startAsForegroundService()
        textToSpeech = TextToSpeech(this) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) {
                textToSpeech?.language = Locale.getDefault()
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            if (serviceActive && !listeningForWakeWord) scheduleWakeWordRestart()
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            if (serviceActive && !listeningForWakeWord) scheduleWakeWordRestart()
                        }
                    }
                })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!listeningForWakeWord && speechRecognizer == null) {
            startWakeWordListening()
        }
        return START_STICKY
    }

    private fun startAsForegroundService() {
        val stopIntent = PendingIntent.getService(
            this,
            2403,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jane voice activation is on")
            .setContentText("Wake phrases are detected on this phone. Tap Turn off to stop.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stopIntent).build())
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Jane wake word",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Visible notification while Jane listens for its wake phrase."
                }
            )
        }
    }

    private fun updateNotification(message: String) {
        val stopIntent = PendingIntent.getService(
            this,
            2403,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jane voice assistant")
            .setContentText(message)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stopIntent).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun createKeywordSpotter(): KeywordSpotter {
        val modelDir = MODEL_DIR
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$modelDir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "$modelDir/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    joiner = "$modelDir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                ),
                tokens = "$modelDir/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer2"
            ),
            keywordsFile = "$modelDir/keywords.txt",
            keywordsScore = 1.0f,
            keywordsThreshold = 0.25f,
            numTrailingBlanks = 1
        )
        return KeywordSpotter(assetManager = assets, config = config)
    }

    private fun startWakeWordListening() {
        if (!serviceActive || listeningForWakeWord || recordingThread?.isAlive == true || speechRecognizer != null) return

        var stream: OnlineStream? = null
        var recorder: AudioRecord? = null
        try {
            if (keywordSpotter == null) keywordSpotter = createKeywordSpotter()
            val spotter = keywordSpotter ?: throw IllegalStateException("Wake-word model is unavailable.")
            stream = spotter.createStream()
            keywordStream = stream

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IllegalStateException("This device cannot initialize the microphone.")

            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, SAMPLE_RATE / 5 * 2)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("The microphone could not be initialized.")
            }

            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Android did not start microphone recording.")
            }

            audioRecord = recorder
            listeningForWakeWord = true
            updateNotification("Listening for Jane — microphone active")
            Log.i(TAG, "Wake-word microphone started; sampleRate=$SAMPLE_RATE, locale=${Locale.getDefault().toLanguageTag()}")
            val activeRecorder = recorder
            val activeStream = stream
            recordingThread = thread(name = "JaneWakeWord") {
                processWakeWordAudio(activeRecorder, spotter, activeStream)
            }
        } catch (error: Exception) {
            listeningForWakeWord = false
            Log.e(TAG, "Could not start wake-word microphone", error)
            try { recorder?.release() } catch (_: Exception) {}
            try { stream?.release() } catch (_: Exception) {}
            if (audioRecord === recorder) audioRecord = null
            if (keywordStream === stream) keywordStream = null
            updateNotification("Microphone unavailable — retrying")
            scheduleWakeWordRestart()
        }
    }

    private fun processWakeWordAudio(
        recorder: AudioRecord,
        spotter: KeywordSpotter,
        stream: OnlineStream
    ) {
        val samplesPerRead = SAMPLE_RATE / 10
        val shortBuffer = ShortArray(samplesPerRead)
        var detected = false

        try {
            while (listeningForWakeWord && serviceActive) {
                val count = recorder.read(shortBuffer, 0, shortBuffer.size)
                if (count < 0) {
                    Log.e(TAG, "AudioRecord.read failed with code=$count")
                    break
                }
                if (count == 0) continue

                val samples = FloatArray(count) { shortBuffer[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)

                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val keyword = spotter.getResult(stream).keyword
                    if (keyword.isNotBlank()) {
                        Log.i(TAG, "Wake keyword detected: $keyword")
                        detected = true
                        break
                    }
                }
                if (detected) break
            }
        } catch (error: Exception) {
            if (serviceActive) Log.e(TAG, "Wake-word audio loop failed", error)
        } finally {
            // Always clear this flag when the recorder thread exits. Otherwise a failed
            // read leaves it true and every attempted restart is rejected as "already listening".
            listeningForWakeWord = false
            try { stream.release() } catch (_: Exception) {}
            if (keywordStream === stream) keywordStream = null
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            if (audioRecord === recorder) audioRecord = null
            recordingThread = null
            Log.i(TAG, "Wake-word audio loop ended; detected=$detected, serviceActive=$serviceActive")
        }

        if (detected && serviceActive) {
            mainHandler.post { beginCommandCapture() }
        } else if (serviceActive) {
            updateNotification("Wake-word listener stopped — restarting")
            scheduleWakeWordRestart()
        }
    }

    private fun scheduleWakeWordRestart() {
        if (!serviceActive || wakeRestartPending) return
        wakeRestartPending = true
        mainHandler.postDelayed({
            wakeRestartPending = false
            if (serviceActive && !listeningForWakeWord && speechRecognizer == null) {
                Log.i(TAG, "Attempting wake-word listener recovery")
                startWakeWordListening()
            }
        }, WAKE_RESTART_DELAY_MS)
    }

    private fun beginCommandCapture() {
        if (!serviceActive) return
        commandPartialTranscript = null
        updateNotification("Jane heard you — listening for your request")
        Log.i(TAG, "Starting Android speech recognition; locale=${Locale.getDefault().toLanguageTag()}")

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition is not available on this device")
            speakAndResume("Speech recognition isn't available right now.")
            return
        }

        try {
            stopSpeechRecognizer()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        Log.i(TAG, "SpeechRecognizer ready; waiting for speech")
                        updateNotification("Listening to your request…")
                    }
                    override fun onBeginningOfSpeech() {
                        Log.i(TAG, "SpeechRecognizer detected speech")
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        Log.i(TAG, "SpeechRecognizer detected end of speech")
                    }
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                    override fun onPartialResults(partialResults: android.os.Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        if (!partial.isNullOrBlank()) {
                            commandPartialTranscript = partial
                            Log.i(TAG, "Partial speech transcript: $partial")
                        }
                    }

                    override fun onError(error: Int) {
                        val partial = commandPartialTranscript
                        Log.e(TAG, "SpeechRecognizer error=$error (${speechErrorName(error)}); partial=${partial.orEmpty()}")
                        stopSpeechRecognizer()
                        if (!partial.isNullOrBlank()) {
                            askJaneCore(partial)
                        } else {
                            speakAndResume(speechErrorMessage(error))
                        }
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        val alternatives = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val phrase = alternatives?.firstOrNull { it.isNotBlank() }?.trim()
                            ?: commandPartialTranscript
                        Log.i(TAG, "Final speech results: ${alternatives?.joinToString(" | ").orEmpty()}")
                        stopSpeechRecognizer()
                        if (phrase.isNullOrBlank()) {
                            speakAndResume("I couldn't hear a request. Please try again.")
                        } else {
                            askJaneCore(phrase)
                        }
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                recognizer.startListening(intent)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not start Android speech recognition", error)
            stopSpeechRecognizer()
            speakAndResume("I couldn't start listening to your request.")
        }
    }

    private fun speechErrorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        else -> "ERROR_$error"
    }

    private fun speechErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "I'm having trouble reaching the speech recognition service. Check your connection."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "I don't have microphone permission. Please allow microphone access for Jane."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "The speech recognizer is busy. Give me a moment and try again."
        SpeechRecognizer.ERROR_AUDIO ->
            "I'm having trouble accessing the microphone. Please try again."
        SpeechRecognizer.ERROR_SERVER ->
            "The speech recognition service encountered an error. Please try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH ->
            "I couldn't hear a request. Please try again."
        else ->
            "Speech recognition failed. Please try again."
    }

    private fun stopSpeechRecognizer() {
        val recognizer = speechRecognizer
        speechRecognizer = null
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
    }

    private fun askJaneCore(message: String) {
        updateNotification("Thinking about your request…")
        val payload = JSONObject()
            .put("message", message)
            .put("history", JSONArray())
        val body = payload.toString().toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )
        val request = Request.Builder()
            .url("$API_BASE_URL/v1/chat")
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    if (serviceActive) speakAndResume("I couldn't reach Jane Core. Check your internet connection and try again.")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string().orEmpty()
                response.close()
                mainHandler.post {
                    if (!serviceActive) return@post
                    val reply = if (response.isSuccessful) {
                        try { JSONObject(responseBody).optString("reply") } catch (_: Exception) { "" }
                    } else ""
                    if (reply.isBlank()) {
                        speakAndResume("I couldn't get a response just now. Say the word Jane to try again.")
                    } else {
                        speakAndResume(reply)
                    }
                }
            }
        })
    }

    private fun speakAndResume(message: String) {
        updateNotification("Jane is responding…")
        Log.i(TAG, "Speaking response; ttsReady=$ttsReady")
        if (ttsReady && textToSpeech != null) {
            val result = textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, WAKE_RESPONSE_UTTERANCE)
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TextToSpeech rejected the response; scheduling listener recovery")
                scheduleWakeWordRestart()
            }
        } else {
            Log.w(TAG, "TextToSpeech is unavailable; resuming wake-word listener without speech")
            scheduleWakeWordRestart()
        }
    }

    private fun stopWakeWordCapture() {
        listeningForWakeWord = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        recordingThread?.interrupt()
    }

    override fun onDestroy() {
        serviceActive = false
        stopWakeWordCapture()
        stopSpeechRecognizer()
        try { keywordStream?.release() } catch (_: Exception) {}
        keywordStream = null
        try { keywordSpotter?.release() } catch (_: Exception) {}
        keywordSpotter = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.chronodisrupt.zoi.action.STOP_WAKE_WORD"
        private const val CHANNEL_ID = "zoi_wake_word"
        private const val NOTIFICATION_ID = 2402
        private const val SAMPLE_RATE = 16000
        private const val WAKE_RESTART_DELAY_MS = 1800L
        private const val WAKE_RESPONSE_UTTERANCE = "jane-wake-response"
        private const val TAG = "JaneVoiceEngine"
        private const val MODEL_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
        private const val API_BASE_URL = "https://zoi-core.onrender.com"

        private val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
