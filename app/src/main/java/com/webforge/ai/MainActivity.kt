package com.webforge.ai

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
    val prefs = remember { ctx.getSharedPreferences("forge", 0) }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "https://openrouter.ai/api/v1/chat/completions") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "openrouter/free") ?: "") }
    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var prompt by remember { mutableStateOf("") }
    var html by remember { mutableStateOf("<!doctype html><html><meta name='viewport' content='width=device-width, initial-scale=1'><body style='background:#101827;color:white;font:18px sans-serif;padding:30px'><h1>WebForge AI</h1><p>Опишите свой проект.</p></body></html>") }
    var status by remember { mutableStateOf("Введите идею проекта и подключите ИИ в настройках.") }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("WebForge AI") }, actions = {
                IconButton(onClick = { settings = !settings; preview = false }) {
                    Icon(Icons.Default.Settings, contentDescription = "Настройки")
                }
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = !preview && !settings, onClick = { preview = false; settings = false }, icon = { Icon(Icons.Default.Code, null) }, label = { Text("Создание") })
                NavigationBarItem(selected = preview, onClick = { preview = true; settings = false }, icon = { Icon(Icons.Default.Preview, null) }, label = { Text("Просмотр") })
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            when {
                settings -> {
                    Text("Подключение ИИ", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Рекомендуемый бесплатный вариант: OpenRouter Free. Он направляет запросы на доступные бесплатные модели. У бесплатных моделей есть дневные и минутные лимиты.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            endpoint = "https://openrouter.ai/api/v1/chat/completions"
                            model = "openrouter/free"
                            status = "Выбран бесплатный маршрутизатор OpenRouter. Создайте бесплатный API-ключ на openrouter.ai."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Выбрать бесплатный OpenRouter") }
                    Spacer(Modifier.height(8.dp))
                    Text("Другие совместимые сервисы можно подключить вручную. API-ключ создаётся в личном кабинете провайдера. Подписка ChatGPT не является API-ключом.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = endpoint, onValueChange = { endpoint = it },
                        label = { Text("HTTPS API endpoint") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = model, onValueChange = { model = it },
                        label = { Text("Название модели") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text("API-ключ") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Скрыть" else "Показать") } }
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            prefs.edit().putString("endpoint", endpoint.trim()).putString("model", model.trim()).putString("key", key.trim()).apply()
                            status = "Настройки сохранены на устройстве."
                            settings = false
                        },
                        enabled = endpoint.isNotBlank() && model.isNotBlank() && key.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить настройки") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            busy = true
                            status = "Проверяю подключение…"
                            scope.launch {
                                try {
                                    val answer = testConnection(endpoint.trim(), model.trim(), key.trim())
                                    status = "Подключение работает. Ответ модели: $answer"
                                    prefs.edit().putString("endpoint", endpoint.trim()).putString("model", model.trim()).putString("key", key.trim()).apply()
                                } catch (e: Exception) {
                                    status = "Не удалось подключиться: ${e.message ?: "неизвестная ошибка"}"
                                } finally { busy = false }
                            }
                        },
                        enabled = !busy && endpoint.isNotBlank() && model.isNotBlank() && key.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "Проверка…" else "Проверить подключение") }
                    Spacer(Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Ключ хранится локально на устройстве. Не отправляйте его в чат и не добавляйте в GitHub.", style = MaterialTheme.typography.bodySmall)
                }
                preview -> {
                    Text("Предпросмотр", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 500.dp),
                        factory = { WebView(it).apply {
                            this.settings.javaScriptEnabled = true
                            this.settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                        } },
                        update = { it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) }
                    )
                }
                else -> {
                    Text("Что создаём?", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Опишите сайт, приложение или игру. ИИ подготовит HTML, CSS и JavaScript.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                        minLines = 5, label = { Text("Опишите идею") }
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = !busy && prompt.isNotBlank() && key.isNotBlank(),
                        onClick = {
                            busy = true
                            status = "ИИ создаёт проект…"
                            scope.launch {
                                try {
                                    html = generate(endpoint, model, key, prompt)
                                    status = "Проект создан. Откройте вкладку «Просмотр»."
                                    preview = true
                                } catch (e: Exception) {
                                    status = "Ошибка генерации: ${e.message ?: "неизвестная ошибка"}"
                                } finally { busy = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "Создаю…" else "Создать с ИИ") }
                    Spacer(Modifier.height(8.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    if (key.isBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Сначала откройте ⚙ и подключите API-ключ.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun makeRequest(endpoint: String, model: String, key: String, prompt: String): Request {
    require(endpoint.startsWith("https://")) { "Адрес API должен начинаться с https://" }
    require(model.isNotBlank()) { "Укажите название модели в настройках" }
    require(key.isNotBlank()) { "Укажите API-ключ в настройках" }
    val messages = JSONArray()
        .put(JSONObject().put("role", "system").put("content", "You are WebForge AI, an expert web developer. Return only a complete self-contained responsive HTML document with inline CSS and JavaScript. Do not use markdown fences. Make all controls functional."))
        .put(JSONObject().put("role", "user").put("content", prompt))
    val body = JSONObject()
        .put("model", model)
        .put("messages", messages)
        .put("temperature", 0.7)
        .toString().toRequestBody("application/json".toMediaType())
    return Request.Builder().url(endpoint).header("Authorization", "Bearer $key").post(body).build()
}

private suspend fun testConnection(endpoint: String, model: String, key: String): String = withContext(Dispatchers.IO) {
    val request = makeRequest(endpoint, model, key, "Reply with the single word OK.")
    val client = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
    client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${raw.take(300)}")
        val text = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        if (text.isBlank()) throw IllegalStateException("Модель вернула пустой ответ")
        text.take(100)
    }
}

private suspend fun generate(endpoint: String, model: String, key: String, prompt: String): String = withContext(Dispatchers.IO) {
    val request = makeRequest(endpoint, model, key, prompt)
    val client = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()
    client.newCall(request).execute().use { response ->
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${raw.take(300)}")
        val result = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            .trim().removePrefix("```html").removePrefix("```").removeSuffix("```").trim()
        if (result.isBlank()) throw IllegalStateException("Модель вернула пустой HTML")
        result
    }
}
