package com.chronodisrupt.zoi

import android.Manifest
import android.content.Intent
import android.net.Uri
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
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
                    onError("I couldn't hear that clearly. Tap the microphone and try again.")
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
    val context = LocalContext.current
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("SYSTEM ONLINE") }
    var errorText by remember { mutableStateOf("") }

    fun sendText(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || busy) return

        errorText = ""
        messages.add(ChatMessage("user", text))
        input = ""

        // Run supported app-launch requests on Android itself. The AI model
        // never generates arbitrary intents or commands.
        val appCommand = matchAppLaunchCommand(text)
        if (appCommand != null) {
            val (appName, packageName) = appCommand
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                try {
                    context.startActivity(launchIntent)
                    val reply = "Opening $appName."
                    messages.add(ChatMessage("assistant", reply))
                    onSpeak(reply)
                } catch (_: Exception) {
                    val reply = "I couldn't open $appName. Please try opening it manually."
                    messages.add(ChatMessage("assistant", reply))
                    onSpeak(reply)
                }
            } else {
                val reply = "$appName doesn't appear to be installed on this phone."
                messages.add(ChatMessage("assistant", reply))
                onSpeak(reply)
            }
            status = "SYSTEM ONLINE"
            return
        }

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
                    status = "SYSTEM ONLINE"
                    errorText = "I couldn't reach Zoi Core. Check your connection and try again."
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string().orEmpty()
                mainHandler.post {
                    busy = false
                    status = "SYSTEM ONLINE"
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            listening = true
            status = "LISTENING"
            errorText = ""
            onListen(
                { spoken ->
                    listening = false
                    status = "THINKING"
                    sendText(spoken)
                },
                { error ->
                    listening = false
                    status = "SYSTEM ONLINE"
                    errorText = error
                }
            )
        } else {
            errorText = "Microphone permission is needed for voice input."
        }
    }

    DisposableEffect(Unit) {
        onDispose { onStopListening() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF090D13)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ZOI", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
                    Text("PERSONAL COMPANION", color = Color(0xFF718397), fontSize = 8.sp, letterSpacing = 2.sp)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.size(6.dp).background(if (busy) Color(0xFFFFB45B) else Color(0xFF5DE0BD), CircleShape))
                    Text(status, color = Color(0xFF91A4B8), fontSize = 9.sp, letterSpacing = 1.sp)
                }
            }

            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(206.dp),
                contentAlignment = Alignment.Center
            ) {
                RobotCompanion(listening = listening, thinking = busy)
            }

            Text(
                when {
                    listening -> "I'm listening."
                    busy -> "Give me a second."
                    else -> "What are we doing today?"
                },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFEAF2FA),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                when {
                    listening -> "Speak naturally. I'll send it when you're done."
                    busy -> "Zoi is working on your request."
                    else -> "Talk to me, or type a message below."
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = Color(0xFF8190A1),
                fontSize = 12.sp
            )

            Spacer(Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Your conversations will appear here.",
                            color = Color(0xFF66778A),
                            fontSize = 13.sp
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
                            color = if (isUser) Color(0xFF91A6BC) else Color(0xFFFFB36A),
                            fontSize = 9.sp,
                            letterSpacing = 1.6.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(5.dp))
                        Surface(
                            color = if (isUser) Color(0xFF182638) else Color(0xFF141C27),
                            shape = RoundedCornerShape(
                                topStart = 15.dp,
                                topEnd = 15.dp,
                                bottomStart = if (isUser) 15.dp else 4.dp,
                                bottomEnd = if (isUser) 4.dp else 15.dp
                            )
                        ) {
                            Text(
                                text = formatMessage(message.content),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                color = Color(0xFFE7EDF5),
                                fontSize = 14.sp,
                                lineHeight = 22.sp
                            )
                        }
                    }
                }
                if (busy) {
                    item {
                        Text("Zoi is thinking…", color = Color(0xFFFFB36A), fontSize = 12.sp)
                    }
                }
            }

            if (errorText.isNotBlank()) {
                Text(errorText, color = Color(0xFFFF8989), fontSize = 12.sp)
                Spacer(Modifier.height(7.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF151E2A))
                    .padding(7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).padding(start = 10.dp, top = 11.dp, bottom = 11.dp),
                    enabled = !busy && !listening,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color(0xFFF4F7FB),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = Brush.verticalGradient(
                        listOf(Color(0xFFFFB36A), Color(0xFFFF7B52))
                    ),
                    decorationBox = { innerTextField ->
                        Box {
                            if (input.isEmpty()) {
                                Text("Message Zoi…", color = Color(0xFF8292A5), fontSize = 14.sp)
                            }
                            innerTextField()
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendText(input) })
                )

                Button(
                    onClick = {
                        if (listening) {
                            onStopListening()
                            listening = false
                            status = "SYSTEM ONLINE"
                        } else {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (listening) Color(0xFF9B493A) else Color(0xFF26384B)
                    )
                ) {
                    MicrophoneIcon(active = listening)
                }

                Button(
                    onClick = { sendText(input) },
                    enabled = !busy && input.isNotBlank() && !listening,
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFA85D),
                        contentColor = Color(0xFF17110D),
                        disabledContainerColor = Color(0xFF35404D),
                        disabledContentColor = Color(0xFF8290A0)
                    )
                ) {
                    Text("SEND", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                if (listening) "TAP THE MIC AGAIN TO CANCEL" else "VOICE IS SENT AUTOMATICALLY WHEN YOU FINISH SPEAKING",
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF59697B),
                fontSize = 8.sp,
                letterSpacing = 0.8.sp
            )
        }
    }
}

@Composable
private fun RobotCompanion(listening: Boolean, thinking: Boolean) {
    val transition = rememberInfiniteTransition(label = "zoi-robot")
    val floatY by transition.animateFloat(
        initialValue = -3f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "robot-float"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (listening) 450 else 1100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "robot-pulse"
    )

    Canvas(
        modifier = Modifier.size(width = 190.dp, height = 198.dp)
    ) {
        val sx = size.width / 190f
        val sy = size.height / 198f
        fun p(x: Float, y: Float) = Offset(x * sx, (y + floatY) * sy)

        // Soft floor glow
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x4446BFEA), Color.Transparent),
                center = p(95f, 174f),
                radius = 75f * sx
            ),
            topLeft = p(30f, 146f),
            size = Size(130f * sx, 42f * sy)
        )

        // Small antenna and glowing tip
        drawLine(Color(0xFF566879), p(95f, 20f), p(95f, 34f), strokeWidth = 5f * sx, cap = StrokeCap.Round)
        drawCircle(Color(0xFFFFA85D), 7f * sx * pulse, p(95f, 16f))
        drawCircle(Color(0x66FF9A4E), 12f * sx * pulse, p(95f, 16f), style = Stroke(2f * sx))

        // Side fins / ears
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFFFB66C), Color(0xFFB84E39))),
            topLeft = p(25f, 69f),
            size = Size(23f * sx, 48f * sy),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(9f * sx, 9f * sy)
        )
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFFFB66C), Color(0xFFB84E39))),
            topLeft = p(142f, 69f),
            size = Size(23f * sx, 48f * sy),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(9f * sx, 9f * sy)
        )
        drawCircle(Color(0xFF263747), 4f * sx, p(36f, 93f))
        drawCircle(Color(0xFF263747), 4f * sx, p(154f, 93f))

        // Compact armored head
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFFC27A), Color(0xFFE77C4C), Color(0xFF9F3F35)),
                start = p(48f, 32f),
                end = p(142f, 140f)
            ),
            topLeft = p(39f, 34f),
            size = Size(112f * sx, 106f * sy),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(30f * sx, 30f * sy)
        )
        // Dark face plate
        drawRoundRect(
            color = Color(0xFF14212D),
            topLeft = p(49f, 57f),
            size = Size(92f * sx, 61f * sy),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(19f * sx, 19f * sy)
        )
        // Face glass highlight
        drawRoundRect(
            color = Color(0x223FBBE8),
            topLeft = p(55f, 62f),
            size = Size(80f * sx, 8f * sy),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * sx, 5f * sy)
        )

        // Animated eyes
        val eyeScale = if (listening) pulse else 1f
        drawRoundRect(
            color = Color(0xFF69E4FF),
            topLeft = p(66f, 77f),
            size = Size(17f * sx, 19f * sy * eyeScale),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f * sx, 7f * sy)
        )
        drawRoundRect(
            color = Color(0xFF69E4FF),
            topLeft = p(107f, 77f),
            size = Size(17f * sx, 19f * sy * eyeScale),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f * sx, 7f * sy)
        )
        drawCircle(Color(0x8874E8FF), 15f * sx, p(74f, 86f), style = Stroke(1.5f * sx))
        drawCircle(Color(0x8874E8FF), 15f * sx, p(115f, 86f), style = Stroke(1.5f * sx))

        // Mouth / voice indicator
        if (thinking) {
            rotate(degrees = (pulse - 1f) * 55f, pivot = p(95f, 105f)) {
                drawLine(Color(0xFFFFB66C), p(88f, 105f), p(102f, 105f), strokeWidth = 3f * sy, cap = StrokeCap.Round)
            }
        } else {
            drawRoundRect(
                color = Color(0xFFFFB66C),
                topLeft = p(88f, 104f),
                size = Size(14f * sx, (if (listening) 8f * pulse else 4f) * sy),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * sx, 4f * sy)
            )
        }

        // Chin plate
        drawRoundRect(
            color = Color(0xFF8E3E35),
            topLeft = p(72f, 126f),
            size = Size(46f * sx, 10f * sy),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f * sx, 5f * sy)
        )
        drawCircle(Color(0xFFFFC27A), 2.5f * sx, p(95f, 131f))

        // Tiny torso and shoulders
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF394D5E), Color(0xFF182633))),
            topLeft = p(59f, 143f),
            size = Size(72f * sx, 42f * sy),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(17f * sx, 17f * sy)
        )
        drawRoundRect(
            color = Color(0xFFFFA85D),
            topLeft = p(81f, 149f),
            size = Size(28f * sx, 5f * sy),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * sx, 3f * sy)
        )
        drawCircle(Color(0xFF67DDF4), 5f * sx, p(95f, 165f))
        drawCircle(Color(0x5567DDF4), 9f * sx, p(95f, 165f), style = Stroke(1.5f * sx))
    }
}

@Composable
private fun MicrophoneIcon(active: Boolean) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val c = if (active) Color.White else Color(0xFFEAF2FA)
        val cx = size.width / 2f
        drawRoundRect(
            color = c,
            topLeft = Offset(cx - size.width * 0.17f, size.height * 0.08f),
            size = Size(size.width * 0.34f, size.height * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.18f)
        )
        drawArc(
            color = c,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.22f, size.height * 0.28f),
            size = Size(size.width * 0.56f, size.height * 0.52f),
            style = Stroke(width = size.width * 0.09f, cap = StrokeCap.Round)
        )
        drawLine(
            color = c,
            start = Offset(cx, size.height * 0.79f),
            end = Offset(cx, size.height * 0.94f),
            strokeWidth = size.width * 0.09f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = c,
            start = Offset(size.width * 0.34f, size.height * 0.94f),
            end = Offset(size.width * 0.66f, size.height * 0.94f),
            strokeWidth = size.width * 0.09f,
            cap = StrokeCap.Round
        )
    }
}

private fun formatMessage(message: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = message.split("\n")
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd()
            val headingLevel = when {
                line.startsWith("### ") -> 3
                line.startsWith("## ") -> 2
                line.startsWith("# ") -> 1
                else -> 0
            }
            val cleanLine = when (headingLevel) {
                3 -> line.removePrefix("### ")
                2 -> line.removePrefix("## ")
                1 -> line.removePrefix("# ")
                else -> line
            }
            val bullet = cleanLine.startsWith("- ") || cleanLine.startsWith("* ")
            val numbered = cleanLine.matches(Regex("^\\d+[.)]\\s+.*"))
            val displayLine = when {
                bullet -> "• " + cleanLine.drop(2)
                else -> cleanLine
            }

            if (headingLevel > 0) {
                withStyle(
                    SpanStyle(
                        color = Color(0xFFFFC080),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                ) {
                    append(displayLine)
                }
            } else if (numbered) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFEAF2FA))) {
                    append(displayLine)
                }
            } else {
                appendInlineMarkdown(displayLine)
            }
            if (index != lines.lastIndex) append("\n")
        }
    }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(line: String) {
    val pattern = Regex("(\\*\\*.+?\\*\\*|`[^`]+`)")
    var cursor = 0
    pattern.findAll(line).forEach { match ->
        append(line.substring(cursor, match.range.first))
        val token = match.value
        if (token.startsWith("**")) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFFFD09B))) {
                append(token.removePrefix("**").removeSuffix("**"))
            }
        } else {
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF8FE3F4),
                    background = Color(0xFF253443)
                )
            ) {
                append(token.removeSurrounding("`"))
            }
        }
        cursor = match.range.last + 1
    }
    append(line.substring(cursor))
}


/**
 * Allowlisted app launcher. Only recognised app names map to package IDs;
 * arbitrary model output is never executed as a device command.
 */
private fun matchAppLaunchCommand(message: String): Pair<String, String>? {
    val pattern = Regex(
        """(?i)^\\s*(?:(?:hey\\s+)?zoi[, ]*)?(?:please\\s+)?(?:can you\\s+|could you\\s+)?(?:open|launch|start)\\s+(?:the\\s+)?(.+?)\\s*[?.!]*$"""
    )
    val match = pattern.matchEntire(message.trim()) ?: return null
    val target = match.groupValues[1]
        .replace(Regex("""(?i)\\s+for me$"""), "")
        .trim()
        .lowercase(Locale.ROOT)

    val apps = mapOf(
        "youtube" to ("YouTube" to "com.google.android.youtube"),
        "youtube app" to ("YouTube" to "com.google.android.youtube"),
        "whatsapp" to ("WhatsApp" to "com.whatsapp"),
        "whats app" to ("WhatsApp" to "com.whatsapp"),
        "chrome" to ("Chrome" to "com.android.chrome"),
        "google chrome" to ("Chrome" to "com.android.chrome"),
        "google maps" to ("Google Maps" to "com.google.android.apps.maps"),
        "maps" to ("Google Maps" to "com.google.android.apps.maps"),
        "gmail" to ("Gmail" to "com.google.android.gm"),
        "spotify" to ("Spotify" to "com.spotify.music")
    )
    return apps[target]
}

private const val API_BASE_URL = "https://zoi-core.onrender.com"

private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .build()

private val mainHandler = Handler(Looper.getMainLooper())
