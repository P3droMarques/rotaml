package com.rota.ml

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.ImageDecoder
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rota.ml.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) {
            processarImagensGaleria(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("RotaMLPrefs", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)

        setupUI()
        setupListeners()
        setupLeitorListener()

        if (intent?.getBooleanExtra("EXTRA_PROCESSAR_AUTOMATICO", false) == true) {
            processarRota()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("EXTRA_PROCESSAR_AUTOMATICO", false) == true) {
            processarRota()
        }
    }

    override fun onResume() {
        super.onResume()
        atualizarStatusAcessibilidade()
        atualizarBotaoLeitor()
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
            mostrarOpcoesDeLeitura()
        }

        binding.btnLerFoto.setOnClickListener {
            abrirSeletorGaleria()
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
            solicitarAtivacaoAcessibilidade()
        }
    }

    private fun setupLeitorListener() {
        LeitorEnviosService.onUpdateListener = { _, totalParadas ->
            runOnUiThread {
                if (LeitorEnviosService.capturando) {
                    binding.btnLer.text = "⏹ Lendo ($totalParadas)..."
                    binding.btnLer.setTextColor(Color.parseColor("#EF4444"))
                } else {
                    binding.btnLer.text = if (totalParadas > 0) "1. Ler Tela ($totalParadas)" else "1. Ler Tela"
                    binding.btnLer.setTextColor(Color.parseColor("#F3F4F6"))
                }
            }
        }
    }

    private fun atualizarStatusAcessibilidade() {
        val enabled = isAccessibilityServiceEnabled() || LeitorEnviosService.isRunning
        if (enabled) {
            binding.chipAcessibilidade.text = "● Leitor Ativo"
            binding.chipAcessibilidade.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.chipAcessibilidade.text = "⚠ Ativar Leitor"
            binding.chipAcessibilidade.setTextColor(Color.parseColor("#F59E0B"))
        }
    }

    private fun atualizarBotaoLeitor() {
        val totalParadas = LeitorEnviosService.paradasDetectadas.size
        if (LeitorEnviosService.capturando) {
            binding.btnLer.text = "⏹ Lendo ($totalParadas)..."
            binding.btnLer.setTextColor(Color.parseColor("#EF4444"))
        } else {
            binding.btnLer.text = if (totalParadas > 0) "1. Ler Tela ($totalParadas)" else "1. Ler Tela"
            binding.btnLer.setTextColor(Color.parseColor("#F3F4F6"))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (LeitorEnviosService.isRunning) return true
        val expectedServiceName = "${packageName}/${LeitorEnviosService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expectedServiceName) || enabledServices.contains(LeitorEnviosService::class.java.simpleName)
    }

    private fun solicitarAtivacaoAcessibilidade() {
        MaterialAlertDialogBuilder(this)
            .setTitle("⚙️ Ativar Leitor no Xiaomi / POCO")
            .setMessage("No POCO/Xiaomi (HyperOS/MIUI):\n\n1. Toque em 'Acessibilidade' > 'Apps baixados' e ative 'Leitor Envios Extra'.\n\n⚠️ Se o sistema bloquear dizendo 'Configuração restrita':\nToque em 'Liberar no Xiaomi' abaixo > toque nos 3 pontinhos no canto superior direito (⋮) > selecione 'Permitir configurações restritas'.")
            .setPositiveButton("Acessibilidade") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("Liberar no Xiaomi (Info do App)") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun mostrarOpcoesDeLeitura() {
        if (LeitorEnviosService.capturando) {
            toggleCapturaAoVivo()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("📱 Como deseja ler a rota?")
            .setItems(arrayOf(
                "⚡ 1. Tela ao Vivo (App Mercado Envios Extra)",
                "🖼️ 2. Ler Foto / Print da Galeria (OCR com IA)"
            )) { _, which ->
                if (which == 0) {
                    toggleCapturaAoVivo()
                } else {
                    abrirSeletorGaleria()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toggleCapturaAoVivo() {
        if (!isAccessibilityServiceEnabled()) {
            solicitarAtivacaoAcessibilidade()
            return
        }

        if (!LeitorEnviosService.capturando) {
            LeitorEnviosService.instance?.iniciarCapturaComOverlay() ?: run {
                LeitorEnviosService.capturando = true
                LeitorEnviosService.limpar()
            }
            binding.btnLer.text = "⏹ Lendo (0)..."
            binding.btnLer.setTextColor(Color.parseColor("#EF4444"))
            Toast.makeText(this, "Vá para o app Envios Extra e role a lista de paradas!\nO botão flutuante amarelo mostrará as paradas lidas.", Toast.LENGTH_LONG).show()
        } else {
            LeitorEnviosService.instance?.pararCapturaEFecharOverlay() ?: run {
                LeitorEnviosService.capturando = false
            }
            val total = LeitorEnviosService.paradasDetectadas.size
            binding.btnLer.text = if (total > 0) "1. Ler Tela ($total)" else "1. Ler Tela"
            binding.btnLer.setTextColor(Color.parseColor("#F3F4F6"))
            Toast.makeText(this, "Captura concluída: $total paradas identificadas!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun abrirSeletorGaleria() {
        try {
            pickImagesLauncher.launch("image/*")
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao abrir galeria: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processarImagensGaleria(uris: List<Uri>) {
        binding.progressBar.visibility = View.VISIBLE
        Toast.makeText(this, "Lendo ${uris.size} foto(s) com IA (OCR)...", Toast.LENGTH_SHORT).show()

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val todasLinhas = Collections.synchronizedList(mutableListOf<String>())
        var processadas = 0

        for (uri in uris) {
            try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }

                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        for (block in visionText.textBlocks) {
                            for (line in block.lines) {
                                val s = line.text.trim()
                                if (s.length >= 2) {
                                    todasLinhas.add(s)
                                    LeitorEnviosService.textosCapturados.add(s)
                                }
                            }
                        }
                        processadas++
                        if (processadas == uris.size) {
                            finalizarProcessamentoOcr(todasLinhas)
                        }
                    }
                    .addOnFailureListener {
                        processadas++
                        if (processadas == uris.size) {
                            finalizarProcessamentoOcr(todasLinhas)
                        }
                    }
            } catch (e: Exception) {
                Log.e("OCR", "Erro carregando imagem $uri", e)
                processadas++
                if (processadas == uris.size) {
                    finalizarProcessamentoOcr(todasLinhas)
                }
            }
        }
    }

    private fun finalizarProcessamentoOcr(linhas: List<String>) {
        scope.launch {
            binding.progressBar.visibility = View.GONE
            val paradas = EnviosExtraParser.parseLinhas(linhas)
            if (paradas.isEmpty()) {
                Toast.makeText(this@MainActivity, "Nenhum endereço identificado na imagem. Verifique se o print está legível.", Toast.LENGTH_LONG).show()
                return@launch
            }

            synchronized(LeitorEnviosService.paradasDetectadas) {
                LeitorEnviosService.paradasDetectadas.clear()
                LeitorEnviosService.paradasDetectadas.addAll(paradas)
            }

            atualizarBotaoLeitor()
            Toast.makeText(this@MainActivity, "🎉 Sucesso! ${paradas.size} paradas lidas da foto!", Toast.LENGTH_SHORT).show()

            val jsonArray = JSONArray()
            for (p in paradas) {
                jsonArray.put(p.toJson())
            }

            geocoderAndRoute(jsonArray)
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
        MaterialAlertDialogBuilder(this)
            .setTitle("🧪 Escolha a Rota de Teste")
            .setItems(arrayOf(
                "⚡ Suas 11 Paradas Envios Extra (Foto Real - 62 Unidades)",
                "📦 Rota Completa com 40 Paradas em Campinas"
            )) { _, which ->
                if (which == 0) {
                    binding.webView.post {
                        binding.webView.evaluateJavascript("carregar11ParadasEnviosExtra(true)", null)
                    }
                    Toast.makeText(this, "11 Paradas dos Envios Extra carregadas!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.webView.post {
                        binding.webView.evaluateJavascript("carregar40ParadasCampinas(true)", null)
                    }
                    Toast.makeText(this, "40 Paradas em Campinas carregadas!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
            val paradasProntas = synchronized(LeitorEnviosService.paradasDetectadas) {
                LeitorEnviosService.paradasDetectadas.toList()
            }
            val rawTextList = LeitorEnviosService.textosCapturados.toList()

            if (paradasProntas.isEmpty() && rawTextList.isEmpty()) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("📍 Nenhuma Parada Lida Ainda")
                    .setMessage("Ainda não foram detectados dados da tela.\n\nVocê pode:\n1. Tocar em '1. Ler Tela' para rolar o app Envios Extra\n2. Tocar no botão 🖼️ para escolher um print da galeria\n\nDeseja carregar as 11 paradas dos seus prints agora para testar?")
                    .setPositiveButton("Carregar Rota dos Prints") { _, _ ->
                        binding.webView.post {
                            binding.webView.evaluateJavascript("carregar11ParadasEnviosExtra(true)", null)
                        }
                    }
                    .setNegativeButton("Entendido", null)
                    .show()
                return@launch
            }

            binding.progressBar.visibility = View.VISIBLE
            Toast.makeText(this@MainActivity, "Processando paradas e calculando rota...", Toast.LENGTH_SHORT).show()

            var paradasJsonArray = JSONArray()

            // 1. Extração em tempo real já estruturada pelo LeitorEnviosService
            if (paradasProntas.isNotEmpty()) {
                for (p in paradasProntas) {
                    paradasJsonArray.put(p.toJson())
                }
            } else if (rawTextList.isNotEmpty()) {
                // 2. Parser local inteligente
                val parsed = EnviosExtraParser.parseLinhas(rawTextList)
                for (p in parsed) {
                    paradasJsonArray.put(p.toJson())
                }
            }

            // 3. Fallback Gemini se configurado
            if (paradasJsonArray.length() == 0 && rawTextList.isNotEmpty()) {
                val geminiResult = callGeminiWithFailover(rawTextList.joinToString("\n"))
                if (geminiResult != null) {
                    try {
                        paradasJsonArray = JSONArray(geminiResult)
                    } catch (e: Exception) {
                        Log.e("GeminiParse", "Erro ao parsear JSON do Gemini", e)
                    }
                }
            }

            if (paradasJsonArray.length() == 0) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Não foi possível identificar endereços no texto. Role a tela do app Envios Extra devagar ou selecione o print pelo botão 🖼️.", Toast.LENGTH_LONG).show()
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
            "destinatario": "Nome ou Local",
            "endereco": "Rua/Avenida e Número",
            "bairro": "Nome do Bairro",
            "cidade": "Campinas",
            "cep": "00000000",
            "complemento": "Condomínio, Apto, etc",
            "pacotes": [
              {
                "id": "NX215_1",
                "final": "215_1",
                "etiqueta": "#NX215_1",
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

    @Suppress("DEPRECATION")
    private suspend fun geocoderAndRoute(paradas: JSONArray) {
        val geocoder = Geocoder(this, Locale.getDefault())
        val points = mutableListOf<JSONObject>()

        for (i in 0 until paradas.length()) {
            val p = paradas.getJSONObject(i)
            val endereco = p.optString("endereco", "")
            val bairro = p.optString("bairro", "")
            val cep = p.optString("cep", "")

            val queryPrincipal = if (cep.isNotBlank()) {
                "$endereco, $cep, Campinas - SP, Brasil"
            } else if (bairro.isNotBlank()) {
                "$endereco, $bairro, Campinas - SP, Brasil"
            } else {
                "$endereco, Campinas - SP, Brasil"
            }

            try {
                var results = withContext(Dispatchers.IO) { geocoder.getFromLocationName(queryPrincipal, 1) }
                if (results.isNullOrEmpty() && cep.isNotBlank()) {
                    results = withContext(Dispatchers.IO) { geocoder.getFromLocationName("$endereco, Campinas - SP", 1) }
                }

                if (!results.isNullOrEmpty()) {
                    p.put("lat", results[0].latitude)
                    p.put("lng", results[0].longitude)
                } else {
                    // Fallback geográfico realista na região de Campinas (Taquaral/Guanabara)
                    p.put("lat", -22.8850 + (i * 0.003))
                    p.put("lng", -47.0580 + (i * 0.003))
                }
                points.add(p)
            } catch (e: Exception) {
                Log.e("Geocode", "Fail addr: $queryPrincipal", e)
                p.put("lat", -22.8850 + (i * 0.003))
                p.put("lng", -47.0580 + (i * 0.003))
                points.add(p)
            }
        }

        if (points.isEmpty()) return

        // OSRM Trip API para ordenar a rota no menor trajeto
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

        // Se o OSRM der erro de rede, o JavaScript da webview aplica o algoritmo 2-Opt local
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
