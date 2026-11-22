package com.chronodisrupt.zoi

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.AlarmClock
import android.provider.OpenableColumns
import android.widget.Toast
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.provider.Settings
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
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
        // Wake the Render backend while the user is opening Jane, rather than
        // making the first chat message pay the cold-start cost.
        warmBackend()
        setContent {
            MaterialTheme {
                JaneAppRoot(
                    onSpeak = { speak(it) },
                    onListen = { onText, onError -> startListening(onText, onError) },
                    onStopListening = { speechRecognizer?.stopListening() },
                    onStopSpeaking = { textToSpeech?.stop() }
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
private fun JaneAppRoot(
    onSpeak: (String) -> Unit,
    onListen: ((String) -> Unit, (String) -> Unit) -> Unit,
    onStopListening: () -> Unit,
    onStopSpeaking: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("jane_preferences", android.content.Context.MODE_PRIVATE)
    }
    var introCompleted by remember {
        mutableStateOf(preferences.getBoolean("intro_completed", false))
    }

    if (introCompleted) {
        JaneChatScreen(
            onSpeak = onSpeak,
            onListen = onListen,
            onStopListening = onStopListening,
            onStopSpeaking = onStopSpeaking
        )
    } else {
        JaneWelcomeScreen(
            onContinue = {
                preferences.edit().putBoolean("intro_completed", true).apply()
                introCompleted = true
            }
        )
    }
}

@Composable
private fun JaneWelcomeScreen(onContinue: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF080D14)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "JANE",
                color = Color.White,
                fontSize = 31.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 8.sp
            )
            Text(
                "PERSONAL AI ASSISTANT",
                color = Color(0xFF8296AA),
                fontSize = 9.sp,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(30.dp))
            RobotCompanion(listening = false, thinking = false)
            Spacer(Modifier.height(22.dp))
            Text(
                "Hello, I'm Jane.",
                color = Color(0xFFF3F6FA),
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Your personal AI assistant.",
                color = Color(0xFFFFB36A),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "I'm here to help you find answers, think through ideas and get things done. Talk to me naturally or send a message whenever you need me.",
                color = Color(0xFF9BAABD),
                fontSize = 14.sp,
                lineHeight = 22.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(34.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFA85D),
                    contentColor = Color(0xFF17110D)
                )
            ) {
                Text(
                    "LET'S GET STARTED",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "READY WHEN YOU ARE",
                color = Color(0xFF536579),
                fontSize = 9.sp,
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
private fun JaneChatScreen(
    onSpeak: (String) -> Unit,
    onListen: ((String) -> Unit, (String) -> Unit) -> Unit,
    onStopListening: () -> Unit,
    onStopSpeaking: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("jane_preferences", android.content.Context.MODE_PRIVATE)
    }
    var notificationAccess by remember {
        mutableStateOf(JaneNotificationListener.hasNotificationAccess(context))
    }
    var notificationContextEnabled by remember {
        mutableStateOf(preferences.getBoolean("notification_context_enabled", false))
    }
    val notificationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        notificationAccess = JaneNotificationListener.hasNotificationAccess(context)
        if (!notificationAccess) notificationContextEnabled = false
    }
    var wakeWordActive by remember { mutableStateOf(false) }
    var overlayActive by remember { mutableStateOf(false) }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            context.startForegroundService(Intent(context, FloatingRobotService::class.java))
            overlayActive = true
        }
    }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var dialAfterContactPick by remember { mutableStateOf(false) }
    var smsAfterContactPickMessage by remember { mutableStateOf<String?>(null) }
    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val contactUri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && contactUri != null) {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selected = try {
                context.contentResolver.query(contactUri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0).orEmpty()
                        val number = cursor.getString(1).orEmpty()
                        name to number
                    } else null
                }
            } catch (_: Exception) { null }
            if (selected != null) {
                val (name, number) = selected
                val smsMessage = smsAfterContactPickMessage
                if (smsMessage != null && number.isNotBlank()) {
                    try {
                        val smsIntent = Intent(
                            Intent.ACTION_SENDTO,
                            Uri.parse("smsto:${Uri.encode(number)}")
                        ).putExtra("sms_body", smsMessage)
                        context.startActivity(smsIntent)
                        messages.add(ChatMessage("assistant", "I've prepared the message for $name. Review it and tap Send."))
                    } catch (_: Exception) {
                        messages.add(ChatMessage("assistant", "I found $name, but couldn't open the SMS composer."))
                    }
                } else if (dialAfterContactPick && number.isNotBlank()) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
                        messages.add(ChatMessage("assistant", "Opening the dialler for $name. You decide whether to place the call."))
                    } catch (_: Exception) {
                        messages.add(ChatMessage("assistant", "I found $name, but couldn't open the dialler."))
                    }
                } else {
                    messages.add(ChatMessage("assistant", if (number.isBlank()) "Selected $name, but no phone number was available." else "Selected $name: $number"))
                }
            } else {
                messages.add(ChatMessage("assistant", "I couldn't read that contact. Try selecting it again."))
            }
        }
        dialAfterContactPick = false
        smsAfterContactPickMessage = null
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val fileUri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && fileUri != null) {
            val displayName = try {
                context.contentResolver.query(fileUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (_: Exception) { null }
            val label = displayName ?: "unnamed file"
            try {
                val mimeType = context.contentResolver.getType(fileUri) ?: "*/*"
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(viewIntent)
                messages.add(ChatMessage("assistant", "Opening $label in a compatible app. Android only shared the file you selected."))
            } catch (_: Exception) {
                messages.add(ChatMessage("assistant", "I selected $label, but no compatible app could open it."))
            }
        }
    }
    var input by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf("chat") }
    var modeMenuExpanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("SYSTEM ONLINE") }
    var errorText by remember { mutableStateOf("") }

    fun executeDeviceAction(action: JSONObject): String {
        val args = action.optJSONObject("arguments") ?: JSONObject()
        return when (action.optString("name")) {
            "open_app" -> {
                val appName = args.optString("app_name").trim()
                when (val lookup = findLaunchableApp(context.packageManager, appName)) {
                    is AppLaunchLookup.Found -> {
                        val app = lookup.app
                        try {
                            val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                            if (launchIntent == null) "I found ${app.label}, but Android didn't provide a launch action for it."
                            else {
                                context.startActivity(launchIntent)
                                "Opening ${app.label}."
                            }
                        } catch (_: Exception) {
                            "I couldn't open ${app.label}. Android blocked the launch."
                        }
                    }
                    is AppLaunchLookup.Ambiguous -> "I found more than one match: ${lookup.apps.joinToString(", ") { it.label }}. Which one did you mean?"
                    AppLaunchLookup.NotFound -> "I couldn't find an installed app called \"$appName\"."
                }
            }
            "open_contacts" -> {
                dialAfterContactPick = false
                contactPicker.launch(Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE))
                "Choose a contact. I’ll only receive the details you select."
            }
            "dial_contact" -> {
                dialAfterContactPick = true
                contactPicker.launch(Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE))
                "Choose the contact. I’ll open the dialler with their number; you still place the call."
            }
            "open_file" -> {
                filePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                })
                "Choose a file. Android will grant access only to the file you select."
            }
            "set_alarm" -> {
                val hour = args.optInt("hour", -1)
                val minute = args.optInt("minute", -1)
                if (hour !in 0..23 || minute !in 0..59) {
                    "I couldn't use that alarm time. Tell me the time again."
                } else {
                    try {
                        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, hour)
                            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                            .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", "Jane alarm"))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        context.startActivity(intent)
                        "Opening Clock to set the alarm. Confirm it there; I won't pretend it's saved before Android says so."
                    } catch (_: Exception) {
                        "I couldn't find a Clock app that accepts alarm requests."
                    }
                }
            }
            "set_timer" -> {
                val seconds = args.optInt("seconds", -1)
                if (seconds !in 1..86400) {
                    "That timer duration isn't valid. Try a duration between one second and 24 hours."
                } else {
                    try {
                        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                            .putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label", ""))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        context.startActivity(intent)
                        "Opening Clock to set a $seconds-second timer. Confirm it there."
                    } catch (_: Exception) {
                        "I couldn't find a Clock app that accepts timer requests."
                    }
                }
            }
            "compose_sms_to_contact" -> {
                val message = args.optString("message").trim()
                if (message.isBlank()) {
                    "I need the message before I can prepare the text."
                } else {
                    smsAfterContactPickMessage = message
                    dialAfterContactPick = false
                    contactPicker.launch(Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE))
                    "Choose the recipient. I'll prepare the message in your SMS app; you decide whether to send it."
                }
            }
            "compose_sms" -> {
                val phoneNumber = args.optString("phone_number").trim()
                val message = args.optString("message").trim()
                if (phoneNumber.length < 3 || message.isBlank()) {
                    "I need a valid phone number and message before opening the SMS composer."
                } else {
                    try {
                        val intent = Intent(
                            Intent.ACTION_SENDTO,
                            Uri.parse("smsto:${Uri.encode(phoneNumber)}")
                        ).putExtra("sms_body", message)
                        context.startActivity(intent)
                        "I've prepared the text in your messaging app. Review it and tap Send when you're ready."
                    } catch (_: Exception) {
                        "I couldn't open an SMS app on this phone."
                    }
                }
            }
            "compose_email" -> {
                val email = args.optString("email").trim()
                val subject = args.optString("subject")
                val body = args.optString("body")
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() || body.isBlank()) {
                    "I need a valid email address and message before opening the email composer."
                } else {
                    try {
                        val intent = Intent(
                            Intent.ACTION_SENDTO,
                            Uri.parse("mailto:${Uri.encode(email)}")
                        ).apply {
                            putExtra(Intent.EXTRA_SUBJECT, subject)
                            putExtra(Intent.EXTRA_TEXT, body)
                        }
                        context.startActivity(intent)
                        "I've prepared the email. Review it and send it yourself."
                    } catch (_: Exception) {
                        "I couldn't open an email app on this phone."
                    }
                }
            }
            "create_calendar_event" -> {
                val title = args.optString("title").trim()
                if (title.isBlank()) {
                    "I need a title before I can open a calendar event."
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_INSERT)
                            .setData(CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.Events.TITLE, title)
                        val startAt = args.optString("start_at")
                        val endAt = args.optString("end_at")
                        if (startAt.isNotBlank()) {
                            val startMillis = java.time.LocalDateTime.parse(startAt)
                                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                            if (endAt.isNotBlank()) {
                                val endMillis = java.time.LocalDateTime.parse(endAt)
                                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                                intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
                            }
                        }
                        if (args.has("location")) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, args.optString("location"))
                        if (args.has("description")) intent.putExtra(CalendarContract.Events.DESCRIPTION, args.optString("description"))
                        context.startActivity(intent)
                        "Opening your calendar with “$title” prefilled. Review the details and save it there."
                    } catch (_: Exception) {
                        "I couldn't open the calendar event editor. Check the date and time and try again."
                    }
                }
            }
            else -> "I understood the request, but that device action isn't supported yet."
        }
    }

    fun sendText(rawText: String) {
        val text = rawText.trim()
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
        val payload = JSONObject()
            .put("message", text)
            .put("history", history)
            .put("mode", selectedMode)
            .put("device_local_datetime", java.time.ZonedDateTime.now().toString())
            .put("device_timezone", java.time.ZoneId.systemDefault().id)

        if (notificationContextEnabled && JaneNotificationListener.hasNotificationAccess(context)) {
            val notificationContext = JSONArray()
            JaneNotificationListener.recentNotifications().forEach { item ->
                notificationContext.put(
                    JSONObject()
                        .put("app", item.app)
                        .put("package", item.packageName)
                        .put("title", item.title)
                        .put("text", item.text)
                        .put("posted_at", item.postedAt)
                )
            }
            payload.put("notification_context", notificationContext)
        }
        val requestBody = payload.toString().toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )
        val request = Request.Builder()
            .url("$API_BASE_URL/v1/chat")
            .post(requestBody)
            .build()

        val requestStartedAt = SystemClock.elapsedRealtime()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w("JaneNetwork", "chat_failed elapsed_ms=${SystemClock.elapsedRealtime() - requestStartedAt}", e)
                mainHandler.post {
                    busy = false
                    status = "SYSTEM ONLINE"
                    errorText = "I couldn't reach Jane Core. Check your connection and try again."
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string().orEmpty()
                Log.i("JaneNetwork", "chat_completed elapsed_ms=${SystemClock.elapsedRealtime() - requestStartedAt} http=${response.code}")
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
                            val responseJson = JSONObject(responseText)
                            val action = responseJson.optJSONObject("action")
                            val reply = if (action != null) {
                                executeDeviceAction(action)
                            } else {
                                responseJson.getString("reply")
                            }
                            messages.add(ChatMessage("assistant", reply))
                            onSpeak(reply)
                        } catch (_: Exception) {
                            errorText = "Jane returned an unreadable response. Please try again."
                        }
                    }
                }
                response.close()
            }
        })
    }

    val wakeWordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                context.startForegroundService(Intent(context, WakeWordService::class.java))
                wakeWordActive = true
                errorText = ""
            } catch (_: Exception) {
                errorText = "I couldn't start wake-word listening. Open Jane and try again."
            }
        } else {
            errorText = "Microphone permission is required for Jane wake-word listening."
        }
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

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B1018)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("JANE", color = Color(0xFFF4F6FA), fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(6.dp).background(if (busy) Color(0xFFFFB45B) else Color(0xFF5DE0BD), CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(status, color = Color(0xFF8291A3), fontSize = 9.sp, letterSpacing = 1.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        if (wakeWordActive) {
                            context.stopService(Intent(context, WakeWordService::class.java))
                            wakeWordActive = false
                        } else wakeWordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(if (wakeWordActive) "VOICE ACTIVATION ON" else "VOICE ACTIVATION", color = if (wakeWordActive) Color(0xFF69DDBD) else Color(0xFF8291A3), fontSize = 9.sp, letterSpacing = 0.4.sp)
                }
                TextButton(
                    onClick = {
                        if (!JaneNotificationListener.hasNotificationAccess(context)) {
                            notificationSettingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        } else {
                            notificationContextEnabled = !notificationContextEnabled
                            preferences.edit().putBoolean("notification_context_enabled", notificationContextEnabled).apply()
                            Toast.makeText(
                                context,
                                if (notificationContextEnabled) "Recent notifications may now be shared with Jane's AI when you chat." else "Notification sharing is off.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        when {
                            !notificationAccess -> "NOTIF ACCESS"
                            notificationContextEnabled -> "NOTIFS SHARED"
                            else -> "NOTIFS OFF"
                        },
                        color = if (notificationContextEnabled && notificationAccess) Color(0xFF69DDBD) else Color(0xFF8291A3),
                        fontSize = 9.sp,
                        letterSpacing = 0.4.sp
                    )
                }
                TextButton(
                    onClick = {
                        if (overlayActive) {
                            context.stopService(Intent(context, FloatingRobotService::class.java))
                            overlayActive = false
                        } else if (Settings.canDrawOverlays(context)) {
                            context.startForegroundService(Intent(context, FloatingRobotService::class.java))
                            overlayActive = true
                        } else {
                            val permissionIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            overlayPermissionLauncher.launch(permissionIntent)
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(if (overlayActive) "OVERLAY ON" else "OVERLAY", color = if (overlayActive) Color(0xFF69DDBD) else Color(0xFF8291A3), fontSize = 9.sp, letterSpacing = 0.4.sp)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("So. What's going on?", color = Color(0xFFEAF0F7), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text("Say something interesting. Or don't. I'm not your manager.", color = Color(0xFF8492A4), fontSize = 12.sp)
                        }
                    }
                }
                items(messages) { message ->
                    val isUser = message.role == "user"
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                    ) {
                        if (!isUser) {
                            Text("JANE", color = Color(0xFFFFB36A), fontSize = 9.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(5.dp))
                        }
                        Surface(
                            modifier = Modifier.widthIn(max = 328.dp),
                            color = if (isUser) Color(0xFF243448) else Color(0xFF151E29),
                            shape = RoundedCornerShape(
                                topStart = 17.dp, topEnd = 17.dp,
                                bottomStart = if (isUser) 17.dp else 5.dp,
                                bottomEnd = if (isUser) 5.dp else 17.dp
                            )
                        ) {
                            Text(
                                text = formatMessage(message.content),
                                modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                                color = Color(0xFFE7EDF5), fontSize = 14.sp, lineHeight = 22.sp
                            )
                        }
                        if (!isUser) {
                            Row(modifier = Modifier.padding(start = 2.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Jane's reply", message.content))
                                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) { Text("COPY", color = Color(0xFF8B9BAE), fontSize = 9.sp, letterSpacing = 0.8.sp) }
                                TextButton(
                                    onClick = {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, message.content)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share Jane's reply"))
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) { Text("SHARE", color = Color(0xFF8B9BAE), fontSize = 9.sp, letterSpacing = 0.8.sp) }
                            }
                        }
                    }
                }
                if (busy) {
                    item {
                        Text("Jane is thinking…", color = Color(0xFFFFB36A), fontSize = 12.sp)
                    }
                }
            }

            if (errorText.isNotBlank()) {
                Text(errorText, color = Color(0xFFFF8989), fontSize = 12.sp)
                Spacer(Modifier.height(7.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MODE", color = Color(0xFF66788B), fontSize = 9.sp, letterSpacing = 1.2.sp)
                Spacer(Modifier.width(8.dp))
                Box {
                    TextButton(onClick = { modeMenuExpanded = true }) {
                        Text(
                            when (selectedMode) {
                                "write" -> "WRITE"
                                "code" -> "CODE"
                                "research" -> "RESEARCH"
                                "think" -> "THINK"
                                else -> "CHAT"
                            },
                            color = Color(0xFFFFB36A),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    DropdownMenu(
                        expanded = modeMenuExpanded,
                        onDismissRequest = { modeMenuExpanded = false },
                        containerColor = Color(0xFF1B2633)
                    ) {
                        listOf(
                            "chat" to "Chat",
                            "write" to "Write",
                            "code" to "Code",
                            "research" to "Research · Web search",
                            "think" to "Think"
                        ).forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = Color(0xFFE7EDF5), fontSize = 13.sp) },
                                onClick = {
                                    selectedMode = mode
                                    modeMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                if (selectedMode == "research") {
                    Spacer(Modifier.weight(1f))
                    Text("LIVE WEB", color = Color(0xFF5DE0BD), fontSize = 8.sp, letterSpacing = 1.sp)
                }
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
                                Text("Message Jane…", color = Color(0xFF8292A5), fontSize = 14.sp)
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
                        onStopSpeaking()
                        if (listening) {
                            onStopListening()
                            listening = false
                            status = "SYSTEM ONLINE"
                        } else {
                            // Avoid microphone contention with the background wake-word listener.
                            context.stopService(Intent(context, WakeWordService::class.java))
                            wakeWordActive = false
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


private const val API_BASE_URL = "https://zoi-core.onrender.com"

private fun warmBackend() {
    val startedAt = SystemClock.elapsedRealtime()
    val request = Request.Builder()
        .url("$API_BASE_URL/health")
        .get()
        .build()

    httpClient.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            Log.w(
                "JaneNetwork",
                "backend_warmup_failed elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}",
                e
            )
        }

        override fun onResponse(call: Call, response: Response) {
            val body = response.body?.string().orEmpty()
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val uptimeSeconds = try {
                JSONObject(body).optDouble("uptime_seconds", -1.0)
            } catch (_: Exception) {
                -1.0
            }
            Log.i(
                "JaneNetwork",
                "backend_warmup_completed elapsed_ms=$elapsedMs server_uptime_seconds=$uptimeSeconds http=${response.code}"
            )
            response.close()
        }
    })
}

private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .build()

private val mainHandler = Handler(Looper.getMainLooper())
