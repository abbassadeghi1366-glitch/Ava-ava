package com.avaai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import java.util.concurrent.TimeUnit
import io.github.sceneview.SceneView
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.node.ModelNode

private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .callTimeout(75, TimeUnit.SECONDS)
    .build()
private val jsonType = "application/json; charset=utf-8".toMediaType()

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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
    var status by remember { mutableStateOf("در حال بررسی اتصال آوا…") }
    var serviceOnline by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf("telegram") }
    var chatId by remember { mutableStateOf("") }
    var instagramRecipientId by remember { mutableStateOf("") }
    var gameMode by remember { mutableStateOf("آماده") }
    var outfitStyle by remember { mutableStateOf("لباس شب") }
    var avatarExpanded by remember { mutableStateOf(false) }
    var touchMessage by remember { mutableStateOf("برای تعامل با آوا یکی از دکمه‌ها را انتخاب کن.") }
    val lines = remember { mutableStateListOf(ChatLine(false, "سلام! من آوا هستم. می‌توانم به پرسش‌ها پاسخ بدهم و برای پیام‌ها پیش‌نویس بسازم.")) }

    LaunchedEffect(Unit) {
        serviceOnline = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder().url(BuildConfig.BASE_URL + "/health").get().build()
                ).execute().use { response ->
                    response.isSuccessful && JSONObject(response.body?.string().orEmpty()).optBoolean("ok")
                }
            }.getOrDefault(false)
        }
        status = if (serviceOnline) "اتصال به سرور آوا برقرار است." else "سرور آوا در دسترس نیست؛ اتصال اینترنت یا وضعیت سرویس را بررسی کن."
    }

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
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Surface(shape = CircleShape, color = if (serviceOnline) Color(0xFF233A35) else Color(0xFF49342D)) {
                        Text(
                            if (serviceOnline) "● متصل" else "● آفلاین",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = if (serviceOnline) Color(0xFF7BE0B1) else Color(0xFFFFC49B),
                            fontSize = 12.sp
                        )
                    }
                }

                // Real GLB avatar viewport. Animation clips are selected from the embedded GLB tracks.
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFF211A30),
                    modifier = Modifier.fillMaxWidth().height(300.dp)
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Ava3DAvatar(
                            Modifier.fillMaxSize(),
                            outfitStyle = outfitStyle,
                            animationName = when (gameMode) {
                                "راه رفتن" -> "Walk"
                                "دویدن" -> "Run"
                                "سلام" -> "Wave"
                                else -> "Idle"
                            }
                        )
                        IconButton(
                            onClick = { avatarExpanded = true },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        ) { Text("⛶", color = Color.White, fontSize = 26.sp) }
                        Surface(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                            color = Color(0xAA211A30), shape = RoundedCornerShape(50)
                        ) {
                            Text("آوا • مدل سه‌بعدی", modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = Color.White, fontSize = 12.sp)
                        }
                    }
                }

                if (avatarExpanded) {
                    Dialog(onDismissRequest = { avatarExpanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF100D1A))) {
                            Ava3DAvatar(
                                modifier = Modifier.fillMaxSize(),
                                outfitStyle = outfitStyle,
                                animationName = when (gameMode) {
                                    "راه رفتن" -> "Walk"
                                    "دویدن" -> "Run"
                                    "سلام" -> "Wave"
                                    else -> "Idle"
                                }
                            )
                            TextButton(onClick = { avatarExpanded = false }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                                Text("بستن ✕", color = Color.White)
                            }
                            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                                listOf("لباس شب", "لباس روزمره", "لباس ورزشی").forEach { style ->
                                    FilterChip(selected = outfitStyle == style, onClick = { outfitStyle = style }, label = { Text(style) })
                                }
                            }
                        }
                    }
                }

                Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF211A30), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("حالت بازی آوا • نسخه آزمایشی", fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("حالت فعلی: $gameMode", color = Color(0xFFC5A6FF))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                gameMode = "راه رفتن"
                                touchMessage = "انیمیشن واقعی Walk در حال پخش است."
                                onSpeak("بیا با هم قدم بزنیم!")
                            }, modifier = Modifier.weight(1f)) { Text("🚶 راه رفتن") }
                            Button(onClick = {
                                gameMode = "دویدن"
                                touchMessage = "انیمیشن واقعی Run در حال پخش است."
                                onSpeak("حالا کمی سریع‌تر!")
                            }, modifier = Modifier.weight(1f)) { Text("🏃 دویدن") }
                            OutlinedButton(onClick = {
                                gameMode = "آماده"
                                touchMessage = "آوا به انیمیشن Idle برگشت."
                                onSpeak("من اینجام!")
                            }, modifier = Modifier.weight(1f)) { Text("توقف") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                gameMode = "سلام"
                                touchMessage = "انیمیشن واقعی Wave در حال پخش است."
                                onSpeak("سلام! خوش اومدی.")
                            }, modifier = Modifier.weight(1f)) { Text("👋 دست تکان دادن") }
                            OutlinedButton(onClick = { gameMode = "رقص"; touchMessage = "برای رقص واقعی باید کلیپ Dance به مدل اضافه شود." }, enabled = false, modifier = Modifier.weight(1f)) { Text("💃 رقص (بعداً)") }
                        }
                        Text("انتخاب استایل", color = Color.White, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = outfitStyle == "لباس شب", onClick = { outfitStyle = "لباس شب" }, label = { Text("شب") })
                            FilterChip(selected = outfitStyle == "لباس روزمره", onClick = { outfitStyle = "لباس روزمره" }, label = { Text("روزمره") })
                            FilterChip(selected = outfitStyle == "لباس ورزشی", onClick = { outfitStyle = "لباس ورزشی" }, label = { Text("ورزشی") })
                        }
                        Text("استایل انتخابی: $outfitStyle", color = Color(0xFFBDB2D0), fontSize = 12.sp)
                        Text("واکنش لمسی", color = Color.White, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { touchMessage = "آوا با لبخند جواب می‌دهد."; onSpeak("سلام! خوشحالم می‌بینمت.") }) { Text("صورت") }
                            OutlinedButton(onClick = { touchMessage = "آوا موهایش را مرتب می‌کند."; onSpeak("موهام رو دوست داری؟") }) { Text("مو") }
                            OutlinedButton(onClick = { touchMessage = "آوا دست تکان می‌دهد."; onSpeak("سلام، دستت رو می‌بینم!") }) { Text("دست") }
                            OutlinedButton(onClick = { touchMessage = "آوا با لبخند واکنش نشان می‌دهد."; onSpeak("چه خبر؟") }) { Text("شانه") }
                        }
                        Text(touchMessage, color = Color(0xFFBDB2D0), fontSize = 12.sp)
                        Text("انیمیشن‌های موجود در مدل: Idle، Walk، Run و Wave. برای رقص کامل باید کلیپ Dance جداگانه به همین اسکلت اضافه شود.", color = Color(0xFFFFD180), fontSize = 11.sp)
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
                            OutlinedTextField(value = instagramRecipientId, onValueChange = { instagramRecipientId = it }, modifier = Modifier.fillMaxWidth(), label = { Text("شناسه گیرنده اینستاگرام (IG-scoped ID)") }, singleLine = true)
                            Text("گیرنده باید قبلاً به حساب حرفه‌ای شما پیام داده باشد. شناسه کاربری عمومی به‌تنهایی کافی نیست.", color = Color(0xFFFFD180), fontSize = 12.sp)
                        }
                        Button(
                            enabled = !busy && draft.isNotBlank() && (if (platform == "telegram") chatId.isNotBlank() else instagramRecipientId.isNotBlank()),
                            onClick = {
                                val confirmText = "پلتفرم: $platform\nگیرنده: ${if (platform == "telegram") chatId else instagramRecipientId}\n\nپیام:\n$draft\n\nآیا تأیید می‌کنی که پیام ارسال شود؟"
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
                                                    val result = post("/api/send/instagram", JSONObject().put("recipientId", instagramRecipientId.trim()).put("text", draft.trim()))
                                                    status = "پیام اینستاگرام ارسال شد. شناسه: ${result.optString("messageId", "ثبت شد")}" 
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


@Composable
private fun Ava3DAvatar(
    modifier: Modifier = Modifier,
    outfitStyle: String = "لباس شب",
    animationName: String = "Idle"
) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val modelAssetName = remember(context, outfitStyle) {
        val candidate = when (outfitStyle) {
            "لباس روزمره" -> "ava_casual.glb"
            "لباس ورزشی" -> "ava_sport.glb"
            else -> "ava_evening.glb"
        }
        runCatching { context.assets.open(candidate).use { }; candidate }
            .getOrDefault("woman-base-character.glb")
    }

    SceneView(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        cameraManipulator = rememberCameraManipulator()
    ) {
        rememberModelInstance(modelLoader, modelAssetName)?.let { instance ->
            ModelNode(
                modelInstance = instance,
                scaleToUnits = 1.0f,
                autoAnimate = false,
                animationName = animationName,
                animationLoop = true,
                animationSpeed = 1.0f
            )
        }
    }
}
