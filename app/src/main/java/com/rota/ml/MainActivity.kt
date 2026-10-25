package com.rota.ml

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.rota.ml.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.regex.Pattern

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("RotaMLPrefs", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)

        setupUI()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        atualizarStatusAcessibilidade()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupUI() {
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.databaseEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            addJavascriptInterface(WebAppInterface(), "Android")
            loadUrl("file:///android_asset/www/index.html")
        }

        atualizarStatusAcessibilidade()
    }

    private fun setupListeners() {
        binding.btnLer.setOnClickListener {
            toggleCaptura()
        }

        binding.btnRota.setOnClickListener {
            processarRota()
        }

        binding.btnBiparTop.setOnClickListener {
            iniciarScanner()
        }

        binding.btnQuickTest.setOnClickListener {
            carregarRotaTeste()
        }

        binding.btnConfig.setOnClickListener {
            showConfigDialog()
        }

        binding.chipAcessibilidade.setOnClickListener {
            Toast.makeText(this, "Ative o serviço 'Leitor Envios Extra' nas configurações", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
        }
    }

    private fun atualizarStatusAcessibilidade() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            binding.chipAcessibilidade.text = "● Leitor Ativo"
            binding.chipAcessibilidade.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.chipAcessibilidade.text = "⚠ Ativar Leitor"
            binding.chipAcessibilidade.setTextColor(Color.parseColor("#F59E0B"))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedServiceName = "${packageName}/${LeitorEnviosService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expectedServiceName) || enabledServices.contains(LeitorEnviosService::class.java.simpleName)
    }

    private fun toggleCaptura() {
        LeitorEnviosService.capturando = !LeitorEnviosService.capturando
        if (LeitorEnviosService.capturando) {
            LeitorEnviosService.limpar()
            binding.btnLer.text = "⏹ Lendo..."
            binding.btnLer.setTextColor(Color.parseColor("#EF4444"))
            Toast.makeText(this, "Vá para o app Envios Extra e role a lista de paradas!", Toast.LENGTH_LONG).show()
        } else {
            val total = LeitorEnviosService.textosCapturados.size
            binding.btnLer.text = "1. Ler Tela ($total)"
            binding.btnLer.setTextColor(Color.parseColor("#F3F4F6"))
            Toast.makeText(this, "Captura encerrada: $total itens capturados", Toast.LENGTH_SHORT).show()
        }
    }

    private fun iniciarScanner() {
        val scanner = GmsBarcodeScanning.getClient(this@MainActivity)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val code = barcode.rawValue ?: ""
                binding.webView.post {
                    binding.webView.evaluateJavascript("confirmarBipe('$code')", null)
                }
                syncSupabase(code)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Erro ao ler código: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun carregarRotaTeste() {
        binding.webView.post {
            binding.webView.evaluateJavascript("carregar40ParadasCampinas(true)", null)
        }
        Toast.makeText(this, "40 Paradas de Campinas carregadas com sucesso!", Toast.LENGTH_SHORT).show()
    }

    private fun showConfigDialog() {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val keys = arrayOf("Gemini Key 1", "Gemini Key 2", "Gemini Key 3", "Gemini Key 4", "Supa URL", "Supa Key", "Sala Casal")
        val edits = keys.map { label ->
            TextView(this).apply { 
                text = label 
                setTextColor(Color.DKGRAY)
                textSize = 12f
            }.also { view.addView(it) }
            EditText(this).apply {
                setText(prefs.getString(label, ""))
                textSize = 14f
                view.addView(this)
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("⚙️ Configurações RotaML")
            .setView(view)
            .setPositiveButton("Salvar") { _, _ ->
                val editor = prefs.edit()
                keys.forEachIndexed { i, label -> editor.putString(label, edits[i].text.toString().trim()) }
                editor.apply()
                Toast.makeText(this, "Configurações salvas!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun processarRota() {
        scope.launch {
            val rawTextList = LeitorEnviosService.textosCapturados.toList()
            if (rawTextList.isEmpty()) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("🎯 Teste com 40 Paradas em Campinas")
                    .setMessage("Nenhum dado capturado ainda da tela do app Envios Extra.\n\nDeseja carregar a rota de teste com 40 paradas da região de Campinas (incluindo o pacote da sua foto) para ver a otimização no mapa?")
                    .setPositiveButton("Carregar 40 Paradas") { _, _ -> carregarRotaTeste() }
                    .setNegativeButton("Entendido", null)
                    .show()
                return@launch
            }

            binding.progressBar.visibility = View.VISIBLE
            Toast.makeText(this@MainActivity, "Processando dados com IA...", Toast.LENGTH_SHORT).show()

            val rawText = rawTextList.joinToString("\n")
            var paradasJsonArray: JSONArray? = null

            // 1. Tentar extração inteligente via Gemini
            val geminiResult = callGeminiWithFailover(rawText)
            if (geminiResult != null) {
                try {
                    paradasJsonArray = JSONArray(geminiResult)
                } catch (e: Exception) {
                    Log.e("GeminiParse", "Erro ao parsear JSON do Gemini", e)
                }
            }

            // 2. Se Gemini falhou ou não há chave configurada, usa parser local de emergência
            if (paradasJsonArray == null || paradasJsonArray.length() == 0) {
                Log.d("RotaML", "Gemini indisponível ou vazio. Usando parser local...")
                paradasJsonArray = extrairParadasLocalmente(rawTextList)
            }

            if (paradasJsonArray.length() == 0) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Não foi possível identificar endereços no texto capturado.", Toast.LENGTH_LONG).show()
                return@launch
            }

            // Geocodificação e OSRM
            geocoderAndRoute(paradasJsonArray)
            binding.progressBar.visibility = View.GONE
        }
    }

    private suspend fun callGeminiWithFailover(text: String): String? {
        val keys = listOf("Gemini Key 1", "Gemini Key 2", "Gemini Key 3", "Gemini Key 4")
            .map { prefs.getString(it, "") ?: "" }
            .filter { it.isNotBlank() }

        if (keys.isEmpty()) return null

        val client = OkHttpClient()
        val prompt = """
        Você é um assistente especialista em logística do Mercado Envios Extra.
        Analise o texto capturado da tela do aplicativo e extraia todas as paradas de entrega e seus respectivos pacotes.
        
        Retorne ESTRITAMENTE um array JSON válido sem markdown adicional no formato:
        [
          {
            "parada_num": 1,
            "destinatario": "Nome do Cliente",
            "endereco": "Rua/Avenida e Número",
            "bairro": "Nome do Bairro",
            "cidade": "Cidade",
            "cep": "00000000",
            "complemento": "Fundos, Apto, etc ou vazio",
            "pacotes": [
              {
                "id": "48314806075",
                "final": "06075",
                "etiqueta": "#NX394_21",
                "ponto": "A"
              }
            ]
          }
        ]
        Texto capturado:
        $text
        """.trimIndent()

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

    // Extrator local heurístico caso o Gemini não tenha chave ou falhe
    private fun extrairParadasLocalmente(linhas: List<String>): JSONArray {
        val paradas = JSONArray()
        val cepRegex = Pattern.compile("\\b(\\d{5}-?\\d{3})\\b")
        val idRegex = Pattern.compile("\\b(48\\d{8,10}|\\d{10,12})\\b")
        val etiquetaRegex = Pattern.compile("#?([A-Z]{2}\\d{3}_\\d+)")

        var enderecoAtual = ""
        var destinatarioAtual = ""
        var cepAtual = ""
        val pacotesAtuais = mutableListOf<JSONObject>()

        for (linha in linhas) {
            val l = linha.trim()
            val mCep = cepRegex.matcher(l)
            val mId = idRegex.matcher(l)
            val mEtiqueta = etiquetaRegex.matcher(l)

            if (mId.find()) {
                val id = mId.group(1) ?: ""
                val pkg = JSONObject().apply {
                    put("id", id)
                    put("final", if (id.length >= 5) id.takeLast(5) else id)
                    put("conferido", false)
                }
                pacotesAtuais.add(pkg)
            } else if (mEtiqueta.find()) {
                val etq = mEtiqueta.group(1) ?: ""
                if (pacotesAtuais.isNotEmpty()) {
                    pacotesAtuais.last().put("etiqueta", "#$etq")
                }
            } else if (mCep.find()) {
                cepAtual = mCep.group(1)?.replace("-", "") ?: ""
            } else if (l.startsWith("Rua", true) || l.startsWith("Av", true) || l.startsWith("R.", true) || l.startsWith("Alameda", true)) {
                if (enderecoAtual.isNotBlank() && pacotesAtuais.isNotEmpty()) {
                    // Salva parada anterior
                    val p = JSONObject().apply {
                        put("parada_num", paradas.length() + 1)
                        put("destinatario", if (destinatarioAtual.isNotBlank()) destinatarioAtual else "Cliente ${paradas.length() + 1}")
                        put("endereco", enderecoAtual)
                        put("bairro", "")
                        put("cidade", "Campinas")
                        put("cep", cepAtual)
                        put("pacotes", JSONArray(pacotesAtuais))
                    }
                    paradas.put(p)
                    pacotesAtuais.clear()
                    destinatarioAtual = ""
                    cepAtual = ""
                }
                enderecoAtual = l
            } else if (enderecoAtual.isBlank() && l.length > 5 && !l.contains("Mercado") && !l.contains("Envios")) {
                destinatarioAtual = l
            }
        }

        // Adiciona última parada
        if (enderecoAtual.isNotBlank()) {
            val p = JSONObject().apply {
                put("parada_num", paradas.length() + 1)
                put("destinatario", if (destinatarioAtual.isNotBlank()) destinatarioAtual else "Cliente ${paradas.length() + 1}")
                put("endereco", enderecoAtual)
                put("bairro", "")
                put("cidade", "Campinas")
                put("cep", cepAtual)
                put("pacotes", JSONArray(pacotesAtuais))
            }
            paradas.put(p)
        }

        return paradas
    }

    @Suppress("DEPRECATION")
    private suspend fun geocoderAndRoute(paradas: JSONArray) {
        val geocoder = Geocoder(this, Locale.getDefault())
        val points = mutableListOf<JSONObject>()

        for (i in 0 until paradas.length()) {
            val p = paradas.getJSONObject(i)
            val addr = "${p.optString("endereco", "")}, ${p.optString("bairro", "")}, ${p.optString("cidade", "Campinas")}, SP, Brasil"
            try {
                val results = withContext(Dispatchers.IO) { geocoder.getFromLocationName(addr, 1) }
                if (!results.isNullOrEmpty()) {
                    p.put("lat", results[0].latitude)
                    p.put("lng", results[0].longitude)
                } else {
                    // Fallback aproximado se não achar no geocoder
                    p.put("lat", -22.9056 + (i * 0.005))
                    p.put("lng", -47.0608 + (i * 0.005))
                }
                points.add(p)
            } catch (e: Exception) {
                Log.e("Geocode", "Fail addr: $addr", e)
                p.put("lat", -22.9056 + (i * 0.005))
                p.put("lng", -47.0608 + (i * 0.005))
                points.add(p)
            }
        }

        if (points.isEmpty()) return

        // OSRM Trip API para ordenar a rota
        val coords = points.joinToString(";") { "${it.getDouble("lng")},${it.getDouble("lat")}" }
        val osrmUrl = "https://router.project-osrm.org/trip/v1/driving/$coords?source=first&destination=last&roundtrip=false&geometries=geojson"

        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(osrmUrl).build()
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (response.isSuccessful) {
                val tripJson = JSONObject(response.body?.string() ?: "{}")
                val waypoints = tripJson.optJSONArray("waypoints")
                val sortedPoints = JSONArray()

                if (waypoints != null && waypoints.length() > 0) {
                    val indices = (0 until waypoints.length()).map { i ->
                        waypoints.getJSONObject(i).getInt("waypoint_index") to i
                    }.sortedBy { it.first }

                    for (pair in indices) {
                        sortedPoints.put(points[pair.second])
                    }
                } else {
                    for (p in points) sortedPoints.put(p)
                }

                binding.webView.post {
                    binding.webView.evaluateJavascript("carregarRota(${sortedPoints})", null)
                }
                return
            }
        } catch (e: Exception) {
            Log.e("OSRM", "Error", e)
        }

        // Se o OSRM der erro de rede, carrega os pontos na ordem original
        val fallbackPoints = JSONArray()
        for (p in points) fallbackPoints.put(p)
        binding.webView.post {
            binding.webView.evaluateJavascript("carregarRota(${fallbackPoints})", null)
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun bipar() {
            runOnUiThread {
                iniciarScanner()
            }
        }

        @JavascriptInterface
        fun speak(text: String) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UTTERANCE_${System.currentTimeMillis()}")
        }

        @JavascriptInterface
        fun vibrar(tipo: String) {
            this@MainActivity.executarVibracao(tipo)
        }

        @JavascriptInterface
        fun abrirNavegacao(lat: Double, lng: Double, endereco: String) {
            this@MainActivity.abrirAppNavegacao(lat, lng, endereco)
        }
    }

    @Suppress("DEPRECATION")
    private fun executarVibracao(tipo: String) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (tipo) {
                    "warning" -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 100), -1))
                    "danger" -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 80, 180), -1))
                    else -> vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } else {
                vibrator.vibrate(80)
            }
        } catch (e: Exception) {
            Log.e("Vibrator", "Erro de vibração", e)
        }
    }

    private fun abrirAppNavegacao(lat: Double, lng: Double, endereco: String) {
        try {
            val uri = if (lat != 0.0 && lng != 0.0) {
                Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(endereco)})")
            } else {
                Uri.parse("geo:0,0?q=${Uri.encode(endereco)}")
            }
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(endereco)}"))
                startActivity(mapIntent)
            } catch (ex: Exception) {
                Toast.makeText(this, "Nenhum aplicativo de mapas disponível", Toast.LENGTH_SHORT).show()
            }
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
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("pt", "BR")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
