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
                            if (serviceActive && !listeningForWakeWord) startWakeWordListening()
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            if (serviceActive && !listeningForWakeWord) startWakeWordListening()
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
        if (!serviceActive || listeningForWakeWord) return

        try {
            if (keywordSpotter == null) keywordSpotter = createKeywordSpotter()
            val spotter = keywordSpotter ?: return
            val stream = spotter.createStream()
            keywordStream = stream

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IllegalStateException("This device cannot initialize the microphone.")

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, SAMPLE_RATE / 5 * 2)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IllegalStateException("The microphone could not be initialized.")
            }

            audioRecord = recorder
            recorder.startRecording()
            listeningForWakeWord = true
            updateNotification("Listening locally for the Jane wake keyword")
            recordingThread = thread(name = "JaneWakeWord") {
                processWakeWordAudio(recorder, spotter, stream)
            }
        } catch (error: Exception) {
            listeningForWakeWord = false
            updateNotification("Wake-word setup failed: ${error.message ?: "unknown error"}")
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
                if (count <= 0) continue

                val samples = FloatArray(count) { shortBuffer[it] / 32768.0f }
                stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)

                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val keyword = spotter.getResult(stream).keyword
                    if (keyword.isNotBlank()) {
                        detected = true
                        listeningForWakeWord = false
                        break
                    }
                }
                if (detected) break
            }
        } catch (_: Exception) {
            // A stop request can interrupt AudioRecord.read(); cleanup below is expected.
        } finally {
            try { stream.release() } catch (_: Exception) {}
            if (keywordStream === stream) keywordStream = null
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (_: Exception) {}
            recorder.release()
            if (audioRecord === recorder) audioRecord = null
            recordingThread = null
        }

        if (detected && serviceActive) {
            mainHandler.post { beginCommandCapture() }
        } else if (serviceActive && listeningForWakeWord) {
            mainHandler.post { startWakeWordListening() }
        }
    }

    private fun beginCommandCapture() {
        if (!serviceActive) return
        updateNotification("Hi! Listening for your request…")

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speakAndResume("I heard you, but speech recognition isn't available on this phone.")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}

                    override fun onError(error: Int) {
                        stopSpeechRecognizer()
                        speakAndResume("I didn't catch that. Say the word Jane and try again.")
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        val phrase = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        stopSpeechRecognizer()
                        if (phrase.isNullOrBlank()) {
                            speakAndResume("I didn't catch that. Say the word Jane and try again.")
                        } else {
                            askJaneCore(phrase)
                        }
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
                recognizer.startListening(intent)
            }
        } catch (_: Exception) {
            stopSpeechRecognizer()
            speakAndResume("I couldn't start listening. Say the word Jane and try again.")
        }
    }

    private fun stopSpeechRecognizer() {
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
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
        if (ttsReady && textToSpeech != null) {
            textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "zoi-wake-response")
        } else {
            updateNotification("Response ready. Listening again shortly.")
            mainHandler.postDelayedSafe { startWakeWordListening() }
        }
    }

    private fun Handler.postDelayedSafe(action: () -> Unit) {
        postDelayed({ if (serviceActive && !listeningForWakeWord) action() }, 1200)
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
        private const val MODEL_DIR = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
        private const val API_BASE_URL = "https://zoi-core.onrender.com"

        private val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
