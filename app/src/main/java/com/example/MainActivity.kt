package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity(), SensorEventListener, TextToSpeech.OnInitListener {

  private var webView: WebView? = null
  private var sensorManager: SensorManager? = null
  private var accelerometer: Sensor? = null
  private var textToSpeech: TextToSpeech? = null
  private var sharedTextReceived: String? = null
  private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

  // File Picker Launcher
  private val filePickerLauncher =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (fileChooserCallback != null) {
          val clipData = result.data?.clipData
          val data = result.data?.data
          val results =
              when {
                clipData != null -> {
                  val list = mutableListOf<Uri>()
                  for (i in 0 until clipData.itemCount) {
                    list.add(clipData.getItemAt(i).uri)
                  }
                  list.toTypedArray()
                }
                data != null -> arrayOf(data)
                else -> null
              }
          fileChooserCallback?.onReceiveValue(results)
          fileChooserCallback = null
        }
      }

  // Voice Recognizer Launcher
  private val speechLauncher =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spokenText =
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spokenText.isNullOrBlank()) {
          webView?.post {
            val sanitized = spokenText.replace("'", "\\'").replace("\n", "\\n")
            webView?.evaluateJavascript(
                "document.getElementById('composer-input').value = '$sanitized'; window.showToast('Voice transcribed!', 'success');",
                null)
          }
        }
      }

  // Audio Permission Launcher
  private val audioPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
          launchSpeechRecognition()
        } else {
          Toast.makeText(this, "Microphone permission required for speech", Toast.LENGTH_SHORT).show()
        }
        webView?.post {
          webView?.evaluateJavascript("if (window.refreshPermissionsUi) window.refreshPermissionsUi();", null)
        }
      }

  // Notification Permission Launcher (Android 13+)
  private val notificationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val msg = if (granted) "Notifications enabled" else "Notification permission denied"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        webView?.post {
          webView?.evaluateJavascript("if (window.refreshPermissionsUi) window.refreshPermissionsUi();", null)
        }
      }

  // Storage Manager Launcher
  private val storagePermissionLauncher =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        webView?.post {
          webView?.evaluateJavascript("if (window.refreshPermissionsUi) window.refreshPermissionsUi();", null)
        }
      }

  // Multiple Permissions Launcher (Quick Grant All)
  private val multiplePermissionsLauncher =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        var grantedCount = 0
        results.forEach { (_, granted) ->
          if (granted) grantedCount++
        }
        val total = results.size
        Toast.makeText(
            this,
            "Granted $grantedCount of $total requested permissions",
            Toast.LENGTH_SHORT
        ).show()
        webView?.post {
          webView?.evaluateJavascript(
              "if (window.refreshPermissionsUi) window.refreshPermissionsUi(); window.showToast('Permissions updated ($grantedCount/$total granted)', 'info');",
              null)
        }

        // If MANAGE_EXTERNAL_STORAGE is not granted on Android 11+, prompt user for special settings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
          webView?.postDelayed({
            try {
              val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
              }
              storagePermissionLauncher.launch(intent)
            } catch (_: Exception) {
              val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
              storagePermissionLauncher.launch(intent)
            }
          }, 1200)
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    sanitizeWebViewVariationsSeed()
    enableEdgeToEdge()

    // Handle shared text if launched via SEND intent
    if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
      sharedTextReceived = intent.getStringExtra(Intent.EXTRA_TEXT)
    }

    // Initialize Sensor Manager for real-time telemetry into WebGL
    sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Initialize Native TTS
    textToSpeech = TextToSpeech(this, this)

    setContent {
      MyApplicationTheme {
        Surface(
            modifier =
                Modifier.fillMaxSize()
                    .background(Color(0xFF0F0F11))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .testTag("yslam_root_surface"),
            color = Color(0xFF0F0F11)) {
          YslamStudioScreen(
              onWebViewCreated = { wv ->
                webView = wv
                setupWebView(wv)
              },
              onBackPressed = {
                if (webView?.canGoBack() == true) {
                  webView?.goBack()
                } else {
                  finish()
                }
              })
        }
      }
    }
  }

  private val okHttpClient =
      OkHttpClient.Builder()
          .connectTimeout(4, TimeUnit.SECONDS)
          .readTimeout(90, TimeUnit.SECONDS)
          .writeTimeout(30, TimeUnit.SECONDS)
          .build()

  @SuppressLint("SetJavaScriptEnabled")
  private fun setupWebView(wv: WebView) {
    wv.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      databaseEnabled = true
      allowFileAccess = true
      allowContentAccess = true
      loadWithOverviewMode = true
      useWideViewPort = true
      mediaPlaybackRequiresUserGesture = false
      cacheMode = WebSettings.LOAD_DEFAULT
      mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
      allowFileAccessFromFileURLs = true
      allowUniversalAccessFromFileURLs = true
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        safeBrowsingEnabled = false
      }
    }

    wv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

    // Add JavaScript Interface Bridge
    wv.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

    wv.webChromeClient =
        object : WebChromeClient() {
          override fun onPermissionRequest(request: PermissionRequest?) {
            request?.grant(request.resources)
          }

          override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
            android.util.Log.d(
                "YSLAM_WebView",
                "${consoleMessage?.message()} [${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}]")
            return true
          }

          override fun onShowFileChooser(
              webView: WebView?,
              filePathCallback: ValueCallback<Array<Uri>>?,
              fileChooserParams: FileChooserParams?
          ): Boolean {
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = filePathCallback

            val intent =
                Intent(Intent.ACTION_GET_CONTENT).apply {
                  addCategory(Intent.CATEGORY_OPENABLE)
                  type = "*/*"
                  putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            filePickerLauncher.launch(Intent.createChooser(intent, "Select Code Files or Archive"))
            return true
          }
        }

    wv.webViewClient =
        object : WebViewClient() {
          override fun onReceivedError(
              view: WebView?,
              request: android.webkit.WebResourceRequest?,
              error: android.webkit.WebResourceError?
          ) {
            super.onReceivedError(view, request, error)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
              android.util.Log.w(
                  "YSLAM_WebView",
                  "Resource load notice: ${error?.description} (${error?.errorCode}) for ${request?.url}")
            }
          }

          override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // Inject shared text if present
            sharedTextReceived?.let { text ->
              val sanitized = text.replace("'", "\\'").replace("\n", "\\n")
              wv.evaluateJavascript(
                  "document.getElementById('composer-input').value = '$sanitized'; window.showToast('Imported shared text', 'success');",
                  null)
              sharedTextReceived = null
            }
          }
        }

    wv.loadUrl("file:///android_asset/index.html")
  }

  override fun onResume() {
    super.onResume()
    accelerometer?.let { sensor ->
      sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }
  }

  override fun onPause() {
    super.onPause()
    sensorManager?.unregisterListener(this)
    textToSpeech?.stop()
  }

  override fun onDestroy() {
    super.onDestroy()
    textToSpeech?.shutdown()
    webView?.destroy()
  }

  /**
   * Sanitizes corrupted or 0-byte Chromium WebView experiment variation seeds.
   * Resolves Chromium variations_seed_loader.cc: 'Seed missing signature' errors.
   */
  private fun sanitizeWebViewVariationsSeed() {
    try {
      val dataDir = applicationContext.dataDir ?: return
      val candidateDirs =
          listOf(
              File(dataDir, "app_webview"),
              File(dataDir, "app_webview_variations"),
              File(dataDir, "app_webview/variations_seed")
          )
      for (dir in candidateDirs) {
        if (dir.exists() && dir.isDirectory) {
          val seedFiles = dir.listFiles { _, name -> name.contains("variations_seed") }
          seedFiles?.forEach { seedFile ->
            if (seedFile.exists() && seedFile.length() < 128) {
              seedFile.delete()
            }
          }
        }
      }

      val prefsNames = listOf("variations_seed_loader", "aw_variations_seed_prefs", "WebViewChromiumPrefs")
      for (name in prefsNames) {
        val sp = getSharedPreferences(name, Context.MODE_PRIVATE)
        if (sp != null && sp.contains("variations_seed") && !sp.contains("variations_seed_signature")) {
          sp.edit().clear().apply()
        }
      }
    } catch (_: Exception) {
      // Non-fatal background cleanup
    }
  }

  // SensorEventListener implementation
  override fun onSensorChanged(event: SensorEvent?) {
    if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
      val x = event.values[0]
      val y = event.values[1]
      val z = event.values[2]
      webView?.post {
        webView?.evaluateJavascript(
            "if(window.onAndroidSensorData) { window.onAndroidSensorData($x, $y, $z); }", null)
      }
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

  // TTS Init Listener
  override fun onInit(status: Int) {
    if (status == TextToSpeech.SUCCESS) {
      textToSpeech?.language = Locale.US
    }
  }

  private fun launchSpeechRecognition() {
    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(
              RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
          putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak prompt for YSLAM...")
        }
    speechLauncher.launch(intent)
  }

  // JavaScript Interface Bridge exposed to Web Studio
  inner class AndroidBridge {

    @JavascriptInterface
    fun vibrate(milliseconds: Long) {
      runOnUiThread {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val vibratorManager =
              getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
          vibratorManager?.defaultVibrator?.vibrate(
              VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
          @Suppress("DEPRECATION")
          val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
          @Suppress("DEPRECATION")
          vibrator?.vibrate(milliseconds)
        }
      }
    }

    @JavascriptInterface
    fun speakText(text: String) {
      runOnUiThread {
        val cleanText = text.replace(Regex("```[\\s\\S]*?```"), "Code snippet omitted.")
        textToSpeech?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "YSLAM_TTS")
      }
    }

    @JavascriptInterface
    fun stopSpeaking() {
      runOnUiThread { textToSpeech?.stop() }
    }

    @JavascriptInterface
    fun transcribeSpeech() {
      runOnUiThread {
        if (ContextCompat.checkSelfPermission(
            this@MainActivity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) {
          launchSpeechRecognition()
        } else {
          audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
      }
    }

    @JavascriptInterface
    fun getSharedText(): String {
      val text = sharedTextReceived ?: ""
      sharedTextReceived = null
      return text
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
      runOnUiThread {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("YSLAM Code", text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
      }
    }

    @JavascriptInterface
    fun showToast(message: String) {
      runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
    }

    // --- Real Termux & Localhost Network Diagnostic Methods ---

    @JavascriptInterface
    fun probeTcpPort(host: String, port: Int, timeoutMs: Int): String {
      val t0 = System.currentTimeMillis()
      val result = JSONObject()
      val targetHost = host.trim().ifEmpty { "127.0.0.1" }
      try {
        Socket().use { socket ->
          socket.connect(InetSocketAddress(targetHost, port), timeoutMs.coerceAtLeast(300))
          val latency = System.currentTimeMillis() - t0
          result.put("success", true)
          result.put("host", targetHost)
          result.put("port", port)
          result.put("latencyMs", latency)
          result.put("message", "Port $port is open and listening ($latency ms)")
        }
      } catch (e: Exception) {
        val latency = System.currentTimeMillis() - t0
        result.put("success", false)
        result.put("host", targetHost)
        result.put("port", port)
        result.put("latencyMs", latency)
        val msg = when {
          e is java.net.ConnectException -> "Connection refused: No service is listening on $targetHost:$port in Termux."
          e is java.net.SocketTimeoutException -> "Timed out connecting to $targetHost:$port after ${timeoutMs}ms."
          else -> e.message ?: e.toString()
        }
        result.put("error", msg)
      }
      return result.toString()
    }

    @JavascriptInterface
    fun httpGet(url: String, timeoutMs: Int): String {
      val result = JSONObject()
      val t0 = System.currentTimeMillis()
      try {
        val client =
            okHttpClient
                .newBuilder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
        val request =
            Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json, text/plain, */*")
                .build()
        client.newCall(request).execute().use { response ->
          val body = response.body?.string() ?: ""
          val latency = System.currentTimeMillis() - t0
          result.put("success", response.isSuccessful)
          result.put("statusCode", response.code)
          result.put("body", body)
          result.put("latencyMs", latency)
        }
      } catch (e: Exception) {
        val latency = System.currentTimeMillis() - t0
        result.put("success", false)
        result.put("latencyMs", latency)
        result.put("error", e.message ?: e.toString())
      }
      return result.toString()
    }

    @JavascriptInterface
    fun httpPost(url: String, jsonBody: String, timeoutMs: Int): String {
      val result = JSONObject()
      val t0 = System.currentTimeMillis()
      try {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonBody.toRequestBody(mediaType)
        val client =
            okHttpClient
                .newBuilder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
        val request =
            Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json, text/plain, */*")
                .build()
        client.newCall(request).execute().use { response ->
          val body = response.body?.string() ?: ""
          val latency = System.currentTimeMillis() - t0
          result.put("success", response.isSuccessful)
          result.put("statusCode", response.code)
          result.put("body", body)
          result.put("latencyMs", latency)
        }
      } catch (e: Exception) {
        val latency = System.currentTimeMillis() - t0
        result.put("success", false)
        result.put("latencyMs", latency)
        result.put("error", e.message ?: e.toString())
      }
      return result.toString()
    }

    @JavascriptInterface
    fun sendPromptToTermux(
        endpoint: String,
        engine: String,
        prompt: String,
        model: String,
        maxTokens: Int,
        temperature: Float
    ): String {
      val result = JSONObject()
      val cleanEndpoint = endpoint.trim().trimEnd('/')
      val t0 = System.currentTimeMillis()

      try {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestUrl: String
        val requestBodyJson: JSONObject

        when (engine.lowercase(Locale.ROOT)) {
          "ollama" -> {
            requestUrl = "$cleanEndpoint/api/generate"
            requestBodyJson =
                JSONObject().apply {
                  put("model", model.ifEmpty { "qwen2.5-coder:7b" })
                  put("prompt", prompt)
                  put("stream", false)
                  put(
                      "options",
                      JSONObject().apply {
                        put("temperature", temperature.toDouble())
                        put("num_predict", maxTokens)
                      })
                }
          }
          "kobold", "koboldcpp" -> {
            requestUrl = "$cleanEndpoint/api/v1/generate"
            requestBodyJson =
                JSONObject().apply {
                  put("prompt", prompt)
                  put("max_length", maxTokens)
                  put("temperature", temperature.toDouble())
                }
          }
          "vllm", "lmstudio", "openai" -> {
            requestUrl = "$cleanEndpoint/v1/chat/completions"
            val messages =
                JSONArray().apply {
                  put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                  })
                }
            requestBodyJson =
                JSONObject().apply {
                  put("model", model.ifEmpty { "local-model" })
                  put("messages", messages)
                  put("max_tokens", maxTokens)
                  put("temperature", temperature.toDouble())
                }
          }
          else -> { // Default: llama.cpp (/completion)
            requestUrl = "$cleanEndpoint/completion"
            requestBodyJson =
                JSONObject().apply {
                  put("prompt", prompt)
                  put("n_predict", maxTokens)
                  put("temperature", temperature.toDouble())
                  put("stream", false)
                }
          }
        }

        val request =
            Request.Builder()
                .url(requestUrl)
                .post(requestBodyJson.toString().toRequestBody(mediaType))
                .addHeader("Content-Type", "application/json")
                .build()

        okHttpClient.newCall(request).execute().use { response ->
          val rawBody = response.body?.string() ?: ""
          val latency = System.currentTimeMillis() - t0

          if (!response.isSuccessful) {
            result.put("success", false)
            result.put("statusCode", response.code)
            result.put(
                "error", "HTTP ${response.code} from Termux server at $requestUrl: $rawBody")
            return result.toString()
          }

          var generatedText = ""
          try {
            val json = JSONObject(rawBody)
            when {
              json.has("content") -> generatedText = json.getString("content")
              json.has("response") -> generatedText = json.getString("response")
              json.has("choices") -> {
                val choices = json.getJSONArray("choices")
                if (choices.length() > 0) {
                  val choice = choices.getJSONObject(0)
                  if (choice.has("message")) {
                    generatedText = choice.getJSONObject("message").optString("content", "")
                  } else if (choice.has("text")) {
                    generatedText = choice.optString("text", "")
                  }
                }
              }
              json.has("results") -> {
                val results = json.getJSONArray("results")
                if (results.length() > 0) {
                  generatedText = results.getJSONObject(0).optString("text", "")
                }
              }
              else -> generatedText = rawBody
            }
          } catch (jsonErr: Exception) {
            generatedText = rawBody
          }

          result.put("success", true)
          result.put("text", generatedText)
          result.put("latencyMs", latency)
          result.put("endpoint", requestUrl)
        }
      } catch (e: Exception) {
        val latency = System.currentTimeMillis() - t0
        result.put("success", false)
        val msg =
            when {
              e is java.net.ConnectException ->
                  "Connection refused on $cleanEndpoint. Ensure your AI model server is running in Termux."
              e is java.net.SocketTimeoutException ->
                  "Timed out waiting for model response from $cleanEndpoint after ${latency}ms."
              else -> e.message ?: e.toString()
            }
        result.put("error", msg)
        result.put("latencyMs", latency)
      }

      return result.toString()
    }

    @JavascriptInterface
    fun getPermissionsSummary(): String {
      val list = JSONArray()

      fun addPerm(
          id: String,
          name: String,
          protectionLevel: String,
          isGranted: Boolean,
          grantMethod: String,
          description: String
      ) {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("protectionLevel", protectionLevel)
        obj.put("isGranted", isGranted)
        obj.put("grantMethod", grantMethod)
        obj.put("description", description)
        list.put(obj)
      }

      // 1. INTERNET
      addPerm(
          "internet",
          "android.permission.INTERNET",
          "Normal",
          true,
          "Install-time (Automatic)",
          "Loopback HTTP & TCP to Termux backends (127.0.0.1:<port>)"
      )

      // 2. ACCESS_NETWORK_STATE
      addPerm(
          "network_state",
          "android.permission.ACCESS_NETWORK_STATE",
          "Normal",
          true,
          "Install-time (Automatic)",
          "Detect network state transitions for model download resume"
      )

      // 3. ACCESS_WIFI_STATE
      addPerm(
          "wifi_state",
          "android.permission.ACCESS_WIFI_STATE",
          "Normal",
          true,
          "Install-time (Automatic)",
          "Detect Wi-Fi connectivity for LAN backend model discovery"
      )

      // 4. WAKE_LOCK
      addPerm(
          "wake_lock",
          "android.permission.WAKE_LOCK",
          "Normal",
          true,
          "Install-time (Automatic)",
          "Keep CPU awake during long-running Termux inference & builds"
      )

      // 5. POST_NOTIFICATIONS
      val hasNotif =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
          } else {
            true
          }
      addPerm(
          "notifications",
          "android.permission.POST_NOTIFICATIONS",
          "Dangerous",
          hasNotif,
          "Runtime prompt (Android 13+)",
          "Show background progress for model pulls, pipeline runs & builds"
      )

      // 6. RECORD_AUDIO
      val hasAudio =
          ContextCompat.checkSelfPermission(
              this@MainActivity,
              Manifest.permission.RECORD_AUDIO
          ) == PackageManager.PERMISSION_GRANTED
      addPerm(
          "audio",
          "android.permission.RECORD_AUDIO",
          "Dangerous",
          hasAudio,
          "Runtime prompt (On microphone tap)",
          "Voice input & STT for multimodal chat composer"
      )

      // 7. com.termux.permission.RUN_COMMAND
      val hasTermuxRun =
          ContextCompat.checkSelfPermission(
              this@MainActivity,
              "com.termux.permission.RUN_COMMAND"
          ) == PackageManager.PERMISSION_GRANTED
      addPerm(
          "termux_run",
          "com.termux.permission.RUN_COMMAND",
          "Dangerous (Custom)",
          hasTermuxRun,
          "ADB, Shizuku, or Termux settings toggle",
          "Send shell commands into Termux to start llama-server and ollama"
      )

      // 8. MANAGE_EXTERNAL_STORAGE
      val hasManageStorage =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
          } else {
            true
          }
      addPerm(
          "manage_storage",
          "android.permission.MANAGE_EXTERNAL_STORAGE",
          "Special App-Op",
          hasManageStorage,
          "System Settings Toggle (All Files Access)",
          "Full filesystem access for /sdcard/YoungProjects/ and local models"
      )

      // 9. READ_EXTERNAL_STORAGE
      val hasReadStorage =
          if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
          } else {
            true
          }
      addPerm(
          "read_storage",
          "android.permission.READ_EXTERNAL_STORAGE",
          "Dangerous (API <= 32)",
          hasReadStorage,
          "Runtime prompt (Legacy)",
          "Legacy storage access fallback for Android 10-12"
      )

      // 10. WRITE_EXTERNAL_STORAGE
      val hasWriteStorage =
          if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
          } else {
            true
          }
      addPerm(
          "write_storage",
          "android.permission.WRITE_EXTERNAL_STORAGE",
          "Dangerous (API <= 29)",
          hasWriteStorage,
          "Runtime prompt (Legacy)",
          "Legacy storage write access for Android 9-10"
      )

      // 11. VIBRATE
      addPerm(
          "vibrate",
          "android.permission.VIBRATE",
          "Normal",
          true,
          "Install-time (Automatic)",
          "Tactile haptic feedback for UI buttons and pipeline events"
      )

      return list.toString()
    }

    @JavascriptInterface
    fun requestPermission(permId: String) {
      runOnUiThread {
        when (permId) {
          "audio" -> {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
          }
          "notifications" -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
              Toast.makeText(this@MainActivity, "Notifications already permitted on this OS version", Toast.LENGTH_SHORT).show()
            }
          }
          "manage_storage" -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
              try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                  data = Uri.parse("package:$packageName")
                }
                storagePermissionLauncher.launch(intent)
              } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                storagePermissionLauncher.launch(intent)
              }
            } else {
              Toast.makeText(this@MainActivity, "Scoped storage is active on this Android version", Toast.LENGTH_SHORT).show()
            }
          }
          "app_settings" -> {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
              data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
          }
          "termux_run" -> {
            Toast.makeText(
                this@MainActivity,
                "Grant RUN_COMMAND via Termux ~/.termux/termux.properties: allow-external-apps=true or adb shell pm grant",
                Toast.LENGTH_LONG
            ).show()
          }
          else -> {
            Toast.makeText(this@MainActivity, "Permission $permId is managed by system", Toast.LENGTH_SHORT).show()
          }
        }
      }
    }

    @JavascriptInterface
    fun quickGrantAllPermissions() {
      runOnUiThread {
        val permissionsToRequest = mutableListOf<String>()

        // 1. Microphone / Audio Recording
        if (ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
          permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // 2. Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          if (ContextCompat.checkSelfPermission(
                  this@MainActivity,
                  Manifest.permission.POST_NOTIFICATIONS
              ) != PackageManager.PERMISSION_GRANTED
          ) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
          }
        }

        // 3. Legacy Storage Read (Android 10-12)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
          if (ContextCompat.checkSelfPermission(
                  this@MainActivity,
                  Manifest.permission.READ_EXTERNAL_STORAGE
              ) != PackageManager.PERMISSION_GRANTED
          ) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
          }
        }

        // 4. Legacy Storage Write (Android 9-10)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
          if (ContextCompat.checkSelfPermission(
                  this@MainActivity,
                  Manifest.permission.WRITE_EXTERNAL_STORAGE
              ) != PackageManager.PERMISSION_GRANTED
          ) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
          }
        }

        if (permissionsToRequest.isNotEmpty()) {
          multiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
          // If all runtime dangerous permissions are already granted, check special storage permission
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Toast.makeText(this@MainActivity, "Runtime permissions granted. Opening Filesystem settings...", Toast.LENGTH_SHORT).show()
            try {
              val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
              }
              storagePermissionLauncher.launch(intent)
            } catch (_: Exception) {
              val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
              storagePermissionLauncher.launch(intent)
            }
          } else {
            Toast.makeText(this@MainActivity, "All standard runtime permissions are already granted!", Toast.LENGTH_SHORT).show()
            webView?.post {
              webView?.evaluateJavascript("if (window.refreshPermissionsUi) window.refreshPermissionsUi();", null)
            }
          }
        }
      }
    }
  }
}

@Composable
fun YslamStudioScreen(onWebViewCreated: (WebView) -> Unit, onBackPressed: () -> Unit) {
  var canGoBack by remember { mutableStateOf(false) }

  BackHandler(enabled = true) { onBackPressed() }

  Box(modifier = Modifier.fillMaxSize().testTag("yslam_webview_container")) {
    AndroidView(
        factory = { ctx ->
          WebView(ctx).apply {
            layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(0xFF0F0F11.toInt())
            onWebViewCreated(this)
          }
        },
        modifier = Modifier.fillMaxSize())
  }
}
