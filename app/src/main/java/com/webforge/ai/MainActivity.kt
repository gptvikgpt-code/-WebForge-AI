package com.webforge.ai

import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { MaterialTheme(colorScheme = darkColorScheme()) { ForgeApp() } }
 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgeApp() {
 val ctx = LocalContext.current
 val scope = rememberCoroutineScope()
 val executor = remember { Executors.newSingleThreadExecutor() }
 val modelDispatcher = remember { executor.asCoroutineDispatcher() }
 var modelFile by remember { mutableStateOf(File(ctx.filesDir, "webforge-model.litertlm").takeIf { it.exists() }) }
 var engine by remember { mutableStateOf<Engine?>(null) }
 var conversation by remember { mutableStateOf<Conversation?>(null) }
 var prompt by remember { mutableStateOf("") }
 var html by remember { mutableStateOf(defaultPage()) }
 var status by remember { mutableStateOf(if (modelFile != null) "Локальная модель найдена. Нажмите «Загрузить модель»." else "Выберите локальную модель .litertlm. Регистрация и API-ключ не нужны.") }
 var busy by remember { mutableStateOf(false) }
 var downloadProgress by remember { mutableFloatStateOf(-1f) }
 var preview by remember { mutableStateOf(false) }
 var showSettings by remember { mutableStateOf(false) }

 val openModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
  if (uri != null) {
   busy = true
   status = "Копирую модель в память приложения…"
   scope.launch {
    try {
     val copied = withContext(Dispatchers.IO) {
      val target = File(ctx.filesDir, "webforge-model.litertlm")
      ctx.contentResolver.openInputStream(uri).use { input ->
       requireNotNull(input) { "Не удалось открыть файл модели" }.copyTo(target.outputStream())
      }
      target
     }
     modelFile = copied
     status = "Модель скопирована (${copied.length() / (1024 * 1024)} МБ). Теперь загрузите её."
    } catch (e: Exception) { status = "Ошибка импорта модели: ${e.message}" }
    finally { busy = false }
   }
  }
 }
 val saveHtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
  if (uri != null) scope.launch {
   try {
    withContext(Dispatchers.IO) {
     ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(html) } ?: error("Не удалось сохранить файл")
    }
    status = "HTML-проект сохранён."
   } catch (e: Exception) { status = "Ошибка сохранения: ${e.message}" }
  }
 }

 DisposableEffect(Unit) {
  onDispose {
   executor.execute {
    try { conversation?.close() } catch (_: Exception) {}
    try { engine?.close() } catch (_: Exception) {}
   }
   executor.shutdown()
  }
 }

 Scaffold(
  topBar = { TopAppBar(title = { Text("WebForge AI") }, actions = {
   IconButton(onClick = { showSettings = !showSettings; preview = false }) { Icon(Icons.Default.Settings, contentDescription = "Модель") }
  }) },
  bottomBar = { NavigationBar {
   NavigationBarItem(selected = !preview && !showSettings, onClick = { preview = false; showSettings = false }, icon = { Icon(Icons.Default.Code, null) }, label = { Text("Создание") })
   NavigationBarItem(selected = preview, onClick = { preview = true; showSettings = false }, icon = { Icon(Icons.Default.Preview, null) }, label = { Text("Просмотр") })
  } }
 ) { pad ->
  Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
   if (busy) {
    if (downloadProgress >= 0f) {
     LinearProgressIndicator(progress = { downloadProgress / 100f }, modifier = Modifier.fillMaxWidth())
     Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(status, style = MaterialTheme.typography.bodySmall)
      Text("${downloadProgress.toInt()}%", style = MaterialTheme.typography.labelLarge)
     }
    } else {
     LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
     Text(status, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(10.dp))
   }
   when {
    showSettings -> {
     Text("Локальный ИИ", style = MaterialTheme.typography.headlineSmall)
     Spacer(Modifier.height(8.dp))
     Text("Модель работает прямо на телефоне. Аккаунт, API-ключ и платная подписка не требуются. Модель скачивается отдельно и хранится на устройстве.")
     Spacer(Modifier.height(12.dp))
     Text("Стартовая модель: Qwen3 0.6B LiteRT-LM. Файл около 328 МБ. Скачивается один раз, затем генерация выполняется на телефоне без интернета.")
     Spacer(Modifier.height(8.dp))
     Button(
      onClick = {
       busy = true
       downloadProgress = -1f
       status = "Подключаюсь к серверу модели Hugging Face…"
       scope.launch {
        try {
         val target = withContext(Dispatchers.IO) {
          val finalFile = File(ctx.filesDir, "webforge-model.litertlm")
          val tempFile = File(ctx.filesDir, "webforge-model.download")
          val connection = URL("https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm?download=true").openConnection() as HttpURLConnection
          connection.connectTimeout = 30000
          connection.readTimeout = 90000
          connection.instanceFollowRedirects = true
          connection.connect()
          if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
          withContext(Dispatchers.Main) { status = "Соединение установлено. Скачиваю файл модели…" }
          val expected = connection.contentLengthLong
          var downloaded = 0L
          if (expected <= 0L) withContext(Dispatchers.Main) { downloadProgress = -1f }
          connection.inputStream.use { input ->
           tempFile.outputStream().buffered().use { output ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
             val count = input.read(buffer)
             if (count < 0) break
             output.write(buffer, 0, count)
             downloaded += count
             if (expected > 0L) {
              val percent = (downloaded * 100f / expected).coerceIn(0f, 99.9f)
              withContext(Dispatchers.Main) { downloadProgress = percent }
             }
            }
           }
          }
          withContext(Dispatchers.Main) { if (expected > 0L) downloadProgress = 100f }
          connection.disconnect()
          if (tempFile.length() < 250L * 1024L * 1024L || (expected > 0 && tempFile.length() != expected)) {
           tempFile.delete()
           error("Файл модели скачан не полностью")
          }
          if (finalFile.exists()) finalFile.delete()
          if (!tempFile.renameTo(finalFile)) error("Не удалось сохранить модель")
          finalFile
         }
         modelFile = target
         downloadProgress = -1f
         status = "Модель скачана: ${target.length() / (1024 * 1024)} МБ. Нажмите «Загрузить модель»."
        } catch (e: Exception) {
         val cause = generateSequence(e as Throwable?) { it.cause }.firstOrNull { it is java.net.UnknownHostException }
         status = if (cause != null) {
          "Не удалось найти сервер Hugging Face (ошибка DNS). Это не загрузка: файл не передавался. Отключите VPN/фильтр DNS или смените сеть и попробуйте снова. Можно также выбрать файл .litertlm вручную."
         } else {
          "Ошибка скачивания модели: ${e.message ?: e.javaClass.simpleName}"
         }
        } finally { downloadProgress = -1f; busy = false }
       }
      },
      enabled = !busy && conversation == null,
      modifier = Modifier.fillMaxWidth()
     ) { Text(if (busy) "Скачивание…" else "Скачать бесплатную модель (≈328 МБ)") }
     Spacer(Modifier.height(8.dp))
     OutlinedButton(onClick = { openModel.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(if (modelFile == null) "Выбрать файл модели .litertlm" else "Заменить файл модели") }
     Text(modelFile?.let { "Файл: ${it.name} • ${it.length() / (1024 * 1024)} МБ" } ?: "Модель ещё не установлена", style = MaterialTheme.typography.bodySmall)
     Spacer(Modifier.height(8.dp))
     Button(onClick = {
      val file = modelFile
      if (file == null) { status = "Сначала выберите файл .litertlm."; return@Button }
      busy = true
      status = "Загружаю локальную модель. Первый запуск может занять время…"
      scope.launch(modelDispatcher) {
       var created: Engine? = null
       var initialized = false
       try {
        created = Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(), cacheDir = ctx.cacheDir.absolutePath))
        created.initialize()
        initialized = true
        val newConversation = created.createConversation(ConversationConfig(
         systemInstruction = Contents.of("Ты WebForge AI — автономный помощник разработчика. Создавай полноценные адаптивные веб-проекты. На запрос о сайте или игре возвращай только полный HTML-документ с CSS и JavaScript внутри, без markdown-ограждений. Все кнопки должны работать. Интерфейс на русском.")
        ))
        conversation?.close()
        engine?.close()
        engine = created
        conversation = newConversation
        withContext(Dispatchers.Main) { status = "Локальная модель готова. Можно работать без интернета."; busy = false }
       } catch (e: Exception) {
        if (initialized) try { created?.close() } catch (_: Exception) {}
        withContext(Dispatchers.Main) { status = "Не удалось загрузить модель: ${e.message}"; busy = false }
       }
      }
     }, enabled = !busy && modelFile != null && conversation == null, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Загрузка…" else "Загрузить модель") }
     if (conversation != null) Text("Модель активна в памяти приложения.", color = MaterialTheme.colorScheme.primary)
     Spacer(Modifier.height(8.dp))
     Text(status, style = MaterialTheme.typography.bodySmall)
    }
    preview -> {
     Text("Предпросмотр", style = MaterialTheme.typography.titleLarge)
     Spacer(Modifier.height(8.dp))
     AndroidView(modifier = Modifier.fillMaxWidth().heightIn(min = 500.dp), factory = { WebView(it).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      webViewClient = WebViewClient()
      loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
     } }, update = { it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) })
     Spacer(Modifier.height(8.dp))
     OutlinedButton(onClick = { saveHtml.launch("webforge-project.html") }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить HTML-проект") }
    }
    else -> {
     Text("Что создаём?", style = MaterialTheme.typography.headlineSmall)
     Spacer(Modifier.height(8.dp))
     Text("Опишите сайт, приложение или игру. Локальная модель подготовит HTML, CSS и JavaScript.")
     Spacer(Modifier.height(12.dp))
     OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), minLines = 5, label = { Text("Опишите идею") })
     Spacer(Modifier.height(12.dp))
     Button(enabled = !busy && prompt.isNotBlank() && conversation != null, onClick = {
      busy = true
      status = "Локальный ИИ создаёт проект…"
      val request = "Создай готовый веб-проект по описанию: $prompt. Верни только полный HTML-документ."
      scope.launch(modelDispatcher) {
       try {
        val output = StringBuilder()
        checkNotNull(conversation).sendMessageAsync(request).collect { output.append(it.toString()) }
        val result = output.toString().trim().removePrefix("```html").removePrefix("```").removeSuffix("```").trim()
        require(result.contains("<html", true) || result.contains("<!doctype", true)) { "Модель не вернула HTML. Уточните запрос." }
        withContext(Dispatchers.Main) { html = result; status = "Проект создан локально. Откройте «Просмотр»."; preview = true; busy = false }
       } catch (e: Exception) {
        withContext(Dispatchers.Main) { status = "Ошибка локальной генерации: ${e.message}"; busy = false }
       }
      }
     }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Создаю…" else "Создать с ИИ") }
     Spacer(Modifier.height(8.dp))
     Text(status, style = MaterialTheme.typography.bodySmall)
     if (conversation == null) {
      Spacer(Modifier.height(6.dp))
      Text("Сначала откройте ⚙ и установите локальную модель. Регистрация не нужна.", style = MaterialTheme.typography.bodySmall)
     }
    }
   }
  }
 }
}

private fun defaultPage() = """<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Новый проект</title><style>body{font:16px system-ui;background:#111827;color:#f8fafc;padding:32px}main{max-width:760px;margin:auto}button{padding:12px 18px;border:0;border-radius:12px;background:#a78bfa;color:#111827}</style></head>
<body><main><h1>Новый проект</h1><p>Выберите локальную модель и опишите, что нужно создать.</p><button onclick="alert('Проект работает')">Тестовая кнопка</button></main></body></html>"""
