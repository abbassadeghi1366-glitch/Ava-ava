package com.avaai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

private val client = OkHttpClient()
private val jsonType = "application/json; charset=utf-8".toMediaType()

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        setContent { AvaScreen(
            onSpeak = { text -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ava_reply") },
            onListenResult = { /* handled by Compose state */ }
        ) }
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("fa", "IR")
    }
    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        super.onDestroy()
    }
}

data class ChatLine(val fromUser: Boolean, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvaScreen(onSpeak: (String) -> Unit, onListenResult: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("آماده‌ام؛ چه کاری انجام بدهم؟") }
    var draft by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf("telegram") }
    var chatId by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf(ChatLine(false, "سلام! من آوا هستم. می‌توانم به پرسش‌ها پاسخ بدهم و برای پیام‌ها پیش‌نویس بسازم.")) }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) input = spoken
    }

    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "با آوا صحبت کن")
        }
        try { speechLauncher.launch(intent) } catch (_: Exception) { status = "تشخیص گفتار روی این دستگاه در دسترس نیست." }
    }

    suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BuildConfig.BASE_URL + path)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val obj = try { JSONObject(text) } catch (_: Exception) { JSONObject().put("error", text) }
            if (!response.isSuccessful) throw IllegalStateException(obj.optString("error", "خطای سرور"))
            obj
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFC5A6FF), secondary = Color(0xFF8BE9FD),
        background = Color(0xFF100D1A), surface = Color(0xFF211A30)
    )) {
        Scaffold(containerColor = Color(0xFF100D1A)) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(72.dp).background(Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFF00BCD4))), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("آوا", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("AVA AI", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("دستیار هوشمند فارسی", color = Color(0xFFC5A6FF))
                    }
                    Surface(shape = CircleShape, color = Color(0xFF233A35)) {
                        Text("● آنلاین", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color(0xFF7BE0B1), fontSize = 12.sp)
                    }
                }

                Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF211A30), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("گفت‌وگو با آوا", fontWeight = FontWeight.SemiBold, color = Color.White)
                        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(lines) { line ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
                                    Surface(
                                        color = if (line.fromUser) Color(0xFF46336B) else Color(0xFF30283F),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.widthIn(max = 300.dp)
                                    ) { Text(line.text, Modifier.padding(10.dp), color = Color.White) }
                                }
                            }
                        }
                        Text(status, color = Color(0xFFBDB2D0), fontSize = 12.sp)
                        OutlinedTextField(
                            value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("به آوا بگو چه کاری انجام دهد…") },
                            minLines = 1, maxLines = 4
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { listen() }, modifier = Modifier.weight(1f)) { Text("🎙 صحبت") }
                            Button(enabled = !busy && input.isNotBlank(), onClick = {
                                val message = input.trim()
                                lines.add(ChatLine(true, message)); input = ""; busy = true; status = "آوا در حال فکر کردن است…"
                                scope.launch {
                                    try {
                                        val result = post("/api/assistant", JSONObject().put("message", message))
                                        val answer = result.optString("answer", "پاسخی دریافت نشد.")
                                        lines.add(ChatLine(false, answer)); status = "پاسخ آماده است"; onSpeak(answer)
                                    } catch (e: Exception) { status = e.message ?: "اتصال به سرور ناموفق بود." }
                                    finally { busy = false }
                                }
                            }, modifier = Modifier.weight(1f)) { Text(if (busy) "..." else "ارسال") }
                        }
                    }
                }

                Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF211A30), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("پاسخ پیشنهادی پیام", fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("پیام ورودی را وارد کن؛ آوا فقط پیش‌نویس می‌سازد.", color = Color(0xFFBDB2D0), fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = platform == "telegram", onClick = { platform = "telegram" }, label = { Text("تلگرام") })
                            FilterChip(selected = platform == "instagram", onClick = { platform = "instagram" }, label = { Text("اینستاگرام") })
                        }
                        var incoming by remember { mutableStateOf("") }
                        OutlinedTextField(value = incoming, onValueChange = { incoming = it }, modifier = Modifier.fillMaxWidth(), label = { Text("متن پیام دریافتی") }, minLines = 2)
                        OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(), label = { Text("پیش‌نویس قابل ویرایش") }, minLines = 2)
                        Button(enabled = !busy && incoming.isNotBlank(), onClick = {
                            busy = true; status = "در حال ساخت پیش‌نویس…"
                            scope.launch {
                                try {
                                    val result = post("/api/draft", JSONObject().put("platform", platform).put("incomingMessage", incoming))
                                    draft = result.optString("draft", "")
                                    status = "پیش‌نویس آماده است؛ قبل از ارسال بررسی‌اش کن."
                                } catch (e: Exception) { status = e.message ?: "خطا در ساخت پیش‌نویس" }
                                finally { busy = false }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("ساخت پاسخ پیشنهادی") }

                        if (platform == "telegram") {
                            OutlinedTextField(value = chatId, onValueChange = { chatId = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Telegram chat_id") }, singleLine = true)
                        } else {
                            Text("ارسال اینستاگرام تا زمان تنظیم رسمی Meta در سرور غیرفعال است.", color = Color(0xFFFFD180), fontSize = 12.sp)
                        }
                        Button(
                            enabled = !busy && draft.isNotBlank() && (platform != "telegram" || chatId.isNotBlank()),
                            onClick = {
                                val confirmText = "پلتفرم: $platform\nگیرنده: ${if (platform == "telegram") chatId else "شناسه گیرنده اینستاگرام"}\n\nپیام:\n$draft\n\nآیا تأیید می‌کنی که پیام ارسال شود؟"
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("تأیید ارسال")
                                    .setMessage(confirmText)
                                    .setNegativeButton("لغو", null)
                                    .setPositiveButton("تأیید و ارسال") { _, _ ->
                                        busy = true; status = "در حال ارسال…"
                                        scope.launch {
                                            try {
                                                if (platform == "telegram") {
                                                    val result = post("/api/send/telegram", JSONObject().put("chatId", chatId.trim()).put("text", draft.trim()))
                                                    status = "ارسال شد. شناسه پیام: ${result.optString("messageId", "ثبت شد")}"
                                                } else {
                                                    status = "برای ارسال اینستاگرام ابتدا اتصال رسمی Meta را در سرور کامل کن."
                                                }
                                            } catch (e: Exception) { status = e.message ?: "ارسال ناموفق بود." }
                                            finally { busy = false }
                                        }
                                    }.show()
                            }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7650C9))
                        ) { Text("تأیید و ارسال پیام") }
                    }
                }
                Text("امنیت: کلیدهای API فقط روی سرور نگهداری شوند. ارسال همیشه نیازمند تأیید توست.", color = Color(0xFF9C91AE), fontSize = 11.sp)
            }
        }
    }
}
