package com.chronodisrupt.zoi

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

private data class ChatMessage(val role: String, val content: String)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textToSpeech = TextToSpeech(this, this)
        setContent {
            MaterialTheme {
                ZoiChatScreen(
                    onSpeak = { speak(it) },
                    onListen = { onText, onError -> startListening(onText, onError) },
                    onStopListening = { speechRecognizer?.stopListening() }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) textToSpeech?.language = Locale.getDefault()
    }

    private fun speak(text: String) {
        if (ttsReady) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zoi-response")
        }
    }

    private fun startListening(onText: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onError("Speech recognition isn't available on this phone.")
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onError(error: Int) {
                    onError("I couldn't hear that clearly. Please try again.")
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spoken = matches?.firstOrNull()
                    if (!spoken.isNullOrBlank()) onText(spoken)
                    else onError("I didn't catch that. Try again.")
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer.startListening(intent)
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun ZoiChatScreen(
    onSpeak: (String) -> Unit,
    onListen: ((String) -> Unit, (String) -> Unit) -> Unit,
    onStopListening: () -> Unit
) {
    val activity = LocalContext.current as MainActivity
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("READY WHEN YOU ARE") }
    var errorText by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            listening = true
            status = "LISTENING"
            onListen(
                { spoken ->
                    input = spoken
                    listening = false
                    status = "READY WHEN YOU ARE"
                },
                { error ->
                    errorText = error
                    listening = false
                    status = "READY WHEN YOU ARE"
                }
            )
        } else {
            errorText = "Microphone permission is needed for voice input."
        }
    }

    DisposableEffect(Unit) {
        onDispose { onStopListening() }
    }

    fun sendMessage() {
        val text = input.trim()
        if (text.isEmpty() || busy) return

        errorText = ""
        messages.add(ChatMessage("user", text))
        input = ""
        busy = true
        status = "THINKING"

        val history = JSONArray()
        messages.dropLast(1).takeLast(12).forEach { item ->
            history.put(JSONObject().put("role", item.role).put("content", item.content))
        }
        val payload = JSONObject().put("message", text).put("history", history)
        val requestBody = payload.toString().toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )
        val request = Request.Builder()
            .url("$API_BASE_URL/v1/chat")
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post {
                    busy = false
                    status = "READY WHEN YOU ARE"
                    errorText = "I couldn't reach Zoi Core. Check your connection and try again."
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string().orEmpty()
                mainHandler.post {
                    busy = false
                    status = "READY WHEN YOU ARE"
                    if (!response.isSuccessful) {
                        errorText = try {
                            JSONObject(responseText).optString("detail", "The request failed.")
                        } catch (_: Exception) {
                            "The request failed. HTTP " + response.code
                        }
                    } else {
                        try {
                            val reply = JSONObject(responseText).getString("reply")
                            messages.add(ChatMessage("assistant", reply))
                            onSpeak(reply)
                        } catch (_: Exception) {
                            errorText = "Zoi returned an unreadable response. Please try again."
                        }
                    }
                }
                response.close()
            }
        })
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0D12)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ZOI", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                Spacer(Modifier.weight(1f))
                Text(status, color = Color(0xFF62D9FF), fontSize = 9.sp, letterSpacing = 1.5.sp)
            }

            Spacer(Modifier.height(18.dp))

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(76.dp).background(Color(0xFF172331), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                        Box(Modifier.size(12.dp).background(Color(0xFF62D9FF), CircleShape))
                        Box(Modifier.size(12.dp).background(Color(0xFF62D9FF), CircleShape))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                if (busy) "Let me think." else if (listening) "I'm listening." else "I'm here.",
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Talk to me, or type whatever is on your mind.",
                            color = Color(0xFF7D8797),
                            fontSize = 14.sp
                        )
                    }
                }
                items(messages) { message ->
                    val isUser = message.role == "user"
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                    ) {
                        Text(
                            if (isUser) "YOU" else "ZOI",
                            color = if (isUser) Color(0xFF8B9AAD) else Color(0xFF62D9FF),
                            fontSize = 9.sp,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = if (isUser) Color(0xFF1B2938) else Color(0xFF171C26),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                message.content,
                                modifier = Modifier.padding(13.dp),
                                color = Color(0xFFE8EDF5),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                if (busy) {
                    item { Text("Zoi is thinking…", color = Color(0xFF62D9FF), fontSize = 12.sp) }
                }
            }

            if (errorText.isNotBlank()) {
                Text(errorText, color = Color(0xFFFF8B8B), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Zoi…") },
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendMessage() })
                )
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    enabled = !busy,
                    modifier = Modifier.size(52.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (listening) Color(0xFF235C51) else Color(0xFF202B38)
                    )
                ) {
                    Text(if (listening) "…" else "MIC", fontSize = 10.sp)
                }
                Button(
                    onClick = { sendMessage() },
                    enabled = !busy && input.isNotBlank(),
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF62D9FF),
                        contentColor = Color(0xFF081018)
                    )
                ) {
                    Text("SEND", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "VOICE INPUT USES YOUR PHONE'S SPEECH SERVICE",
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF596474),
                fontSize = 8.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

private const val API_BASE_URL = "https://zoi-core.onrender.com"

private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .build()

private val mainHandler = Handler(Looper.getMainLooper())
