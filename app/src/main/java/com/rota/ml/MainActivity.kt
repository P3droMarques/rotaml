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
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val DEFAULT_FIREBASE_URL = "https://rotaml-41f8a-default-rtdb.firebaseio.com"
        const val DEFAULT_FIREBASE_KEY = "NJXaaCyLXfLBP06lK7mSoSGbqJzOVQiC66ZNlOzO"
        const val DEFAULT_FIREBASE_SALA = "rotaml-41f8a-default-rtdb"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var isAudioMuted = false
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
        if (!prefs.contains("Firebase URL") || prefs.getString("Firebase URL", "")?.isBlank() == true) {
            prefs.edit()
                .putString("Firebase URL", DEFAULT_FIREBASE_URL)
                .putString("Firebase Key", DEFAULT_FIREBASE_KEY)
                .putString("Firebase Sala", DEFAULT_FIREBASE_SALA)
                .apply()
        }
        isAudioMuted = prefs.getBoolean("is_audio_muted", false)
        tts = TextToSpeech(this, this)

        setupUI()
        atualizarBotaoMute()
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

        binding.btnMuteAudio.setOnClickListener {
            toggleMuteAudio()
        }

        binding.btnConfig.setOnClickListener {
            showConfigDialog()
        }

        binding.chipAcessibilidade.setOnClickListener {
            solicitarAtivacaoAcessibilidade()
        }
    }

    private fun atualizarBotaoMute() {
        runOnUiThread {
            binding.btnMuteAudio.text = if (isAudioMuted) "🔇" else "🔊"
            binding.btnMuteAudio.alpha = if (isAudioMuted) 0.5f else 1.0f
        }
    }

    private fun toggleMuteAudio() {
        isAudioMuted = !isAudioMuted
        prefs.edit().putBoolean("is_audio_muted", isAudioMuted).apply()
        atualizarBotaoMute()
        if (isAudioMuted) {
            tts?.stop()
        }
        binding.webView.post {
            binding.webView.evaluateJavascript("if (window.setAudioMuted) window.setAudioMuted($isAudioMuted);", null)
        }
        val msg = if (isAudioMuted) "🔇 Som desativado (Mudo)" else "🔊 Som ativado"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
                syncFirebaseBipe(code)
                syncSupabase(code)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Erro ao ler código: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun getFirebaseBaseUrl(): String {
        var url = prefs.getString("Firebase URL", DEFAULT_FIREBASE_URL)?.trim() ?: DEFAULT_FIREBASE_URL
        if (url.isBlank()) url = DEFAULT_FIREBASE_URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length - 1)
        }
        if (!url.contains("firebaseio.com") && !url.contains("googleapis.com")) {
            url = "$url-default-rtdb.firebaseio.com"
        }
        return url
    }

    private fun getFirebaseAuthToken(): String {
        val key = prefs.getString("Firebase Key", DEFAULT_FIREBASE_KEY)?.trim() ?: DEFAULT_FIREBASE_KEY
        return if (key.isNotBlank()) key else DEFAULT_FIREBASE_KEY
    }

    private fun getFirebaseSala(): String {
        val sala = prefs.getString("Firebase Sala", DEFAULT_FIREBASE_SALA)?.trim() ?: DEFAULT_FIREBASE_SALA
        return if (sala.isNotBlank()) sala else DEFAULT_FIREBASE_SALA
    }

    fun syncFirebaseBipe(code: String, paradaNum: Int = 0) {
        val baseUrl = getFirebaseBaseUrl()
        if (baseUrl.isBlank()) return

        val authToken = getFirebaseAuthToken()
        val sala = getFirebaseSala()
        val authParam = if (authToken.isNotBlank()) "?auth=$authToken" else ""
        val url = "$baseUrl/bipes/$sala.json$authParam"

        val bodyJson = JSONObject().apply {
            put("codigo", code)
            put("parada_num", paradaNum)
            put("timestamp", System.currentTimeMillis())
            put("data_hora", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("dispositivo", Build.MODEL)
            put("status", "conferido")
        }

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        scope.launch(Dispatchers.IO) {
            try {
                val resp = client.newCall(request).execute()
                Log.d("FirebaseSync", "Bipe sync status=${resp.code}")
            } catch (e: Exception) {
                Log.e("FirebaseSync", "Erro ao sincronizar bipe no Firebase", e)
            }
        }
    }

    fun salvarRotaNoFirebase(rotaJsonStr: String, callback: ((Boolean, String) -> Unit)? = null) {
        val baseUrl = getFirebaseBaseUrl()
        if (baseUrl.isBlank()) {
            callback?.invoke(false, "Configure a URL do Firebase nas configurações ⚙️")
            return
        }

        val authToken = getFirebaseAuthToken()
        val sala = getFirebaseSala()
        val authParam = if (authToken.isNotBlank()) "?auth=$authToken" else ""
        val url = "$baseUrl/rotas/$sala/atual.json$authParam"

        val bodyJson = JSONObject().apply {
            put("atualizado_em", System.currentTimeMillis())
            put("data_hora", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("dispositivo", Build.MODEL)
            try {
                put("paradas", JSONArray(rotaJsonStr))
            } catch (e: Exception) {
                put("dados_brutos", rotaJsonStr)
            }
        }

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .put(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        scope.launch(Dispatchers.IO) {
            try {
                val resp = client.newCall(request).execute()
                if (resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        callback?.invoke(true, "☁️ Rota salva no Firebase ($sala) com sucesso!")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        callback?.invoke(false, "Erro Firebase (${resp.code}): ${resp.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("FirebaseSync", "Erro ao salvar rota", e)
                withContext(Dispatchers.Main) {
                    callback?.invoke(false, "Falha de conexão com Firebase: ${e.message}")
                }
            }
        }
    }

    fun baixarRotaDoFirebase(callback: ((Boolean, String, String?) -> Unit)? = null) {
        val baseUrl = getFirebaseBaseUrl()
        if (baseUrl.isBlank()) {
            callback?.invoke(false, "Configure a URL do Firebase nas configurações ⚙️", null)
            return
        }

        val authToken = getFirebaseAuthToken()
        val sala = getFirebaseSala()
        val authParam = if (authToken.isNotBlank()) "?auth=$authToken" else ""
        val url = "$baseUrl/rotas/$sala/atual.json$authParam"

        val client = OkHttpClient()
        val request = Request.Builder().url(url).get().build()

        scope.launch(Dispatchers.IO) {
            try {
                val resp = client.newCall(request).execute()
                if (resp.isSuccessful) {
                    val respStr = resp.body?.string() ?: ""
                    if (respStr == "null" || respStr.isBlank()) {
                        withContext(Dispatchers.Main) {
                            callback?.invoke(false, "Nenhuma rota salva no Firebase para a sala '$sala'", null)
                        }
                        return@launch
                    }
                    val respObj = JSONObject(respStr)
                    val paradas = respObj.optJSONArray("paradas")
                    val paradasStr = paradas?.toString() ?: respObj.optString("dados_brutos", "")

                    if (paradasStr.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            callback?.invoke(true, "✅ Rota recuperada do Firebase com sucesso!", paradasStr)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            callback?.invoke(false, "Formato de dados no Firebase não reconhecido", null)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        callback?.invoke(false, "Erro Firebase (${resp.code}): ${resp.message}", null)
                    }
                }
            } catch (e: Exception) {
                Log.e("FirebaseSync", "Erro ao baixar rota", e)
                withContext(Dispatchers.Main) {
                    callback?.invoke(false, "Falha ao conectar ao Firebase: ${e.message}", null)
                }
            }
        }
    }

    fun testarConexaoFirebase(urlInput: String, keyInput: String, salaInput: String, onResult: (Boolean, String) -> Unit) {
        var url = urlInput.trim()
        if (url.isBlank()) {
            onResult(false, "Informe a URL do Firebase")
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        if (url.endsWith("/")) url = url.substring(0, url.length - 1)
        if (!url.contains("firebaseio.com") && !url.contains("googleapis.com")) {
            url = "$url-default-rtdb.firebaseio.com"
        }

        val authParam = if (keyInput.isNotBlank()) "?auth=${keyInput.trim()}" else ""
        val pathPing = if (salaInput.isNotBlank()) "ping_${salaInput.trim()}" else "ping"
        val testUrl = "$url/$pathPing.json$authParam"

        val client = OkHttpClient()
        val testBody = JSONObject().put("status", "ok").put("testado_em", System.currentTimeMillis())
        val request = Request.Builder()
            .url(testUrl)
            .put(testBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        scope.launch(Dispatchers.IO) {
            try {
                val resp = client.newCall(request).execute()
                if (resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        onResult(true, "✅ Conexão estabelecida com sucesso com o Firebase!")
                    }
                } else if (resp.code == 401 || resp.code == 403) {
                    withContext(Dispatchers.Main) {
                        onResult(false, "Permissão negada (Código ${resp.code}). Verifique a chave de autenticação ou as regras do Firebase.")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onResult(false, "Firebase respondeu com status ${resp.code}: ${resp.message}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Erro de rede ao conectar: ${e.localizedMessage ?: e.message}")
                }
            }
        }
    }

    private fun showConfigDialog() {
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
        }
        scrollView.addView(container)

        fun createSectionTitle(title: String) = TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FFE600"))
            setPadding(0, 16, 0, 8)
        }

        fun createField(label: String, prefKey: String, hintText: String, defaultVal: String = "", isPassword: Boolean = false): EditText {
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 8, 0, 4)
            }
            val et = EditText(this).apply {
                val current = prefs.getString(prefKey, defaultVal) ?: defaultVal
                setText(if (current.isNotBlank()) current else defaultVal)
                hint = hintText
                setHintTextColor(Color.parseColor("#6B7280"))
                textSize = 14f
                setTextColor(Color.WHITE)
                background = null
                setBackgroundColor(Color.parseColor("#1F2937"))
                setPadding(24, 18, 24, 18)
                if (isPassword) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            container.addView(tv)
            container.addView(et)
            return et
        }

        container.addView(createSectionTitle("🔥 Google Firebase Database"))
        val editFbUrl = createField("URL do Firebase Realtime Database", "Firebase URL", DEFAULT_FIREBASE_URL, defaultVal = DEFAULT_FIREBASE_URL)
        val editFbKey = createField("Chave Secreta / Auth Token do Firebase", "Firebase Key", DEFAULT_FIREBASE_KEY, defaultVal = DEFAULT_FIREBASE_KEY, isPassword = true)
        val editFbSala = createField("Identificador da Sala / Motorista", "Firebase Sala", DEFAULT_FIREBASE_SALA, defaultVal = DEFAULT_FIREBASE_SALA)

        val btnTestFb = com.google.android.material.button.MaterialButton(this).apply {
            text = "🧪 Testar Conexão com Firebase"
            textSize = 12f
            setPadding(0, 12, 0, 12)
            setBackgroundColor(Color.parseColor("#374151"))
            setTextColor(Color.parseColor("#60A5FA"))
            setOnClickListener {
                val url = editFbUrl.text.toString().trim()
                val key = editFbKey.text.toString().trim()
                val sala = editFbSala.text.toString().trim()
                Toast.makeText(this@MainActivity, "Testando conexão Firebase...", Toast.LENGTH_SHORT).show()
                testarConexaoFirebase(url, key, sala) { _, msg ->
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
        val paramsBtn = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 12, 0, 16)
        }
        container.addView(btnTestFb, paramsBtn)

        container.addView(createSectionTitle("🧭 Navegador Padrão"))
        val tvNav = TextView(this).apply {
            text = "Escolha qual aplicativo abrir ao navegar:"
            textSize = 12f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, 4, 0, 8)
        }
        container.addView(tvNav)

        val navOptions = arrayOf("Perguntar Sempre", "🚗 Waze", "🗺️ Google Maps")
        val currentNav = prefs.getString("Navegador Padrao", "Perguntar Sempre") ?: "Perguntar Sempre"
        val selectedNav = when {
            currentNav.contains("Waze", ignoreCase = true) -> 1
            currentNav.contains("Maps", ignoreCase = true) || currentNav.contains("Google", ignoreCase = true) -> 2
            else -> 0
        }

        val radioGroupNav = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        navOptions.forEachIndexed { index, option ->
            val rb = RadioButton(this).apply {
                text = option
                setTextColor(Color.WHITE)
                textSize = 13f
                id = index + 100
                isChecked = (index == selectedNav)
            }
            radioGroupNav.addView(rb)
        }
        container.addView(radioGroupNav)

        container.addView(createSectionTitle("🤖 Google Gemini IA (Chaves Opcionais)"))
        val editGemini1 = createField("Gemini API Key 1", "Gemini Key 1", "AIzaSy...")
        val editGemini2 = createField("Gemini API Key 2 (Backup)", "Gemini Key 2", "AIzaSy...")

        MaterialAlertDialogBuilder(this)
            .setTitle("⚙️ Configurações & Firebase")
            .setView(scrollView)
            .setPositiveButton("Salvar Tudo") { _, _ ->
                val chosenNav = when (radioGroupNav.checkedRadioButtonId) {
                    101 -> "Waze"
                    102 -> "Google Maps"
                    else -> "Perguntar Sempre"
                }

                prefs.edit().apply {
                    putString("Firebase URL", editFbUrl.text.toString().trim())
                    putString("Firebase Key", editFbKey.text.toString().trim())
                    putString("Firebase Sala", editFbSala.text.toString().trim())
                    putString("Navegador Padrao", chosenNav)
                    putString("Gemini Key 1", editGemini1.text.toString().trim())
                    putString("Gemini Key 2", editGemini2.text.toString().trim())
                    apply()
                }

                Toast.makeText(this, "Configurações e chaves salvas com sucesso!", Toast.LENGTH_SHORT).show()
                binding.webView.post {
                    binding.webView.evaluateJavascript("if (window.atualizarStatusFirebase) window.atualizarStatusFirebase();", null)
                }
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
                    .setTitle("📍 Nenhuma Parada Detectada")
                    .setMessage("Para processar e otimizar a rota:\n\n1. Toque em '1. Ler Tela' para ler diretamente do aplicativo Mercado Envios Extra.\n2. Ou toque no ícone 🖼️ (OCR) para escolher fotos/prints da galeria.")
                    .setPositiveButton("📷 Ler Fotos (OCR)") { _, _ ->
                        abrirSeletorGaleria()
                    }
                    .setNegativeButton("Fechar", null)
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
            if (isAudioMuted) return
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UTTERANCE_${System.currentTimeMillis()}")
        }

        @JavascriptInterface
        fun setAudioMuted(muted: Boolean) {
            runOnUiThread {
                if (isAudioMuted != muted) {
                    toggleMuteAudio()
                }
            }
        }

        @JavascriptInterface
        fun isAudioMuted(): Boolean {
            return isAudioMuted
        }

        @JavascriptInterface
        fun toggleMute() {
            runOnUiThread {
                toggleMuteAudio()
            }
        }

        @JavascriptInterface
        fun vibrar(tipo: String) {
            this@MainActivity.executarVibracao(tipo)
        }

        @JavascriptInterface
        fun abrirNavegacao(lat: Double, lng: Double, endereco: String, appNav: String = "chooser") {
            runOnUiThread {
                this@MainActivity.abrirAppNavegacao(lat, lng, endereco, appNav)
            }
        }

        @JavascriptInterface
        fun abrirSeletorGaleria() {
            runOnUiThread {
                this@MainActivity.abrirSeletorGaleria()
            }
        }

        @JavascriptInterface
        fun abrirConfiguracoes() {
            runOnUiThread {
                showConfigDialog()
            }
        }

        @JavascriptInterface
        fun salvarRotaFirebase(rotaJsonStr: String) {
            salvarRotaNoFirebase(rotaJsonStr) { _, msg ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    binding.webView.evaluateJavascript("showToast('${msg.replace("'", "\\'")}');", null)
                }
            }
        }

        @JavascriptInterface
        fun carregarRotaFirebase() {
            baixarRotaDoFirebase { success, msg, rotaJson ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    binding.webView.evaluateJavascript("showToast('${msg.replace("'", "\\'")}');", null)
                    if (success && !rotaJson.isNullOrBlank()) {
                        binding.webView.evaluateJavascript("carregarRota($rotaJson);", null)
                    }
                }
            }
        }

        @JavascriptInterface
        fun syncFirebase(codigo: String, paradaNum: Int = 0) {
            syncFirebaseBipe(codigo, paradaNum)
        }

        @JavascriptInterface
        fun syncBipeFirebase(codigo: String, paradaNum: Int = 0) {
            syncFirebaseBipe(codigo, paradaNum)
        }

        @JavascriptInterface
        fun getFirebaseStatus(): String {
            val url = prefs.getString("Firebase URL", "") ?: ""
            val sala = prefs.getString("Firebase Sala", "rota_extra") ?: "rota_extra"
            val status = JSONObject().apply {
                put("configurado", url.isNotBlank())
                put("url", url)
                put("sala", sala)
            }
            return status.toString()
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

    fun abrirAppNavegacao(lat: Double, lng: Double, endereco: String, appNav: String = "chooser") {
        val prefNav = prefs.getString("Navegador Padrao", "Perguntar Sempre") ?: "Perguntar Sempre"
        val app = if (appNav == "chooser") {
            when {
                prefNav.contains("Waze", ignoreCase = true) -> "waze"
                prefNav.contains("Maps", ignoreCase = true) || prefNav.contains("Google", ignoreCase = true) -> "maps"
                else -> "chooser"
            }
        } else {
            appNav.lowercase()
        }

        when {
            app == "waze" -> abrirWaze(lat, lng, endereco)
            app == "maps" || app == "google" -> abrirGoogleMaps(lat, lng, endereco)
            else -> {
                val opcoes = arrayOf("🚗 Waze", "🗺️ Google Maps")
                var selected = 0
                MaterialAlertDialogBuilder(this)
                    .setTitle("🧭 Escolha o Aplicativo de Navegação")
                    .setSingleChoiceItems(opcoes, 0) { _, which -> selected = which }
                    .setPositiveButton("Navegar") { _, _ ->
                        if (selected == 0) abrirWaze(lat, lng, endereco) else abrirGoogleMaps(lat, lng, endereco)
                    }
                    .setNeutralButton("Definir como Padrão") { _, _ ->
                        val padrao = if (selected == 0) "Waze" else "Google Maps"
                        prefs.edit().putString("Navegador Padrao", padrao).apply()
                        Toast.makeText(this, "$padrao definido como navegador padrão!", Toast.LENGTH_SHORT).show()
                        if (selected == 0) abrirWaze(lat, lng, endereco) else abrirGoogleMaps(lat, lng, endereco)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    private fun abrirWaze(lat: Double, lng: Double, endereco: String) {
        try {
            val uri = if (lat != 0.0 && lng != 0.0) {
                Uri.parse("waze://?ll=$lat,$lng&navigate=yes")
            } else {
                Uri.parse("waze://?q=${Uri.encode(endereco)}&navigate=yes")
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                `package` = "com.waze"
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val webUri = if (lat != 0.0 && lng != 0.0) {
                    Uri.parse("https://waze.com/ul?ll=$lat,$lng&navigate=yes")
                } else {
                    Uri.parse("https://waze.com/ul?q=${Uri.encode(endereco)}&navigate=yes")
                }
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            } catch (ex: Exception) {
                Toast.makeText(this, "Não foi possível abrir o Waze no dispositivo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun abrirGoogleMaps(lat: Double, lng: Double, endereco: String) {
        try {
            val uri = if (lat != 0.0 && lng != 0.0) {
                Uri.parse("google.navigation:q=$lat,$lng&mode=d")
            } else {
                Uri.parse("google.navigation:q=${Uri.encode(endereco)}&mode=d")
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                `package` = "com.google.android.apps.maps"
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackUri = if (lat != 0.0 && lng != 0.0) {
                    Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(endereco)})")
                } else {
                    Uri.parse("geo:0,0?q=${Uri.encode(endereco)}")
                }
                startActivity(Intent(Intent.ACTION_VIEW, fallbackUri))
            } catch (ex: Exception) {
                Toast.makeText(this, "Google Maps não encontrado", Toast.LENGTH_SHORT).show()
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
