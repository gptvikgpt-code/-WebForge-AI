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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
 val partialModelFile = remember { File(ctx.filesDir, "webforge-model.download") }
 var status by remember { mutableStateOf(if (modelFile != null) "Локальная модель найдена. Нажмите «Загрузить модель»." else if (partialModelFile.exists() && partialModelFile.length() > 0L) "Найдена незавершённая загрузка: ${partialModelFile.length() / (1024 * 1024)} МБ. Нажмите «Продолжить загрузку»." else "Выберите локальную модель .litertlm. Регистрация и API-ключ не нужны.") }
 var busy by remember { mutableStateOf(false) }
 var generationJob by remember { mutableStateOf<Job?>(null) }
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
          val tempFile = File(ctx.filesDir, "webforge-model-1.7b.download")
          downloadModelResumable(
           "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm?download=true",
           tempFile
          ) { percent ->
           withContext(Dispatchers.Main) { downloadProgress = percent }
          }
          if (tempFile.length() < 320L * 1024L * 1024L) {
           error("Файл модели неполный: ${tempFile.length() / (1024 * 1024)} МБ. Частичная загрузка сохранена — повторите, докачивание продолжится.")
          }
          if (finalFile.exists()) finalFile.delete()
          if (!tempFile.renameTo(finalFile)) error("Не удалось сохранить модель")
          finalFile
         }
         modelFile = target
         downloadProgress = -1f
         status = "Модель скачана: ${target.length() / (1024 * 1024)} МБ. Нажмите «Загрузить модель»."
        } catch (e: Exception) {
         val unknownHost = generateSequence(e as Throwable?) { it.cause }.filterIsInstance<java.net.UnknownHostException>().firstOrNull()
         status = if (unknownHost != null) {
          val failedHost = unknownHost.message?.substringAfter("host \"")?.substringBefore("\"")?.takeIf { it.isNotBlank() }
          "Не удалось разрешить адрес ${failedHost ?: "сервера модели"} (DNS). Браузер и приложение могут идти через разные VPN-маршруты. Разрешите WebForge AI использовать VPN в настройках VPN-приложения (раздельное туннелирование), затем повторите загрузку. Также проверьте Частный DNS Android. Файл не скачан."
         } else {
          "Ошибка скачивания модели: ${e.message ?: e.javaClass.simpleName}. Проверьте VPN и сеть, затем повторите."
         }
        } finally { downloadProgress = -1f; busy = false }
       }
      },
      enabled = !busy && conversation == null,
      modifier = Modifier.fillMaxWidth()
     ) { Text(if (busy) "Скачивание…" else if (partialModelFile.exists() && partialModelFile.length() > 0L) "Продолжить загрузку (${partialModelFile.length() / (1024 * 1024)} МБ сохранено)" else "Скачать бесплатную модель (≈328 МБ)") }
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
      generationJob = scope.launch(modelDispatcher) {
       try {
        val output = StringBuilder()
        checkNotNull(conversation).sendMessageAsync(request).collect { output.append(it.toString()) }
        val result = output.toString().trim().removePrefix("```html").removePrefix("```").removeSuffix("```").trim()
        require(result.contains("<html", true) || result.contains("<!doctype", true)) { "Модель не вернула HTML. Уточните запрос." }
        withContext(Dispatchers.Main) { html = result; status = "Проект создан локально. Откройте «Просмотр»."; preview = true; busy = false; android.widget.Toast.makeText(ctx, "Проект успешно создан!", android.widget.Toast.LENGTH_LONG).show() }
       } catch (e: CancellationException) {
        withContext(Dispatchers.Main) { status = "Создание остановлено пользователем."; busy = false; generationJob = null }
       } catch (e: Exception) {
        withContext(Dispatchers.Main) { status = "Ошибка локальной генерации: ${e.message}"; busy = false; generationJob = null }
       } finally {
        withContext(Dispatchers.Main) { busy = false; generationJob = null }
       }
      }
     }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Создаю…" else "Создать с ИИ") }
     if (busy && generationJob != null) {
      Spacer(Modifier.height(8.dp))
      OutlinedButton(onClick = { generationJob?.cancel(); status = "Останавливаю создание…" }, modifier = Modifier.fillMaxWidth()) { Text("■ Стоп создания") }
     }
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


/**
 * Resumable Hugging Face download. Keeps partial bytes after network failures and
 * continues with an HTTP Range request instead of restarting the 300+ MB file.
 */
private suspend fun downloadModelResumable(
 url: String,
 tempFile: File,
 onProgress: suspend (Float) -> Unit
) {
 var attempt = 0
 var totalBytes = -1L
 var lastError: Exception? = null
 while (attempt < 8) {
  attempt++
  val offset = if (tempFile.exists()) tempFile.length() else 0L
  var connection: HttpURLConnection? = null
  try {
   connection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 45000
    readTimeout = 180000
    useCaches = false
    instanceFollowRedirects = true
    requestMethod = "GET"
    setRequestProperty("User-Agent", "WebForge-AI-Android/1.1")
    setRequestProperty("Accept", "application/octet-stream")
    if (offset > 0L) setRequestProperty("Range", "bytes=${offset}-")
   }
   connection.connect()
   val code = connection.responseCode
   if (offset > 0L && code == HttpURLConnection.HTTP_OK) {
    // Server/CDN ignored Range: restart cleanly rather than append duplicate bytes.
    tempFile.delete()
    totalBytes = -1L
    connection.disconnect()
    connection = null
    continue
   }
   if (offset > 0L && code != 206) error("HTTP $code при попытке продолжить загрузку")
   if (offset == 0L && code !in 200..299) error("HTTP $code")
   val range = connection.getHeaderField("Content-Range")
   if (code == 206 && range != null) {
    totalBytes = range.substringAfterLast('/').toLongOrNull() ?: totalBytes
   } else if (offset == 0L) {
    totalBytes = connection.contentLengthLong
   }
   java.io.FileOutputStream(tempFile, code == 206 && offset > 0L).buffered().use { output ->
    connection.inputStream.use { input ->
     val buffer = ByteArray(256 * 1024)
     var downloaded = if (code == 206 && offset > 0L) offset else 0L
     while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      output.write(buffer, 0, count)
      downloaded += count
      if (totalBytes > 0L) onProgress((downloaded * 100f / totalBytes).coerceIn(0f, 99.9f))
     }
     output.flush()
    }
   }
   if (totalBytes > 0L && tempFile.length() != totalBytes) {
    throw java.io.IOException("Получено ${tempFile.length()} из $totalBytes байт")
   }
   if (tempFile.length() >= 320L * 1024L * 1024L) {
    onProgress(100f)
    connection.disconnect()
    return
   }
   throw java.io.IOException("Загрузка завершилась раньше ожидаемого размера")
  } catch (e: Exception) {
   lastError = e
   // Preserve the partial file. The next attempt resumes from its current length.
   if (attempt < 8) kotlinx.coroutines.delay((attempt * 1500L).coerceAtMost(10000L))
  } finally {
   connection?.disconnect()
  }
 }
 throw java.io.IOException(
  "Не удалось завершить загрузку после 8 попыток. Сохранено ${tempFile.length() / (1024 * 1024)} МБ; повторите позже — докачивание продолжится. ${lastError?.message ?: ""}",
  lastError
 )
}

private fun defaultPage() = """<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Новый проект</title><style>body{font:16px system-ui;background:#111827;color:#f8fafc;padding:32px}main{max-width:760px;margin:auto}button{padding:12px 18px;border:0;border-radius:12px;background:#a78bfa;color:#111827}</style></head>
<body><main><h1>Новый проект</h1><p>Выберите локальную модель и опишите, что нужно создать.</p><button onclick="alert('Проект работает')">Тестовая кнопка</button></main></body></html>"""
