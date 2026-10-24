package com.rota.ml

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("RotaMLPrefs", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        setupUI()
    }

    private fun setupUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(android.graphics.Color.parseColor("#FFE600"))
        }

        val btnLer = Button(this).apply { text = "1. Ler Tela" }
        val btnRota = Button(this).apply { text = "2. Gerar Rota" }
        val btnConfig = Button(this).apply { text = "⚙️" }
        val btnPerm = Button(this).apply { text = "Permissão" }

        topBar.addView(btnLer)
        topBar.addView(btnRota)
        topBar.addView(btnPerm)
        topBar.addView(btnConfig)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(WebAppInterface(), "Android")
            loadUrl("file:///android_asset/www/index.html")
        }

        root.addView(topBar)
        root.addView(webView)
        setContentView(root)

        btnLer.setOnClickListener { toggleCaptura(btnLer) }
        btnRota.setOnClickListener { processarRota() }
        btnConfig.setOnClickListener { showConfigDialog() }
        btnPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
        }
    }

    private fun toggleCaptura(btn: Button) {
        LeitorEnviosService.capturando = !LeitorEnviosService.capturando
        if (LeitorEnviosService.capturando) {
            LeitorEnviosService.limpar()
            btn.text = "STOP (Lendo...)"
            Toast.makeText(this, "Vá para o App Envios e role a lista", Toast.LENGTH_LONG).show()
        } else {
            btn.text = "1. Ler Tela"
            Toast.makeText(this, "Captura encerrada: ${LeitorEnviosService.textosCapturados.size} itens", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showConfigDialog() {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val keys = arrayOf("Gemini Key 1", "Gemini Key 2", "Gemini Key 3", "Gemini Key 4", "Supa URL", "Supa Key", "Sala Casal")
        val edits = keys.map { label ->
            TextView(this).apply { text = label }.also { view.addView(it) }
            EditText(this).apply { 
                setText(prefs.getString(label, ""))
                view.addView(this)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Configurações")
            .setView(view)
            .setPositiveButton("Salvar") { _, _ ->
                val editor = prefs.edit()
                keys.forEachIndexed { i, label -> editor.putString(label, edits[i].text.toString()) }
                editor.apply()
            }.show()
    }

    private fun processarRota() {
        scope.launch {
            val rawText = LeitorEnviosService.textosCapturados.joinToString("\n")
            if (rawText.isBlank()) {
                Toast.makeText(this@MainActivity, "Nenhum texto capturado!", Toast.LENGTH_SHORT).show()
                return@launch
            }

            Toast.makeText(this@MainActivity, "IA extraindo endereços...", Toast.LENGTH_SHORT).show()
            val jsonStr = callGeminiWithFailover(rawText)
            if (jsonStr != null) {
                val paradas = JSONArray(jsonStr)
                geocoderAndRoute(paradas)
            }
        }
    }

    private suspend fun callGeminiWithFailover(text: String): String? {
        val keys = listOf("Gemini Key 1", "Gemini Key 2", "Gemini Key 3", "Gemini Key 4")
            .map { prefs.getString(it, "") ?: "" }
            .filter { it.isNotBlank() }

        val client = OkHttpClient()
        val prompt = "Extraia os endereços do Mercado Envios Extra abaixo e retorne APENAS um JSON array no formato [{\"id\": \"id_unico\", \"endereco\": \"rua, numero\", \"bairro\": \"...\", \"cep\": \"...\"}]. Texto:\n$text"

        for (key in keys) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key"
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                
                val request = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType())).build()
                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                
                if (response.isSuccessful) {
                    val respJson = JSONObject(response.body?.string() ?: "{}")
                    val out = respJson.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                    return out.replace("```json", "").replace("```", "").trim()
                } else if (response.code != 429) {
                    Log.e("Gemini", "Erro ${response.code}: ${response.message}")
                }
            } catch (e: Exception) {
                Log.e("Gemini", "Failover next", e)
            }
        }
        return null
    }

    private suspend fun geocoderAndRoute(paradas: JSONArray) {
        val geocoder = Geocoder(this, Locale.getDefault())
        val points = mutableListOf<JSONObject>()

        for (i in 0 until paradas.length()) {
            val p = paradas.getJSONObject(i)
            val addr = "${p.getString("endereco")}, ${p.getString("bairro")}, ${p.getString("cep")}, Brasil"
            try {
                val results = withContext(Dispatchers.IO) { geocoder.getFromLocationName(addr, 1) }
                if (results != null && results.isNotEmpty()) {
                    p.put("lat", results[0].latitude)
                    p.put("lng", results[0].longitude)
                    points.add(p)
                }
            } catch (e: Exception) {
                Log.e("Geocode", "Fail addr: $addr")
            }
        }

        if (points.isEmpty()) return

        // OSRM Trip API
        val coords = points.joinToString(";") { "${it.getDouble("lng")},${it.getDouble("lat")}" }
        val osrmUrl = "https://router.project-osrm.org/trip/v1/driving/$coords?source=first&destination=last&roundtrip=false&geometries=geojson"
        
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(osrmUrl).build()
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (response.isSuccessful) {
                val tripJson = JSONObject(response.body?.string() ?: "{}")
                val waypoints = tripJson.getJSONArray("waypoints")
                val sortedPoints = JSONArray()
                
                val indices = (0 until waypoints.length()).map { i -> 
                    waypoints.getJSONObject(i).getInt("waypoint_index") to i 
                }.sortedBy { it.first }

                for (pair in indices) {
                    sortedPoints.put(points[pair.second])
                }

                webView.post {
                    webView.evaluateJavascript("carregarRota(${sortedPoints})", null)
                }
            }
        } catch (e: Exception) {
            Log.e("OSRM", "Error", e)
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun bipar() {
            val scanner = GmsBarcodeScanning.getClient(this@MainActivity)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val code = barcode.rawValue ?: ""
                    webView.post { webView.evaluateJavascript("confirmarBipe('$code')", null) }
                    speak("Parada OK")
                    syncSupabase(code)
                }
        }

        @JavascriptInterface
        fun speak(text: String) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
        }
    }

    private fun syncSupabase(code: String) {
        val url = prefs.getString("Supa URL", "") ?: ""
        val key = prefs.getString("Supa Key", "") ?: ""
        if (url.isBlank()) return

        val client = OkHttpClient()
        val body = JSONObject().put("barcode", code).put("timestamp", System.currentTimeMillis()).put("sala", prefs.getString("Sala Casal", "default"))
        val request = Request.Builder()
            .url("$url/rest/v1/sessoes_rota")
            .header("apikey", key)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        scope.launch(Dispatchers.IO) {
            try { client.newCall(request).execute() } catch (e: Exception) {}
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale("pt", "BR")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
