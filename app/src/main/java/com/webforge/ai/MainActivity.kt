package com.webforge.ai
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
 override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme(colorScheme = darkColorScheme()) { ForgeApp() } } }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ForgeApp() {
 val ctx=LocalContext.current
 val prefs=remember { ctx.getSharedPreferences("forge",0) }
 var endpoint by remember { mutableStateOf(prefs.getString("endpoint","https://api.openai.com/v1/chat/completions") ?: "") }
 var model by remember { mutableStateOf(prefs.getString("model","gpt-4o-mini") ?: "") }
 var key by remember { mutableStateOf(prefs.getString("key","") ?: "") }
 var prompt by remember { mutableStateOf("") }
 var html by remember { mutableStateOf("<html><body style='background:#101827;color:white;font:18px sans-serif;padding:30px'><h1>WebForge AI</h1><p>Опишите свой проект.</p></body></html>") }
 var status by remember { mutableStateOf("Подключите модель в настройках.") }
 var busy by remember { mutableStateOf(false) }
 var preview by remember { mutableStateOf(false) }
 var settings by remember { mutableStateOf(false) }
 val scope= rememberCoroutineScope()
 Scaffold(topBar={ TopAppBar(title={Text("WebForge AI")},actions={IconButton(onClick={settings=!settings}){Icon(Icons.Default.Settings,null)}}) },
 bottomBar={NavigationBar { NavigationBarItem(selected=!preview,onClick={preview=false},icon={Icon(Icons.Default.Code,null)},label={Text("Создание")}); NavigationBarItem(selected=preview,onClick={preview=true},icon={Icon(Icons.Default.Preview,null)},label={Text("Просмотр")}) }}) { pad ->
 Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
 if(settings) {
  Text("Подключение ИИ",style=MaterialTheme.typography.titleLarge)
  OutlinedTextField(endpoint,{endpoint=it},label={Text("HTTPS API endpoint")},modifier=Modifier.fillMaxWidth())
  OutlinedTextField(model,{model=it},label={Text("Модель")},modifier=Modifier.fillMaxWidth())
  OutlinedTextField(key,{key=it},label={Text("API-ключ")},modifier=Modifier.fillMaxWidth())
  Button(onClick={prefs.edit().putString("endpoint",endpoint.trim()).putString("model",model.trim()).putString("key",key.trim()).apply();settings=false;status="Настройки сохранены на устройстве."}){Text("Сохранить")}
  Text("Используйте доверенный HTTPS endpoint. Ключ хранится локально.",style=MaterialTheme.typography.bodySmall)
 } else if(!preview) {
  Text("Что создаём?",style=MaterialTheme.typography.headlineSmall)
  Text("Опишите сайт, приложение или игру. ИИ подготовит HTML, CSS и JavaScript.")
  Spacer(Modifier.height(12.dp))
  OutlinedTextField(prompt,{prompt=it},modifier=Modifier.fillMaxWidth().weight(1f,false),minLines=5,label={Text("Опишите идею")})
  Spacer(Modifier.height(12.dp))
  Button(enabled=!busy && prompt.isNotBlank() && key.isNotBlank(),onClick={
   busy=true;status="ИИ создаёт проект…"
   scope.launch { try { html=generate(endpoint,model,key,prompt);status="Готово. Откройте предпросмотр.";preview=true } catch(e:Exception){status="Ошибка: ${e.message}"} finally{busy=false} }
  },modifier=Modifier.fillMaxWidth()){Text(if(busy)"Создаю…" else "Создать с ИИ")}
  Spacer(Modifier.height(8.dp));Text(status,style=MaterialTheme.typography.bodySmall)
 } else {
  Text("Предпросмотр",style=MaterialTheme.typography.titleLarge)
  AndroidView(modifier=Modifier.fillMaxSize(),factory={ WebView(it).apply { settings.javaScriptEnabled=true;settings.domStorageEnabled=true;webViewClient=WebViewClient();loadDataWithBaseURL(null,html,"text/html","UTF-8",null) } },update={it.loadDataWithBaseURL(null,html,"text/html","UTF-8",null)})
 }
 }
 }
}
private suspend fun generate(endpoint:String,model:String,key:String,prompt:String):String=withContext(Dispatchers.IO){
 require(endpoint.startsWith("https://")){"Endpoint должен использовать HTTPS"}
 require(model.isNotBlank() && key.isNotBlank()){"Заполните модель и API-ключ"}
 val messages=JSONArray().put(JSONObject().put("role","system").put("content","You are WebForge AI. Return only a complete self-contained responsive HTML document with inline CSS and JavaScript. No markdown fences.")).put(JSONObject().put("role","user").put("content",prompt))
 val body=JSONObject().put("model",model).put("messages",messages).put("temperature",0.7).toString().toRequestBody("application/json".toMediaType())
 val req=Request.Builder().url(endpoint).header("Authorization","Bearer $key").post(body).build()
 OkHttpClient.Builder().callTimeout(120,TimeUnit.SECONDS).build().newCall(req).execute().use { res ->
  val raw=res.body?.string().orEmpty()
  if(!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}: ${raw.take(250)}")
  JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim().removePrefix("```html").removePrefix("```").removeSuffix("```").trim()
 }
}
