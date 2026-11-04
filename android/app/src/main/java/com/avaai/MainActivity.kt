package com.avaai

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

private val http = OkHttpClient()
private val jsonType = "application/json; charset=utf-8".toMediaType()

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent {
            AvaHome(
                speak = { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "ava_reply") }
            )
        }
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("fa", "IR")
    }
    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

private data class Message(val user: Boolean, val text: String)
private data class LaunchableApp(val label: String, val intent: Intent)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AvaHome(speak: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chatOpen by remember { mutableStateOf(false) }
    var appsOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf("در حال بررسی اتصال…") }
    val messages = remember { mutableStateListOf(Message(false, "سلام، من آوا هستم. چه کمکی از دستم برمی‌آید؟")) }

    fun requestSpeech(launch: (Intent) -> Unit) {
        launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "با آوا صحبت کن")
        })
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) input = spoken
    }

    suspend fun callAssistant(text: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BuildConfig.BASE_URL.trimEnd('/') + "/api/assistant")
            .post(JSONObject().put("message", text).toString().toRequestBody(jsonType))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = try { JSONObject(body) } catch (_: Exception) { JSONObject().put("error", body) }
            if (!response.isSuccessful) throw IllegalStateException(json.optString("error", "خطای سرور"))
            json.optString("answer", "پاسخی دریافت نشد.")
        }
    }

    LaunchedEffect(Unit) {
        connection = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(BuildConfig.BASE_URL.trimEnd('/') + "/health").get().build()
                http.newCall(req).execute().use { if (it.isSuccessful) "سرور متصل است" else "سرور پاسخ نداد" }
            } catch (_: Exception) { "سرور هنوز راه‌اندازی نشده است" }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFC7A7FF), secondary = Color(0xFF79E2E8),
        background = Color(0xFF0D0A16), surface = Color(0xFF211A30)
    )) {
        Scaffold(containerColor = Color(0xFF0D0A16)) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("AVA", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                        Text("دستیار هوشمند تو", color = Color(0xFFC7A7FF), fontSize = 13.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(
                            Modifier.size(250.dp).background(
                                Brush.radialGradient(listOf(Color(0xFF563C83), Color(0xFF201832), Color(0xFF0D0A16))),
                                CircleShape
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Ava3DAvatar(Modifier.fillMaxSize())
                        }
                        Text("سلام، من آوا هستم", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text(connection, color = if (connection == "سرور متصل است") Color(0xFF78D9A8) else Color(0xFFFFC978), fontSize = 12.sp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { chatOpen = true },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) { Text("گفت‌وگو با آوا  •  شروع چت", fontSize = 16.sp) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { requestSpeech { speech.launch(it) } }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) {
                                Text("🎙 صحبت")
                            }
                            OutlinedButton(onClick = { appsOpen = true }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(16.dp)) {
                                Text("برنامه‌های گوشی")
                            }
                        }
                        Text("اتصال پیام‌رسان‌ها و ایمیل در مراحل بعدی فعال می‌شود.", color = Color(0xFF9186A5), fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
        }

        if (chatOpen) {
            AlertDialog(
                onDismissRequest = { if (!busy) chatOpen = false },
                title = { Text("گفت‌وگو با آوا") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(messages) { msg ->
                                Surface(
                                    color = if (msg.user) Color(0xFF49346D) else Color(0xFF30283F),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text(msg.text, Modifier.padding(10.dp), color = Color.White) }
                            }
                        }
                        OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("پیامت را بنویس…") }, maxLines = 4)
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && input.isNotBlank(), onClick = {
                        val prompt = input.trim()
                        input = ""
                        messages.add(Message(true, prompt))
                        busy = true
                        scope.launch {
                            try {
                                val answer = callAssistant(prompt)
                                messages.add(Message(false, answer))
                                speak(answer)
                            } catch (e: Exception) {
                                messages.add(Message(false, e.message ?: "اتصال ناموفق بود."))
                            } finally { busy = false }
                        }
                    }) { Text(if (busy) "در حال پاسخ…" else "ارسال") }
                },
                dismissButton = { TextButton(onClick = { if (!busy) chatOpen = false }) { Text("بستن") }
                }
            )
        }

        if (appsOpen) {
            val apps = remember {
                val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                context.packageManager.queryIntentActivities(query, 0)
                    .map { info ->
                        LaunchableApp(info.loadLabel(context.packageManager).toString(),
                            context.packageManager.getLaunchIntentForPackage(info.activityInfo.packageName)!!)
                    }
                    .distinctBy { it.label }
                    .sortedBy { it.label.lowercase() }
            }
            AlertDialog(
                onDismissRequest = { appsOpen = false },
                title = { Text("برنامه‌های نصب‌شده") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(apps) { app ->
                            TextButton(onClick = {
                                try { context.startActivity(app.intent); appsOpen = false } catch (_: Exception) { }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(app.label, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { appsOpen = false }) { Text("بستن") } }
            )
        }
    }
}

@Composable
private fun Ava3DAvatar(modifier: Modifier = Modifier) {
    val engine = io.github.sceneview.rememberEngine()
    val loader = io.github.sceneview.rememberModelLoader(engine)
    Box(modifier) {
        io.github.sceneview.SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = loader,
            cameraManipulator = io.github.sceneview.rememberCameraManipulator()
        ) {
            io.github.sceneview.rememberModelInstance(loader, "models/woman-base-character.glb")?.let { instance ->
                io.github.sceneview.node.ModelNode(modelInstance = instance, scaleToUnits = 1.0f, autoAnimate = false)
            }
        }
    }
}
