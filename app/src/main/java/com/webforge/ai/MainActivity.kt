package com.webforge.ai

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
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
 var status by remember { mutableStateOf(if (model != null) "Модель найдена. Запусти её в настройках." else "Установи локальную модель в настройках.") }
 var busy by remember { mutableStateOf(false) }
 var settings by remember { mutableStateOf(false) }
 var memoryDialog by remember { mutableStateOf(false) }
 var task by remember { mutableStateOf<Job?>(null) }
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
 DisposableEffect(Unit) {
  onDispose {
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
    val recent = messages.dropLast(1).takeLast(8).joinToString("\n") { (if (it.user) "Ты: " else "ИИ: ") + it.text.take(900) }
    val prompt = "Локальная память пользователя:\n" + memory.joinToString("\n") { "- $it" } +
      "\n\nНедавний разговор:\n$recent\n\nСообщение пользователя:\n$text\n\nОтвечай на русском, учитывай память и контекст. Не выдумывай факты."
    val result = StringBuilder()
    checkNotNull(chat).sendMessageAsync(prompt).collect { result.append(it.toString()) }
    withContext(Dispatchers.Main) {
     messages = messages + Msg(System.currentTimeMillis(), result.toString().trim().ifBlank { "Пустой ответ модели." }, false)
     saveChat(); status = "Локальный режим • записей памяти: ${memory.size}"
    }
   } catch (e: CancellationException) {
    withContext(Dispatchers.Main) { messages = messages + Msg(System.currentTimeMillis(), "Ответ остановлен.", false); saveChat() }
   } catch (e: Exception) { withContext(Dispatchers.Main) { status = "Ошибка: ${e.message}" } }
   finally { withContext(Dispatchers.Main) { busy = false; task = null } }
  }
 }

 Scaffold(
  topBar = { TopAppBar(title = { Column { Text("Локальный ИИ"); Text(if (chat != null) "На устройстве • активен" else "Модель не запущена", style = MaterialTheme.typography.labelSmall) } }, actions = {
   IconButton(onClick = { memoryDialog = true }) { Icon(Icons.Default.Memory, "Память") }
   IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Настройки") }
   IconButton(onClick = { messages = emptyList(); saveChat() }) { Icon(Icons.Default.Delete, "Очистить чат") }
  }) },
  bottomBar = { Surface(tonalElevation = 3.dp) { Column(Modifier.fillMaxWidth().padding(10.dp)) {
   Row(verticalAlignment = Alignment.Bottom) {
    OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Напиши сообщение…") }, maxLines = 5, enabled = !busy, shape = RoundedCornerShape(22.dp))
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
    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать файл модели .litertlm") }
    Text(model?.let { "Модель: ${it.name} • ${it.length() / 1048576} МБ" } ?: "Модель не установлена")
    Spacer(Modifier.height(12.dp))
    Button(enabled = !busy && model != null && chat == null, modifier = Modifier.fillMaxWidth(), onClick = {
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
    Text("Если файл модели уже установлен в старой версии WebForge AI, выбери его через файловый менеджер.")
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
