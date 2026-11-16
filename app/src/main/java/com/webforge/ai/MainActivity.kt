package com.webforge.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private data class Msg(val id: Long, val text: String, val user: Boolean)

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { MaterialTheme(colorScheme = darkColorScheme()) { ChatApp() } }
 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatApp() {
 val ctx = LocalContext.current
 val prefs = remember { ctx.getSharedPreferences("local_chat", 0) }
 val scope = rememberCoroutineScope()
 val executor = remember { Executors.newSingleThreadExecutor() }
 val dispatcher = remember { executor.asCoroutineDispatcher() }
 var model by remember { mutableStateOf(File(ctx.filesDir, "webforge-model-1.7b.litertlm").takeIf { it.exists() }) }
 var engine by remember { mutableStateOf<Engine?>(null) }
 var chat by remember { mutableStateOf<Conversation?>(null) }
 var messages by remember { mutableStateOf(readMessages(prefs)) }
 var memory by remember { mutableStateOf(readMemory(prefs)) }
 var input by remember { mutableStateOf("") }
 var status by remember { mutableStateOf(if (model != null) "Модель найдена. Автоматически запускаю…" else "Установи локальную модель в настройках.") }
 var busy by remember { mutableStateOf(false) }
 var startingModel by remember { mutableStateOf(false) }
 var settings by remember { mutableStateOf(false) }
 var memoryDialog by remember { mutableStateOf(false) }
 var task by remember { mutableStateOf<Job?>(null) }
 var downloadProgress by remember { mutableFloatStateOf(-1f) }
 var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
 var listening by remember { mutableStateOf(false) }
 var pendingSpeechIntent by remember { mutableStateOf<Intent?>(null) }
 val submitVoice = remember { arrayOfNulls<() -> Unit>(1) }
 var tts by remember { mutableStateOf<TextToSpeech?>(null) }
 val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
  if (granted) pendingSpeechIntent?.let { speechRecognizer?.startListening(it); listening = true }
  else status = "Разреши доступ к микрофону в настройках Android."
 }
 val partialModel = remember { File(ctx.filesDir, "webforge-model-1.7b.download") }
 val listState = rememberLazyListState()
 val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
  if (uri != null) scope.launch {
   busy = true
   try {
    val target = withContext(Dispatchers.IO) {
     File(ctx.filesDir, "webforge-model-1.7b.litertlm").also { f ->
      ctx.contentResolver.openInputStream(uri).use { it!!.copyTo(f.outputStream()) }
     }
    }
    model = target
    status = "Модель скопирована: ${target.length() / 1048576} МБ"
   } catch (e: Exception) { status = "Ошибка импорта: ${e.message}" }
   finally { busy = false }
  }
 }
 DisposableEffect(ctx) {
  val speaker = TextToSpeech(ctx)
  tts = speaker
  onDispose { try { speaker.stop(); speaker.shutdown() } catch (_: Exception) {}; tts = null }
 }
 LaunchedEffect(tts) {
  tts?.let { speaker ->
   val result = speaker.setLanguage(Locale("ru", "RU"))
   if (result >= TextToSpeech.LANG_AVAILABLE) {
    speaker.setSpeechRate(0.91f)
    speaker.setPitch(0.86f)
    val russianVoice = speaker.voices
     ?.filter { it.locale.language == "ru" && !it.isNetworkConnectionRequired }
     ?.maxByOrNull { it.quality }
    if (russianVoice != null) speaker.voice = russianVoice
   }
  }
 }
 DisposableEffect(Unit) {
  onDispose {
   try { speechRecognizer?.destroy() } catch (_: Exception) {}
   executor.execute { try { chat?.close() } catch (_: Exception) {}; try { engine?.close() } catch (_: Exception) {} }
   executor.shutdown()
  }
 }
 LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }

 fun saveChat() {
  val a = JSONArray()
  messages.takeLast(300).forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("user", it.user)) }
  prefs.edit().putString("messages", a.toString()).apply()
 }
 fun saveMemory() { prefs.edit().putString("memory", JSONArray(memory).toString()).apply() }
 fun beginVoiceInput() {
  if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { status = "На устройстве нет службы распознавания речи."; return }
  try {
   speechRecognizer?.destroy()
   val recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
   speechRecognizer = recognizer
   recognizer.setRecognitionListener(object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) { listening = true; status = "Слушаю… говори." }
    override fun onBeginningOfSpeech() { listening = true }
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() { listening = false; status = "Обрабатываю речь…" }
    override fun onError(error: Int) { listening = false; status = "Не удалось распознать речь (код $error). Нажми микрофон и попробуй ещё раз." }
    override fun onResults(results: Bundle?) {
     listening = false
     val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
     if (spoken.isNotBlank()) { input = spoken; submitVoice[0]?.invoke() } else status = "Речь не распознана. Попробуй ещё раз."
    }
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
   })
   val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
   }
   pendingSpeechIntent = intent
   if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
    recognizer.startListening(intent); listening = true
   } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
  } catch (e: Exception) { listening = false; status = "Ошибка микрофона: ${e.message}" }
 }
 fun send() {
  val text = input.trim()
  if (text.isBlank() || busy) return
  if (text.equals("забудь всё", true) || text.equals("очисти память", true)) {
   memory = emptyList(); saveMemory()
   messages = messages + Msg(System.currentTimeMillis(), "Локальная память очищена.", false)
   input = ""; saveChat(); return
  }
  val low = text.lowercase()
  val explicit = listOf("запомни:", "запомни ", "сохрани в памяти:")
  val adaptive = listOf("я предпочитаю", "мне нравится", "мне не нравится", "в дальнейшем", "всегда ", "не делай так", "мне нужно")
  val fact = when {
   explicit.any { low.startsWith(it) } -> text.substringAfter(":").ifBlank { text.substringAfter(" ") }.trim()
   adaptive.any { low.contains(it) } -> text
   else -> null
  }
  if (!fact.isNullOrBlank() && memory.none { it.equals(fact, true) }) {
   memory = (memory + fact.take(500)).takeLast(100); saveMemory()
  }
  messages = messages + Msg(System.currentTimeMillis(), text, true)
  saveChat(); input = ""
  if (chat == null) {
   messages = messages + Msg(System.currentTimeMillis() + 1, if (fact != null) "Сохранил в память. Запусти модель в настройках, чтобы продолжить общение." else "Сначала запусти локальную модель в настройках. Переписка сохранится.", false)
   saveChat(); return
  }
  busy = true
  status = if (fact != null) "Записал в память • думаю…" else "Думаю локально…"
  task = scope.launch(dispatcher) {
   try {
    val recent = messages.dropLast(1).takeLast(4).joinToString("\n") { (if (it.user) "Ты: " else "ИИ: ") + it.text.take(600) }
    val prompt = "Локальная память пользователя:\n" + memory.joinToString("\n") { "- $it" } +
      "\n\nНедавний разговор:\n$recent\n\nСообщение пользователя:\n$text\n\nОтвечай на русском, учитывай память и контекст. Не выдумывай факты."
    val result = StringBuilder()
    checkNotNull(chat).sendMessageAsync(prompt).collect { result.append(it.toString()) }
    withContext(Dispatchers.Main) {
     val answer = result.toString().trim().ifBlank { "Пустой ответ модели." }
     messages = messages + Msg(System.currentTimeMillis(), answer, false)
     saveChat(); status = "Локальный режим • записей памяти: ${memory.size}"
     tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "webforge-answer")
    }
   } catch (e: CancellationException) {
    withContext(Dispatchers.Main) { messages = messages + Msg(System.currentTimeMillis(), "Ответ остановлен.", false); saveChat() }
   } catch (e: Exception) { withContext(Dispatchers.Main) { status = "Ошибка: ${e.message}" } }
   finally { withContext(Dispatchers.Main) { busy = false; task = null } }
  }
 }
 submitVoice[0] = { send() }

 // Automatically initialize a model already stored in the app's private files.
 LaunchedEffect(model?.absolutePath) {
  if (model != null && chat == null && !startingModel) {
   startingModel = true
   busy = true
   status = "Автоматически запускаю локальную модель…"
   val file = model!!
   scope.launch(dispatcher) {
    var created: Engine? = null
    try {
     val gpu = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.GPU(), cacheDir = ctx.cacheDir.absolutePath))
     created = try { gpu.initialize(); gpu } catch (_: Exception) {
      try { gpu.close() } catch (_: Exception) {}
      Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(threadCount = 4), cacheDir = ctx.cacheDir.absolutePath)).also { it.initialize() }
     }
     val convo = created.createConversation(ConversationConfig(
      systemInstruction = Contents.of("Ты личный локальный ИИ-собеседник. Общайся по-русски, естественно и точно. Учитывай память и контекст, не выдумывай факты. Память хранится приложением локально; не утверждай, что изменяешь веса нейросети."),
      samplerConfig = SamplerConfig(topK = 20, topP = 0.90, temperature = 0.62)
     ))
     chat?.close(); engine?.close(); engine = created; chat = convo
     withContext(Dispatchers.Main) { status = "ИИ запущен локально"; busy = false; startingModel = false }
    } catch (e: Exception) {
     try { created?.close() } catch (_: Exception) {}
     withContext(Dispatchers.Main) { status = "Не удалось запустить модель: ${e.message}"; busy = false; startingModel = false }
    }
   }
  }
 }

 Scaffold(
  topBar = { TopAppBar(title = { Column { Text("Локальный ИИ"); Text(when { chat != null -> "На устройстве • активен"; startingModel -> "Запуск локальной модели…"; model != null -> "Модель найдена • готовлю запуск"; else -> "Модель не установлена" }, style = MaterialTheme.typography.labelSmall) } }, actions = {
   IconButton(onClick = { memoryDialog = true }) { Icon(Icons.Default.Memory, "Память") }
   IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Настройки") }
   IconButton(onClick = { messages = emptyList(); saveChat() }) { Icon(Icons.Default.Delete, "Очистить чат") }
  }) },
  bottomBar = { Surface(tonalElevation = 3.dp, modifier = Modifier.navigationBarsPadding()) { Column(Modifier.fillMaxWidth().padding(10.dp)) {
   Row(verticalAlignment = Alignment.Bottom) {
    OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text(if (listening) "Слушаю…" else "Напиши сообщение…") }, maxLines = 5, enabled = !busy, shape = RoundedCornerShape(22.dp))
    IconButton(onClick = { if (listening) { speechRecognizer?.stopListening(); listening = false } else beginVoiceInput() }, enabled = !busy) { Icon(if (listening) Icons.Default.MicOff else Icons.Default.Mic, if (listening) "Остановить микрофон" else "Голосовой ввод") }
    IconButton(onClick = { send() }, enabled = input.isNotBlank() && !busy) { Icon(Icons.Default.Send, "Отправить") }
   }
   if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
    LinearProgressIndicator(Modifier.weight(1f))
    TextButton(onClick = { task?.cancel() }) { Text("Стоп") }
   } else Text(status, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
  } } }
 ) { pad ->
  if (settings) {
   Column(Modifier.fillMaxSize().padding(pad).padding(18.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
    Text("Локальная модель", style = MaterialTheme.typography.headlineSmall)
    Text("Qwen3 1.7B в 4-битной квантовке. После установки модель отвечает прямо на телефоне, без API и аккаунта.")
    Spacer(Modifier.height(12.dp))

    Button(
     enabled = !busy && chat == null,
     modifier = Modifier.fillMaxWidth(),
     onClick = {
      busy = true; downloadProgress = -1f; status = "Подключаюсь к Hugging Face…"
      scope.launch {
       try {
        val target = withContext(Dispatchers.IO) {
         val finalFile = File(ctx.filesDir, "webforge-model-1.7b.litertlm")
         downloadModelResumable(
          "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm?download=true",
          partialModel
         ) { p -> withContext(Dispatchers.Main) { downloadProgress = p } }
         if (finalFile.exists()) finalFile.delete()
         check(partialModel.renameTo(finalFile)) { "Не удалось сохранить модель" }
         finalFile
        }
        model = target; status = "Модель скачана. Нажми «Запустить ИИ»."
       } catch (e: Exception) { status = "Ошибка загрузки: ${e.message}. Частичный файл сохранён, повтори позже." }
       finally { busy = false; downloadProgress = -1f }
      }
     }
    ) { Text(if (partialModel.exists() && partialModel.length() > 0) "Продолжить загрузку модели" else "Скачать Qwen3 1.7B (≈932 МБ)") }
    if (downloadProgress >= 0f) {
     LinearProgressIndicator(progress = { downloadProgress / 100f }, modifier = Modifier.fillMaxWidth())
     Text("${downloadProgress.toInt()}%")
    }

    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать файл модели .litertlm") }
    Text(model?.let { "Модель: ${it.name} • ${it.length() / 1048576} МБ" } ?: "Модель не установлена")
    Text("Файл хранится во внутренней памяти приложения. Искать его в папке «Загрузки» не нужно.")
    Spacer(Modifier.height(12.dp))
    if (chat != null) {
     Text("Модель уже запущена и готова к работе.", color = MaterialTheme.colorScheme.primary)
     OutlinedButton(onClick = { settings = false }, modifier = Modifier.fillMaxWidth()) { Text("Вернуться в активный чат") }
    } else Button(enabled = !busy && model != null, modifier = Modifier.fillMaxWidth(), onClick = {
     busy = true; status = "Запускаю модель…"
     val file = model!!
     scope.launch(dispatcher) {
      var created: Engine? = null
      try {
       val gpu = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.GPU(), cacheDir = ctx.cacheDir.absolutePath))
       created = try { gpu.initialize(); gpu } catch (_: Exception) {
        try { gpu.close() } catch (_: Exception) {}
        Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(threadCount = 4), cacheDir = ctx.cacheDir.absolutePath)).also { it.initialize() }
       }
       val convo = created.createConversation(ConversationConfig(
        systemInstruction = Contents.of("Ты личный локальный ИИ-собеседник. Общайся по-русски, естественно и точно. Учитывай память и контекст, не выдумывай факты. Память хранится приложением локально; не утверждай, что изменяешь веса нейросети."),
        samplerConfig = SamplerConfig(topK = 20, topP = 0.92, temperature = 0.7)
       ))
       chat?.close(); engine?.close(); engine = created; chat = convo
       withContext(Dispatchers.Main) { status = "ИИ запущен локально"; settings = false; busy = false }
      } catch (e: Exception) {
       try { created?.close() } catch (_: Exception) {}
       withContext(Dispatchers.Main) { status = "Не удалось запустить: ${e.message}"; busy = false }
      }
     }
    }) { Text("Запустить ИИ") }
    Text("Если модель была загружена этим приложением, она уже выбрана автоматически. Импорт файла нужен только для модели из другого источника.")
    OutlinedButton(onClick = { settings = false }, modifier = Modifier.fillMaxWidth()) { Text("Назад в чат") }
   }
  } else if (messages.isEmpty()) {
   Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
     Icon(Icons.Default.Memory, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
     Text("Твой локальный ИИ", style = MaterialTheme.typography.headlineSmall)
     Text("Общайся, исправляй ответы и сохраняй важное в память. Данные остаются на телефоне.", textAlign = TextAlign.Center)
     Text("Попробуй: «Запомни: я работаю декоратором»", style = MaterialTheme.typography.bodySmall)
    }
   }
  } else {
   LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
    items(messages, key = { it.id }) { m ->
     Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.user) Arrangement.End else Arrangement.Start) {
      Surface(color = if (m.user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 330.dp)) {
       Text(m.text, Modifier.padding(13.dp), color = if (m.user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
      }
     }
    }
   }
  }
 }
 if (memoryDialog) AlertDialog(
  onDismissRequest = { memoryDialog = false },
  title = { Text("Память на устройстве • ${memory.size}") },
  text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
   Text("Запомни: … — добавить факт. «Забудь всё» — очистить память.")
   memory.forEachIndexed { i, item ->
    Row(verticalAlignment = Alignment.CenterVertically) {
     Text(item, Modifier.weight(1f))
     IconButton(onClick = { memory = memory.toMutableList().also { it.removeAt(i) }; saveMemory() }) { Icon(Icons.Default.Delete, "Удалить") }
    }
    HorizontalDivider()
   }
   if (memory.isEmpty()) Text("Память пока пуста.")
  } },
  confirmButton = { TextButton(onClick = { memoryDialog = false }) { Text("Готово") } },
  dismissButton = { TextButton(onClick = { memory = emptyList(); saveMemory() }) { Text("Очистить") } }
 )
}
private fun readMessages(p: android.content.SharedPreferences): List<Msg> = try {
 val a = JSONArray(p.getString("messages", "[]"))
 (0 until a.length()).map { val o = a.getJSONObject(it); Msg(o.getLong("id"), o.getString("text"), o.getBoolean("user")) }
} catch (_: Exception) { emptyList() }
private fun readMemory(p: android.content.SharedPreferences): List<String> = try {
 val a = JSONArray(p.getString("memory", "[]")); (0 until a.length()).map { a.getString(it) }
} catch (_: Exception) { emptyList() }

private suspend fun downloadModelResumable(url: String, file: File, onProgress: suspend (Float) -> Unit) {
 var attempt = 0
 var total = -1L
 var last: Exception? = null
 while (attempt < 8) {
  attempt++
  val offset = if (file.exists()) file.length() else 0L
  var connection: HttpURLConnection? = null
  try {
   connection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 45000; readTimeout = 180000; instanceFollowRedirects = true
    setRequestProperty("User-Agent", "WebForge-Local-Chat/1.0")
    if (offset > 0) setRequestProperty("Range", "bytes=${offset}-")
   }
   connection.connect()
   val code = connection.responseCode
   if (offset > 0 && code == HttpURLConnection.HTTP_OK) {
    file.delete(); connection.disconnect(); connection = null; continue
   }
   if (offset > 0 && code != 206) error("HTTP $code при продолжении")
   if (offset == 0L && code !in 200..299) error("HTTP $code")
   val range = connection.getHeaderField("Content-Range")
   if (code == 206 && range != null) total = range.substringAfterLast('/').toLongOrNull() ?: total
   else if (offset == 0L) total = connection.contentLengthLong
   java.io.FileOutputStream(file, code == 206 && offset > 0).buffered().use { out ->
    connection.inputStream.use { input ->
     val buffer = ByteArray(256 * 1024)
     var got = if (code == 206 && offset > 0) offset else 0L
     while (true) {
      val n = input.read(buffer); if (n < 0) break
      out.write(buffer, 0, n); got += n
      if (total > 0) onProgress((got * 100f / total).coerceIn(0f, 99.9f))
     }
    }
   }
   if (total > 0 && file.length() != total) error("Загружено ${file.length()} из $total байт")
   if (file.length() >= 850L * 1024L * 1024L) { onProgress(100f); return }
   error("Файл меньше ожидаемого размера")
  } catch (e: Exception) {
   last = e
   if (attempt < 8) delay((attempt * 1500L).coerceAtMost(10000L))
  } finally { connection?.disconnect() }
 }
 throw java.io.IOException("Загрузка не завершена; сохранено ${file.length() / 1048576} МБ. ${last?.message ?: ""}", last)
}
