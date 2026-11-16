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
import android.speech.tts.UtteranceProgressListener
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
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
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
  setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8BFF70), onPrimary = Color(0xFF10210B), primaryContainer = Color(0xFF23451D), onPrimaryContainer = Color(0xFFD7FFCC), secondary = Color(0xFFB6F59F), tertiary = Color(0xFF8BFF70), surface = Color(0xFF171A17), background = Color(0xFF101210), surfaceVariant = Color(0xFF252A25), onSurface = Color(0xFFE7F1E3), onSurfaceVariant = Color(0xFFC1CCBC))) { ChatApp() } }
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
 var smartModel by remember { mutableStateOf(File(ctx.filesDir, "webforge-model-4b.litertlm").takeIf { it.exists() }) }
 var engine by remember { mutableStateOf<Engine?>(null) }
 var chat by remember { mutableStateOf<Conversation?>(null) }
 var messages by remember { mutableStateOf(readMessages(prefs)) }
 var memory by remember { mutableStateOf(readMemory(prefs)) }
 var input by remember { mutableStateOf("") }
 var status by remember { mutableStateOf(if (model != null) "Модель найдена. Автоматически запускаю…" else "Установи локальную модель в настройках.") }
 var busy by remember { mutableStateOf(false) }
 var startingModel by remember { mutableStateOf(false) }
 var settings by remember { mutableStateOf(false) }
 var clearCacheDialog by remember { mutableStateOf(false) }
 var memoryDialog by remember { mutableStateOf(false) }
 var task by remember { mutableStateOf<Job?>(null) }
 var activeWorkKind by remember { mutableStateOf("") }
 var activeDownloadConnection by remember { mutableStateOf<HttpURLConnection?>(null) }
 var downloadProgress by remember { mutableFloatStateOf(-1f) }
 var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
 var listening by remember { mutableStateOf(false) }
 var voiceMode by remember { mutableStateOf(prefs.getBoolean("voice_mode", false)) }
 var ttsReady by remember { mutableStateOf(false) }
 var pendingSpeechIntent by remember { mutableStateOf<Intent?>(null) }
 val submitVoice = remember { arrayOfNulls<() -> Unit>(1) }
 var tts by remember { mutableStateOf<TextToSpeech?>(null) }
 val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
  if (granted) pendingSpeechIntent?.let { speechRecognizer?.startListening(it); listening = true }
  else status = "Разреши доступ к микрофону в настройках Android."
 }
 val partialModel = remember { File(ctx.filesDir, "webforge-model-1.7b.download") }
 val partialSmartModel = remember { File(ctx.filesDir, "webforge-model-4b.download") }
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
  val speaker = TextToSpeech(ctx, TextToSpeech.OnInitListener { result -> ttsReady = result == TextToSpeech.SUCCESS })
  tts = speaker
  onDispose { try { speaker.stop(); speaker.shutdown() } catch (_: Exception) {}; tts = null; ttsReady = false }
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
    private fun isCurrent() = speechRecognizer === recognizer
    override fun onReadyForSpeech(params: Bundle?) {
     if (!isCurrent()) return
     listening = true; status = "Слушаю… говори."
    }
    override fun onBeginningOfSpeech() { if (isCurrent()) listening = true }
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
     if (!isCurrent()) return
     listening = false; status = "Обрабатываю речь…"
    }
    override fun onError(error: Int) {
     if (!isCurrent()) return
     listening = false
     status = when (error) {
      SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
       "Служба распознавания речи отключилась (код 11). Нажми микрофон ещё раз. Если повторяется — проверь приложение Google и сервис распознавания речи."
      SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
       "Нет доступа к микрофону. Разреши его в настройках Android."
      SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
       "Распознавание ещё занято. Подожди секунду и нажми микрофон снова."
      SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
       "Нет соединения со службой распознавания речи. Проверь интернет и попробуй снова."
      else -> "Не удалось распознать речь (код $error). Нажми микрофон и попробуй ещё раз."
     }
     try { recognizer.destroy() } catch (_: Exception) {}
     speechRecognizer = null
    }
    override fun onResults(results: Bundle?) {
     if (!isCurrent()) return
     listening = false
     val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
     try { recognizer.destroy() } catch (_: Exception) {}
     speechRecognizer = null
     if (spoken.isNotBlank()) { input = spoken; submitVoice[0]?.invoke() } else status = "Речь не распознана. Нажми микрофон и попробуй ещё раз."
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
 LaunchedEffect(ttsReady) {
  if (ttsReady) tts?.let { speaker ->
   val result = speaker.setLanguage(Locale("ru", "RU"))
   if (result >= TextToSpeech.LANG_AVAILABLE) {
    speaker.setSpeechRate(1.04f)
    speaker.setPitch(1.0f)
    val russianVoice = speaker.voices?.filter { it.locale.language == "ru" && !it.isNetworkConnectionRequired }?.maxByOrNull { it.quality }
    if (russianVoice != null) speaker.voice = russianVoice
   }
   speaker.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) {}
    override fun onDone(utteranceId: String?) { ctx.mainExecutor.execute { if (voiceMode && !busy) beginVoiceInput() } }
    @Deprecated("Deprecated in Java")
    override fun onError(utteranceId: String?) { ctx.mainExecutor.execute { if (voiceMode && !busy) beginVoiceInput() } }
   })
  }
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
  val isComplex = isComplexPrompt(text)
  val fastFile = File(ctx.filesDir, "webforge-model-1.7b.litertlm").takeIf { it.exists() }
  // Keep the currently loaded model between requests. Reinitializing a multi-GB
  // model can take several seconds; switch to 4B only when a complex task needs it.
  // Do not switch back to 1.7B automatically after every short follow-up.
  val desiredModel = when {
   isComplex && smartModel != null && model?.name != "webforge-model-4b.litertlm" -> smartModel
   else -> model
  }
  busy = true
  status = if (desiredModel?.name == "webforge-model-4b.litertlm") "Переключаюсь на умную модель 4B…" else if (isComplex && smartModel == null) "Сложная задача • использую доступную модель 1.7B…" else "Думаю локально…"
  activeWorkKind = "chat"
  task = scope.launch(dispatcher) {
   try {
    if (desiredModel != null && model?.absolutePath != desiredModel.absolutePath) {
     try { chat?.close() } catch (_: Exception) {}
     try { engine?.close() } catch (_: Exception) {}
     var selectedFile = desiredModel
     var replacement: Engine? = null
     try {
      val gpu = Engine(EngineConfig(modelPath = selectedFile.absolutePath, backend = Backend.GPU(), cacheDir = ctx.cacheDir.absolutePath))
      replacement = try { gpu.initialize(); gpu } catch (_: Exception) {
       try { gpu.close() } catch (_: Exception) {}
       Engine(EngineConfig(modelPath = selectedFile.absolutePath, backend = Backend.CPU(threadCount = 4), cacheDir = ctx.cacheDir.absolutePath)).also { it.initialize() }
      }
     } catch (modelError: Exception) {
      if (selectedFile.name != "webforge-model-4b.litertlm" || fastFile == null) throw modelError
      selectedFile = fastFile
      val fallback = Engine(EngineConfig(modelPath = selectedFile.absolutePath, backend = Backend.CPU(threadCount = 4), cacheDir = ctx.cacheDir.absolutePath))
      replacement = fallback.also { it.initialize() }
      withContext(Dispatchers.Main) { status = "4B не запустилась — перешёл на быструю 1.7B." }
     }
     val replacementChat = checkNotNull(replacement).createConversation(ConversationConfig(
      systemInstruction = Contents.of("Ты личный локальный ИИ-собеседник. Общайся по-русски, естественно и точно. Сначала дай прямой ответ, не затягивай вступление и не повторяй запрос. Для простых вопросов отвечай кратко; для сложных задач объясняй по шагам. Если не уверен — прямо скажи. Учитывай память и контекст, не выдумывай факты. Память хранится приложением локально; не утверждай, что изменяешь веса нейросети."),
      samplerConfig = SamplerConfig(topK = 12, topP = 0.88, temperature = 0.58),
      maxOutputToken = 512
     ))
     engine = replacement; chat = replacementChat; model = selectedFile
    }
    // Shorter context reduces prompt-prefill time on mobile hardware.
    // Conversation retains its own turns; re-sending them duplicates tokens and slows every request.
    val relevantMemory = selectRelevantMemory(memory, text, 4)
    val prompt = if (relevantMemory.isEmpty()) text else
     "Полезная память о пользователе (используй только по теме):\n" +
      relevantMemory.joinToString("\n") { "- $it" } + "\n\nЗапрос: $text"
    val result = StringBuilder()
    checkNotNull(chat).sendMessageAsync(prompt).collect { piece ->
     val chunk = piece.toString()
     when {
      chunk.isBlank() -> Unit
      result.isEmpty() -> result.append(chunk)
      chunk.startsWith(result.toString()) -> { result.setLength(0); result.append(chunk) }
      else -> result.append(chunk)
     }
    }
    withContext(Dispatchers.Main) {
     val answer = cleanModelText(result.toString()).ifBlank { "Модель не сформировала ответ. Попробуй переформулировать запрос." }
     val previous = messages.lastOrNull()
     if (previous == null || previous.user || cleanModelText(previous.text) != answer) {
      messages = messages + Msg(System.currentTimeMillis(), answer, false)
     }
     saveChat(); status = "Локальный режим • ${if (model?.name == "webforge-model-4b.litertlm") "умная 4B" else "быстрая 1.7B"} • память: ${memory.size}"
     if (voiceMode) tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "webforge-answer")
    }
   } catch (e: CancellationException) {
    withContext(Dispatchers.Main) { messages = messages + Msg(System.currentTimeMillis(), "Генерация остановлена.", false); saveChat() }
   } catch (e: Exception) { withContext(Dispatchers.Main) { status = "Ошибка: ${e.message}" } }
   finally { withContext(Dispatchers.Main) { busy = false; task = null; activeWorkKind = "" } }
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
      systemInstruction = Contents.of("Ты личный локальный ИИ-собеседник. Общайся по-русски, естественно и точно. Сначала дай прямой ответ, не затягивай вступление и не повторяй запрос. Для простых вопросов отвечай кратко; для сложных задач объясняй по шагам. Если не уверен — прямо скажи. Учитывай память и контекст, не выдумывай факты. Память хранится приложением локально; не утверждай, что изменяешь веса нейросети."),
      samplerConfig = SamplerConfig(topK = 12, topP = 0.88, temperature = 0.58),
      maxOutputToken = 512
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
   Row(verticalAlignment = Alignment.CenterVertically) {
    if (voiceMode) {
     Column(Modifier.weight(1f).padding(start = 12.dp)) {
      Text(if (listening) "Слушаю тебя…" else if (busy) "Готовлю ответ…" else "Голосовой режим", style = MaterialTheme.typography.bodyLarge)
      Text("Нажми клавиатуру, чтобы вернуться к тексту", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
     }
    } else {
     OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Напиши сообщение…") }, maxLines = 5, enabled = !busy, shape = RoundedCornerShape(22.dp))
    }
    IconButton(onClick = {
     voiceMode = !voiceMode
     prefs.edit().putBoolean("voice_mode", voiceMode).apply()
     if (voiceMode) { tts?.stop(); beginVoiceInput() }
     else { try { speechRecognizer?.cancel() } catch (_: Exception) {}; listening = false; tts?.stop(); status = "Текстовый режим" }
    }) { Icon(if (voiceMode) Icons.Default.Keyboard else Icons.Default.GraphicEq, if (voiceMode) "Переключить на текст" else "Переключить на голос") }
    if (!voiceMode) IconButton(onClick = { send() }, enabled = input.isNotBlank() && !busy) { Icon(Icons.Default.Send, "Отправить") }
   }
   if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
    LinearProgressIndicator(Modifier.weight(1f))
    TextButton(onClick = {
     val kind = activeWorkKind
     status = "Останавливаю процесс…"
     try { tts?.stop() } catch (_: Exception) {}
     try { activeDownloadConnection?.disconnect() } catch (_: Exception) {}
     try { task?.cancel(CancellationException("Stopped by user")) } catch (_: Exception) {}
     task = null
     activeDownloadConnection = null
     busy = false
     downloadProgress = -1f
     if (kind == "chat") {
      val oldChat = chat
      val oldEngine = engine
      chat = null
      engine = null
      startingModel = false
      scope.launch(Dispatchers.IO) {
       try { oldChat?.close() } catch (_: Exception) {}
       try { oldEngine?.close() } catch (_: Exception) {}
      }
      status = "Генерация остановлена. Для продолжения запусти ИИ в настройках."
     } else status = "Загрузка остановлена. Уже скачанная часть сохранена."
     activeWorkKind = ""
    }) { Text("Стоп") }
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
      activeWorkKind = "download"
      task = scope.launch {
       try {
        val target = withContext(Dispatchers.IO) {
         val finalFile = File(ctx.filesDir, "webforge-model-1.7b.litertlm")
         downloadModelResumable(
          "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm?download=true",
          partialModel,
          onConnection = { connection -> activeDownloadConnection = connection }
         ) { p -> withContext(Dispatchers.Main) { downloadProgress = p } }
         if (finalFile.exists()) finalFile.delete()
         check(partialModel.renameTo(finalFile)) { "Не удалось сохранить модель" }
         finalFile
        }
        model = target; status = "Модель скачана. Нажми «Запустить ИИ»."
       } catch (e: Exception) { status = "Ошибка загрузки: ${e.message}. Частичный файл сохранён, повтори позже." }
       finally { busy = false; downloadProgress = -1f; task = null; activeDownloadConnection = null; activeWorkKind = "" }
      }
     }
    ) { Text(if (partialModel.exists() && partialModel.length() > 0) "Продолжить загрузку модели" else "Скачать Qwen3 1.7B (≈932 МБ)") }
    if (downloadProgress >= 0f) {
     LinearProgressIndicator(progress = { downloadProgress / 100f }, modifier = Modifier.fillMaxWidth())
     Text("${downloadProgress.toInt()}%")
    }

    Spacer(Modifier.height(18.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    Spacer(Modifier.height(12.dp))
    Text("Умная модель • Qwen3 4B INT4", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Text("≈ 2,17 ГБ. Используется для сложного кода и больших проектов. Для GPU требуется LiteRT-LM 0.19.0; при ошибке приложение попробует CPU.", style = MaterialTheme.typography.bodySmall)
    Button(
     enabled = !busy && smartModel == null,
     modifier = Modifier.fillMaxWidth(),
     onClick = {
      busy = true; activeWorkKind = "download"; downloadProgress = -1f; status = "Загружаю умную модель Qwen3 4B…"
      task = scope.launch {
       try {
        val target = withContext(Dispatchers.IO) {
         val finalFile = File(ctx.filesDir, "webforge-model-4b.litertlm")
         downloadModelResumable(
          "https://huggingface.co/litert-community/Qwen3-4B/resolve/main/qwen3_4b_mixed_int4.litertlm?download=true",
          partialSmartModel,
          onConnection = { connection -> activeDownloadConnection = connection }
         ) { p -> withContext(Dispatchers.Main) { downloadProgress = p } }
         if (finalFile.exists()) finalFile.delete()
         check(partialSmartModel.renameTo(finalFile)) { "Не удалось сохранить Qwen3 4B" }
         finalFile
        }
        smartModel = target; status = "Qwen3 4B установлена. Для переключения перезапусти модель."
       } catch (e: Exception) { status = "Ошибка загрузки 4B: ${e.message}. Загрузка сохранена для продолжения." }
       finally { busy = false; downloadProgress = -1f }
      }
     }
    ) { Text(if (partialSmartModel.exists() && partialSmartModel.length() > 0) "Продолжить загрузку 4B" else "Скачать умную модель 4B") }
    Text(smartModel?.let { "Qwen3 4B установлена • ${it.length() / 1048576} МБ" } ?: "Qwen3 4B пока не установлена")
    if (smartModel != null) {
     OutlinedButton(
      enabled = !busy,
      modifier = Modifier.fillMaxWidth(),
      onClick = {
       busy = true; status = "Переключаюсь на Qwen3 4B…"
       scope.launch(dispatcher) {
        try { chat?.close(); engine?.close() } catch (_: Exception) {}
        withContext(Dispatchers.Main) {
         chat = null; engine = null; model = smartModel
         status = "Подготовка Qwen3 4B…"; settings = false
        }
       }
      }
     ) { Text("Перезапустить на умной модели 4B") }
    }
    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать файл модели .litertlm") }
    Text(model?.let { "Модель: ${it.name} • ${it.length() / 1048576} МБ" } ?: "Модель не установлена")
    Text("Файл хранится во внутренней памяти приложения. Искать его в папке «Загрузки» не нужно.")
    Spacer(Modifier.height(18.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    Spacer(Modifier.height(12.dp))
    Text("Хранилище", style = MaterialTheme.typography.titleMedium)
    val cacheBytes = ctx.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    Text("Временный кеш: ${cacheBytes / 1048576} МБ. Модели и история чата хранятся отдельно.")
    OutlinedButton(
     enabled = !busy,
     onClick = { clearCacheDialog = true },
     modifier = Modifier.fillMaxWidth()
    ) { Text("Очистить временный кеш") }
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
        systemInstruction = Contents.of("Ты личный локальный ИИ-собеседник. Общайся по-русски, естественно и точно. Сначала дай прямой ответ, не затягивай вступление и не повторяй запрос. Для простых вопросов отвечай кратко; для сложных задач объясняй по шагам. Если не уверен — прямо скажи. Учитывай память и контекст, не выдумывай факты. Память хранится приложением локально; не утверждай, что изменяешь веса нейросети."),
        samplerConfig = SamplerConfig(topK = 16, topP = 0.88, temperature = 0.62),
        maxOutputToken = 512
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
 if (clearCacheDialog) AlertDialog(
  onDismissRequest = { clearCacheDialog = false },
  title = { Text("Очистить временный кеш?") },
  text = { Text("Удалятся только временные файлы. Скачанные модели, незавершённые загрузки и история чата останутся на месте.") },
  confirmButton = {
   TextButton(onClick = {
    val removed = try {
     ctx.cacheDir.listFiles()?.sumOf { file ->
      val size = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
      file.deleteRecursively()
      size
     } ?: 0L
    } catch (_: Exception) { 0L }
    status = "Временный кеш очищен • ${removed / 1048576} МБ освобождено"
    clearCacheDialog = false
   }) { Text("Очистить") }
  },
  dismissButton = { TextButton(onClick = { clearCacheDialog = false }) { Text("Отмена") } }
 )
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
private fun isComplexPrompt(text: String): Boolean {
 val value = text.lowercase()
 val markers = listOf("создай приложение", "создай проект", "напиши код", "исправь ошиб", "разработай", "архитектур", "оптимизируй", "рефактор", "debug", "kotlin", "gradle", "android", "api", "алгоритм", "интеграц", "собери apk", "веб-прилож", "приложение", "проект", "программ", "код", "разберись с")
 return text.length > 420 || markers.any { value.contains(it) } || text.lines().size > 8
}

private fun selectRelevantMemory(memory: List<String>, query: String, limit: Int): List<String> {
 val stop = setOf("это", "как", "что", "для", "или", "мне", "меня", "тебя", "надо", "нужно", "можно", "пожалуйста", "сделай", "почему")
 fun words(value: String) = Regex("[\\p{L}\\p{N}]{3,}").findAll(value.lowercase())
  .map { it.value }.filterNot { it in stop }.toSet()
 val queryWords = words(query)
 if (queryWords.isEmpty()) return memory.takeLast(3).reversed()
 return memory.mapIndexed { index, item ->
  Triple(item, words(item).intersect(queryWords).size, index)
 }.filter { it.second > 0 }
  .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })
  .take(limit).map { it.first }
}

private fun cleanModelText(source: String): String {
 return source.trim().replace("```", "").replace("\\times", "×")
  .replace("\\cdot", "·").replace("**", "").replace("*", "").replace("$", "").trim()
}

private fun readMessages(p: android.content.SharedPreferences): List<Msg> = try {
 val a = JSONArray(p.getString("messages", "[]"))
 (0 until a.length()).map { val o = a.getJSONObject(it); Msg(o.getLong("id"), o.getString("text"), o.getBoolean("user")) }
} catch (_: Exception) { emptyList() }
private fun readMemory(p: android.content.SharedPreferences): List<String> = try {
 val a = JSONArray(p.getString("memory", "[]")); (0 until a.length()).map { a.getString(it) }
} catch (_: Exception) { emptyList() }

private suspend fun downloadModelResumable(url: String, file: File, onConnection: (HttpURLConnection?) -> Unit = {}, onProgress: suspend (Float) -> Unit) {
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
   onConnection(connection)
   currentCoroutineContext().ensureActive()
   connection.connect()
   currentCoroutineContext().ensureActive()
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
      currentCoroutineContext().ensureActive()
      val n = input.read(buffer); if (n < 0) break
      currentCoroutineContext().ensureActive()
      out.write(buffer, 0, n); got += n
      if (total > 0) onProgress((got * 100f / total).coerceIn(0f, 99.9f))
     }
    }
   }
   if (total > 0 && file.length() != total) error("Загружено ${file.length()} из $total байт")
   if (file.length() >= 850L * 1024L * 1024L) { onProgress(100f); return }
   error("Файл меньше ожидаемого размера")
  } catch (e: CancellationException) {
   throw e
  } catch (e: Exception) {
   last = e
   if (attempt < 8) delay((attempt * 1500L).coerceAtMost(10000L))
  } finally { connection?.disconnect(); onConnection(null) }
 }
 throw java.io.IOException("Загрузка не завершена; сохранено ${file.length() / 1048576} МБ. ${last?.message ?: ""}", last)
}
