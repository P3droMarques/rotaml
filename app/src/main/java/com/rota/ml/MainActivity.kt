package com.rota.ml

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageDecoder
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import kotlin.coroutines.resume

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
    private var capturaProjecaoAtiva = false
    private var solicitacaoProjecaoPendente = false

    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) {
            processarImagensGaleria(uris)
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            iniciarCapturaPorProjecao(result.resultCode, data)
        } else {
            solicitacaoProjecaoPendente = false
            LeitorEnviosService.instance?.pararCapturaEFecharOverlay()
            atualizarBotaoLeitor()
            Toast.makeText(this, "A autorização de captura de tela é necessária para ler ao vivo neste aparelho.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("RotaMLPrefs", Context.MODE_PRIVATE)
        isAudioMuted = prefs.getBoolean("is_audio_muted", false)
        tts = TextToSpeech(this, this)

        setupUI()
        atualizarBotaoMute()
        setupListeners()
        setupLeitorListener()
        LeitorEnviosService.onScreenshotCapabilityError = {
            runOnUiThread {
                if (LeitorEnviosService.capturando && !capturaProjecaoAtiva && !solicitacaoProjecaoPendente) {
                    solicitarCapturaPorProjecao()
                }
            }
        }
        ScreenCaptureService.onError = { message ->
            runOnUiThread {
                capturaProjecaoAtiva = false
                solicitacaoProjecaoPendente = false
                LeitorEnviosService.instance?.pararCapturaEFecharOverlay()
                atualizarBotaoLeitor()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }

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

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var ultimaLocalizacao: Location? = null

    override fun onResume() {
        super.onResume()
        atualizarStatusAcessibilidade()
        atualizarBotaoLeitor()
        iniciarRastreamentoGPS()
    }

    override fun onPause() {
        locationListener?.let { listener ->
            try { locationManager?.removeUpdates(listener) } catch (_: SecurityException) {}
        }
        super.onPause()
    }

    private fun publicarLocalizacao(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val bearing = if (location.hasBearing()) location.bearing else 0f
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        val accuracy = if (location.hasAccuracy()) location.accuracy else -1f
        val timestamp = location.time
        binding.webView.post {
            binding.webView.evaluateJavascript(
                "if (window.atualizarPosicaoCarro) window.atualizarPosicaoCarro($lat, $lng, $bearing, $speedKmh, $accuracy, $timestamp);",
                null
            )
        }
    }

    private fun iniciarRastreamentoGPS() {
        val temPrecisa = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val temAproximada = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!temPrecisa && !temAproximada) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                1001
            )
            return
        }

        try {
            if (locationManager == null) {
                locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            }

            if (locationListener == null) {
                locationListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        val idade = System.currentTimeMillis() - location.time
                        if (idade < -5000L || idade > 120_000L) return
                        val anterior = ultimaLocalizacao
                        if (anterior != null) {
                            if (location.elapsedRealtimeNanos < anterior.elapsedRealtimeNanos) return
                            val intervaloMs = (location.elapsedRealtimeNanos - anterior.elapsedRealtimeNanos) / 1_000_000L
                            if (intervaloMs < 10_000L && anterior.hasAccuracy() && location.hasAccuracy() &&
                                location.accuracy > anterior.accuracy * 2 && location.accuracy > 50f) return
                        }
                        ultimaLocalizacao = location
                        publicarLocalizacao(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
            }

            locationManager?.let { lm ->
                lm.removeUpdates(locationListener!!)
                val isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val isNetEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

                if (isGpsEnabled && temPrecisa) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, locationListener!!)
                }
                if (isNetEnabled) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 2.0f, locationListener!!)
                }

                val lastLoc = listOfNotNull(
                    if (temPrecisa) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null,
                    lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
                    ultimaLocalizacao
                ).filter { System.currentTimeMillis() - it.time in 0..120_000L }
                    .maxByOrNull { it.elapsedRealtimeNanos }
                lastLoc?.let { loc ->
                    ultimaLocalizacao = loc
                    publicarLocalizacao(loc)
                }
            }
        } catch (e: Exception) {
            Log.e("GPS", "Erro ao iniciar rastreamento GPS", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            iniciarRastreamentoGPS()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupUI() {
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.databaseEnabled = true
            settings.setGeolocationEnabled(true)
            settings.userAgentString = settings.userAgentString + " RotaML/1.0 (+https://github.com/P3droMarques/rotaml)"
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                    callback?.invoke(origin, true, false)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    atualizarResumoLeitura(LeitorEnviosService.paradasDetectadas.size)
                    ultimaLocalizacao?.takeIf { System.currentTimeMillis() - it.time in 0..120_000L }?.let { publicarLocalizacao(it) }
                }
            }
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
                atualizarResumoLeitura(totalParadas)
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
        atualizarResumoLeitura(totalParadas)
    }

    private fun atualizarResumoLeitura(total: Int) {
        val capturando = LeitorEnviosService.capturando
        binding.webView.post {
            binding.webView.evaluateJavascript("if (window.atualizarResumoLeitura) window.atualizarResumoLeitura($total, $capturando);", null)
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

        val leitor = LeitorEnviosService.instance
        if (leitor == null || !LeitorEnviosService.isRunning) {
            // O Android pode levar alguns instantes para conectar o serviço
            // depois que a acessibilidade foi ativada. Não marque a captura
            // como ativa sem um serviço real: isso criava um estado falso,
            // sem overlay e sem OCR.
            Toast.makeText(this, "O leitor ainda está iniciando. Abra a Acessibilidade, aguarde alguns segundos e tente novamente.", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {}
            return
        }

        if (!LeitorEnviosService.capturando) {
            leitor.iniciarCapturaComOverlay()
            if (!leitor.podeCapturarTela()) {
                solicitarCapturaPorProjecao()
            }
            binding.btnLer.text = "⏹ Lendo (0)..."
            binding.btnLer.setTextColor(Color.parseColor("#EF4444"))
            Toast.makeText(this, "Abra o app Envios Extra e role a lista devagar. A tela visível será lida automaticamente.", Toast.LENGTH_LONG).show()
        } else {
            leitor.pararCapturaEFecharOverlay()
            pararCapturaPorProjecao()
            val total = LeitorEnviosService.paradasDetectadas.size
            binding.btnLer.text = if (total > 0) "1. Ler Tela ($total)" else "1. Ler Tela"
            binding.btnLer.setTextColor(Color.parseColor("#F3F4F6"))
            Toast.makeText(this, "Captura concluída: $total paradas identificadas!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun solicitarCapturaPorProjecao() {
        if (capturaProjecaoAtiva || solicitacaoProjecaoPendente) return
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            solicitacaoProjecaoPendente = true
            Toast.makeText(this, "Autorize a captura de tela para ativar a leitura ao vivo.", Toast.LENGTH_LONG).show()
            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
        } catch (e: Exception) {
            solicitacaoProjecaoPendente = false
            Log.e("ScreenCapture", "Não foi possível solicitar captura de tela", e)
            LeitorEnviosService.instance?.pararCapturaEFecharOverlay()
            atualizarBotaoLeitor()
            Toast.makeText(this, "Este aparelho não permitiu a captura de tela ao vivo.", Toast.LENGTH_LONG).show()
        }
    }
    private fun iniciarCapturaPorProjecao(resultCode: Int, data: Intent) {
        try {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            solicitacaoProjecaoPendente = false
            capturaProjecaoAtiva = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Captura autorizada. Abra o Envios Extra e role as paradas.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            solicitacaoProjecaoPendente = false
            capturaProjecaoAtiva = false
            Log.e("ScreenCapture", "Erro iniciando serviço de captura", e)
            LeitorEnviosService.instance?.pararCapturaEFecharOverlay()
            atualizarBotaoLeitor()
            Toast.makeText(this, "Erro ao iniciar a captura de tela: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private fun pararCapturaPorProjecao() {
        solicitacaoProjecaoPendente = false
        stopService(Intent(this, ScreenCaptureService::class.java))
        capturaProjecaoAtiva = false
    }
    private fun abrirSeletorGaleria() {
        try {
            pickImagesLauncher.launch("image/*")
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao abrir galeria: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processarImagensGaleria(uris: List<Uri>) {
        // Uma nova rota deve começar somente pelas fotos escolhidas agora;
        // nunca misture o resultado de uma captura anterior.
        LeitorEnviosService.limpar()
        binding.progressBar.visibility = View.VISIBLE
        Toast.makeText(this, "Lendo ${uris.size} foto(s) com IA (OCR)...", Toast.LENGTH_SHORT).show()

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val leituras = MutableList<List<String>>(uris.size) { emptyList() }
        var processadas = 0

        for ((indice, uri) in uris.withIndex()) {
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
                        leituras[indice] = linhasOcrEmOrdem(visionText)
                        LeitorEnviosService.textosCapturados.addAll(leituras[indice])
                        processadas++
                        if (processadas == uris.size) {
                            finalizarProcessamentoOcr(leituras)
                        }
                    }
                    .addOnFailureListener {
                        processadas++
                        if (processadas == uris.size) {
                            finalizarProcessamentoOcr(leituras)
                        }
                    }
                    .addOnCompleteListener { bitmap.recycle() }
            } catch (e: Exception) {
                Log.e("OCR", "Erro carregando imagem $uri", e)
                processadas++
                if (processadas == uris.size) {
                    finalizarProcessamentoOcr(leituras)
                }
            }
        }
    }

    private fun finalizarProcessamentoOcr(leituras: List<List<String>>) {
        scope.launch {
            binding.progressBar.visibility = View.GONE
            val paradas = mutableListOf<ParadaExtra>()
            leituras.forEach { EnviosExtraParser.mesclarLeituras(paradas, EnviosExtraParser.parseLinhas(it)) }
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
                    binding.webView.evaluateJavascript("confirmarBipe(${JSONObject.quote(code)})", null)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Erro ao ler código: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun getFirebaseBaseUrl(): String {
        return DEFAULT_FIREBASE_URL
    }

    private fun getFirebaseAuthToken(): String {
        return DEFAULT_FIREBASE_KEY
    }

    private fun getFirebaseSala(): String {
        return DEFAULT_FIREBASE_SALA
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

    fun firebaseListarRotas(callback: ((Boolean, String, String?) -> Unit)? = null) {
        val baseUrl = getFirebaseBaseUrl()
        if (baseUrl.isBlank()) {
            callback?.invoke(false, "Configure a URL do Firebase nas configurações ⚙️", null)
            return
        }

        val authToken = getFirebaseAuthToken()
        val sala = getFirebaseSala()
        val authParam = if (authToken.isNotBlank()) "?auth=$authToken" else ""
        val url = "$baseUrl/rotas/$sala.json$authParam"

        val client = OkHttpClient()
        val request = Request.Builder().url(url).get().build()

        scope.launch(Dispatchers.IO) {
            try {
                val resp = client.newCall(request).execute()
                if (resp.isSuccessful) {
                    val respStr = resp.body?.string() ?: "{}"
                    withContext(Dispatchers.Main) {
                        callback?.invoke(true, "Rotas carregadas da nuvem", respStr)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        callback?.invoke(false, "Erro Firebase (${resp.code}): ${resp.message}", null)
                    }
                }
            } catch (e: Exception) {
                Log.e("FirebaseSync", "Erro ao listar rotas", e)
                withContext(Dispatchers.Main) {
                    callback?.invoke(false, "Falha ao conectar ao Firebase: ${e.message}", null)
                }
            }
        }
    }

    fun firebaseSalvarRota(rotaId: String, rotaJsonStr: String, callback: ((Boolean, String) -> Unit)? = null) {
        val baseUrl = getFirebaseBaseUrl()
        if (baseUrl.isBlank()) {
            callback?.invoke(false, "Configure a URL do Firebase nas configurações ⚙️")
            return
        }

        val authToken = getFirebaseAuthToken()
        val sala = getFirebaseSala()
        val authParam = if (authToken.isNotBlank()) "?auth=$authToken" else ""
        val url = "$baseUrl/rotas/$sala/$rotaId.json$authParam"

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .put(rotaJsonStr.toRequestBody("application/json".toMediaType()))
            .build()

        scope.launch(Dispatchers.IO) {
            try {
                val resp = client.newCall(request).execute()
                if (resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        callback?.invoke(true, "☁️ Rota salva automaticamente no Firebase!")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        callback?.invoke(false, "Erro Firebase (${resp.code}): ${resp.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("FirebaseSync", "Erro ao salvar rota", e)
                withContext(Dispatchers.Main) {
                    callback?.invoke(false, "Falha ao conectar ao Firebase: ${e.message}")
                }
            }
        }
    }

    fun firebaseExcluirRota(rotaId: String, callback: ((Boolean, String) -> Unit)? = null) {
        val baseUrl = getFirebaseBaseUrl()
        if (baseUrl.isBlank()) {
            callback?.invoke(false, "Configure a URL do Firebase")
            return
        }

        val authToken = getFirebaseAuthToken()
        val sala = getFirebaseSala()
        val authParam = if (authToken.isNotBlank()) "?auth=$authToken" else ""
        val url = "$baseUrl/rotas/$sala/$rotaId.json$authParam"

        val client = OkHttpClient()
        val request = Request.Builder().url(url).delete().build()

        scope.launch(Dispatchers.IO) {
            try {
                val resp = client.newCall(request).execute()
                if (resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        callback?.invoke(true, "🗑️ Rota excluída do Firebase com sucesso!")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        callback?.invoke(false, "Erro ao excluir (${resp.code}): ${resp.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("FirebaseSync", "Erro ao excluir rota", e)
                withContext(Dispatchers.Main) {
                    callback?.invoke(false, "Falha de rede ao excluir: ${e.message}")
                }
            }
        }
    }

    fun firebaseAtualizarStatusParada(rotaId: String, paradaIndex: Int, finalizada: Boolean, finalizadaEm: String, callback: ((Boolean, String) -> Unit)? = null) {
        val baseUrl = getFirebaseBaseUrl()
        if (baseUrl.isBlank()) return

        val authToken = getFirebaseAuthToken()
        val sala = getFirebaseSala()
        val authParam = if (authToken.isNotBlank()) "?auth=$authToken" else ""
        val url = "$baseUrl/rotas/$sala/$rotaId/paradas/$paradaIndex.json$authParam"

        val patchBody = JSONObject().apply {
            put("finalizada", finalizada)
            put("finalizadaEm", finalizadaEm)
        }

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .patch(patchBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        scope.launch(Dispatchers.IO) {
            try {
                val resp = client.newCall(request).execute()
                if (resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        callback?.invoke(true, "Status da parada sincronizado no Firebase")
                    }
                }
            } catch (e: Exception) {
                Log.e("FirebaseSync", "Erro ao atualizar parada", e)
            }
        }
    }

    fun firebaseFinalizarRota(rotaId: String, status: String, callback: ((Boolean, String) -> Unit)? = null) {
        val baseUrl = getFirebaseBaseUrl()
        if (baseUrl.isBlank()) return

        val authToken = getFirebaseAuthToken()
        val sala = getFirebaseSala()
        val authParam = if (authToken.isNotBlank()) "?auth=$authToken" else ""
        val url = "$baseUrl/rotas/$sala/$rotaId.json$authParam"

        val patchBody = JSONObject().apply {
            put("status", status)
            put("atualizado_em", System.currentTimeMillis())
            if (status == "finalizada") {
                put("finalizada_em", System.currentTimeMillis())
                put("finalizada_em_str", SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date()))
            }
        }

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .patch(patchBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        scope.launch(Dispatchers.IO) {
            try {
                val resp = client.newCall(request).execute()
                if (resp.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        val msg = if (status == "finalizada") "🏁 Rota finalizada e sincronizada na nuvem!" else "🔄 Rota reaberta na nuvem!"
                        callback?.invoke(true, msg)
                    }
                }
            } catch (e: Exception) {
                Log.e("FirebaseSync", "Erro ao finalizar rota", e)
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

        container.addView(createSectionTitle("🏠 Retorno para casa"))
        val editEnderecoCasa = createField(
            "Endereço de casa (destino final da rota)",
            "Endereço de casa",
            "Rua, número, bairro, cidade - UF"
        )

        container.addView(createSectionTitle("🤖 Google Gemini IA (Chaves Opcionais)"))
        val editGemini1 = createField("Gemini API Key 1", "Gemini Key 1", "AIzaSy...")
        val editGemini2 = createField("Gemini API Key 2 (Backup)", "Gemini Key 2", "AIzaSy...")

        MaterialAlertDialogBuilder(this)
            .setTitle("Configurações")
            .setView(scrollView)
            .setPositiveButton("Salvar Tudo") { _, _ ->
                val chosenNav = when (radioGroupNav.checkedRadioButtonId) {
                    101 -> "Waze"
                    102 -> "Google Maps"
                    else -> "Perguntar Sempre"
                }

                prefs.edit().apply {
                    putString("Navegador Padrao", chosenNav)
                    putString("Endereço de casa", editEnderecoCasa.text.toString().trim())
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
            if (paradasProntas.isEmpty()) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("📍 Nenhuma Parada Detectada")
                    .setMessage("Não foi possível identificar paradas completas. Leia novamente a tela do Envios Extra ou selecione fotos nítidas da rota.")
                    .setPositiveButton("📷 Ler Fotos (OCR)") { _, _ ->
                        abrirSeletorGaleria()
                    }
                    .setNegativeButton("Fechar", null)
                    .show()
                return@launch
            }

            val enderecoCasa = prefs.getString("Endereço de casa", "")?.trim().orEmpty()
            if (enderecoCasa.isBlank()) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("🏠 Configure seu endereço de casa")
                    .setMessage("O endereço de casa será usado como destino final para calcular a volta mais econômica.")
                    .setPositiveButton("Abrir configurações") { _, _ -> showConfigDialog() }
                    .setNegativeButton("Cancelar", null)
                    .show()
                return@launch
            }

            binding.progressBar.visibility = View.VISIBLE
            Toast.makeText(this@MainActivity, "Obtendo GPS atual e calculando a melhor rota pelas ruas...", Toast.LENGTH_SHORT).show()

            val paradasJsonArray = JSONArray()

            // 1. Extração em tempo real já estruturada pelo LeitorEnviosService
            for (p in paradasProntas) paradasJsonArray.put(p.toJson())

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

    private fun obterLocalizacaoAtual(): Location? {
        val candidata = ultimaLocalizacao ?: try {
            locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            null
        }
        return candidata?.takeIf { System.currentTimeMillis() - it.time <= 5 * 60 * 1000L }
    }

    @SuppressLint("MissingPermission")
    private suspend fun obterLocalizacaoAtualParaRota(): Location? {
        iniciarRastreamentoGPS()
        val lm = locationManager ?: return null
        val provedores = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider ->
                val precisa = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                try { (provider != LocationManager.GPS_PROVIDER || precisa) && lm.isProviderEnabled(provider) } catch (_: Exception) { false }
            }

        val atuais = mutableListOf<Location>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (provider in provedores) {
                val location = withTimeoutOrNull(6500L) {
                    suspendCancellableCoroutine<Location?> { continuation ->
                        val cancelamento = CancellationSignal()
                        continuation.invokeOnCancellation { cancelamento.cancel() }
                        try {
                            lm.getCurrentLocation(provider, cancelamento, mainExecutor) { atual ->
                                if (continuation.isActive) continuation.resume(atual)
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
                location?.let { atuais.add(it) }
            }
        }

        val melhor = (atuais + listOfNotNull(
            try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Exception) { null },
            try { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { null },
            ultimaLocalizacao
        )).filter { location ->
            val idade = System.currentTimeMillis() - location.time
            val precisao = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
            idade in 0..120_000L && (precisao <= 200f || !location.hasAccuracy())
        }.minWithOrNull(compareBy<Location> {
            if (it.hasAccuracy()) it.accuracy else 200f
        }.thenByDescending { it.time })

        melhor?.let {
            ultimaLocalizacao = it
            Log.d("GPS", "Posição atual para rota: ${it.latitude}, ${it.longitude}, precisão=${it.accuracy}m")
        }
        return melhor
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
        val origemAtual = obterLocalizacaoAtualParaRota()
        val enderecoCasa = prefs.getString("Endereço de casa", "")?.trim().orEmpty()
        if (origemAtual == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Não consegui obter uma localização GPS recente. Ative a localização e tente novamente.", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (enderecoCasa.isBlank()) return
        suspend fun geocodificar(query: String): android.location.Address? {
            val resultados = withContext(Dispatchers.IO) { geocoder.getFromLocationName(query, 1) }
            return resultados?.firstOrNull()
        }
        val entregas = mutableListOf<JSONObject>()
        val enderecosNaoLocalizados = mutableListOf<String>()
        for (i in 0 until paradas.length()) {
            val p = paradas.getJSONObject(i)
            if (!p.has("parada_original")) {
                val numeroOriginal = p.optInt("parada_num", 0)
                p.put("parada_original", if (numeroOriginal > 0) numeroOriginal else JSONObject.NULL)
            }
            val endereco = p.optString("endereco", "")
            val bairro = p.optString("bairro", "")
            val cep = p.optString("cep", "")
            val cidade = p.optString("cidade", "Campinas")
            val query = when {
                cep.isNotBlank() -> "$endereco, $cep, $cidade, SP, Brasil"
                bairro.isNotBlank() -> "$endereco, $bairro, $cidade, SP, Brasil"
                else -> "$endereco, $cidade, SP, Brasil"
            }
            try {
                val location = geocodificar(query)
                if (location == null) enderecosNaoLocalizados.add(endereco)
                else {
                    p.put("lat", location.latitude)
                    p.put("lng", location.longitude)
                    p.put("_tipo_rota", "entrega")
                    entregas.add(p)
                }
            } catch (e: Exception) {
                Log.e("Geocode", "Falha localizando: $query", e)
                enderecosNaoLocalizados.add(endereco)
            }
        }
        val casaLocation = try { geocodificar("$enderecoCasa, Brasil") } catch (e: Exception) {
            Log.e("Geocode", "Falha localizando casa", e)
            null
        }
        if (enderecosNaoLocalizados.isNotEmpty() || casaLocation == null || entregas.isEmpty()) {
            withContext(Dispatchers.Main) {
                val detalhe = if (enderecosNaoLocalizados.isNotEmpty()) {
                    "\n\nNão localizado: ${enderecosNaoLocalizados.take(2).joinToString("; ")}"
                } else ""
                Toast.makeText(this@MainActivity, "Não foi possível localizar todos os endereços. Revise os dados e tente novamente.$detalhe", Toast.LENGTH_LONG).show()
            }
            return
        }
        val origem = JSONObject().apply {
            put("_tipo_rota", "origem")
            put("endereco", "Sua localização atual")
            put("lat", origemAtual.latitude)
            put("lng", origemAtual.longitude)
            put("precisao_m", if (origemAtual.hasAccuracy()) origemAtual.accuracy else JSONObject.NULL)
            put("capturada_em", origemAtual.time)
        }
        val casa = JSONObject().apply {
            put("_tipo_rota", "casa")
            put("endereco", enderecoCasa)
            put("lat", casaLocation.latitude)
            put("lng", casaLocation.longitude)
        }
        val pontosRoteamento = mutableListOf<JSONObject>().apply {
            add(origem)
            addAll(entregas)
            add(casa)
        }
        var ordenados = ordenarEntregasPorDistancia(entregas, origem, casa)
        var routeInfo = JSONObject().put("fonte", "estimativa offline").put("distancia_m", 0).put("duracao_s", 0)

        // A ordem é calculada pela distância de carro entre todos os pontos,
        // mantendo a localização atual como origem e a casa como destino final.
        val planoPorRuas = otimizarPorDistanciaRodoviaria(pontosRoteamento, entregas)
        if (planoPorRuas != null) {
            ordenados = planoPorRuas.entregasOrdenadas
            routeInfo = JSONObject()
                .put("fonte", "OSRM matriz de ruas")
                .put("distancia_m", planoPorRuas.distanciaMetros)
                .put("duracao_s", planoPorRuas.duracaoSegundos)
                .put("distancia_original_m", if (planoPorRuas.distanciaOriginalMetros.isFinite()) planoPorRuas.distanciaOriginalMetros else JSONObject.NULL)
                .put("comparacao", "ordem importada")
        }

        // Mantém um segundo fallback de ordenação viária caso o servidor de
        // matriz esteja temporariamente indisponível.
        val coords = pontosRoteamento.joinToString(";") { "${it.getDouble("lng")},${it.getDouble("lat")}" }
        val osrmUrl = "https://router.project-osrm.org/trip/v1/driving/$coords?source=first&destination=last&roundtrip=false&geometries=geojson&overview=full"
        try {
            if (planoPorRuas == null) {
                val client = OkHttpClient.Builder().connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
                val request = Request.Builder().url(osrmUrl).header("User-Agent", "RotaML/3.0 Android").build()
                val tripJson = withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) JSONObject(resp.body?.string() ?: "{}") else null
                    }
                }
                if (tripJson != null) {
                        val waypoints = tripJson.optJSONArray("waypoints")
                        if (waypoints != null && waypoints.length() == pontosRoteamento.size) {
                            val orderedAll = (0 until waypoints.length())
                                .map { i -> waypoints.getJSONObject(i).getInt("waypoint_index") to i }
                                .sortedBy { it.first }
                                .map { pontosRoteamento[it.second] }
                            ordenados = orderedAll.filter { it.optString("_tipo_rota") == "entrega" }.toMutableList()
                            val trip = tripJson.optJSONArray("trips")?.optJSONObject(0)
                            routeInfo = JSONObject().put("fonte", "ruas OSRM").put("distancia_m", trip?.optDouble("distance", 0.0) ?: 0.0).put("duracao_s", trip?.optDouble("duration", 0.0) ?: 0.0)
                        }
                }
            }
        } catch (e: Exception) {
            Log.e("OSRM", "Roteamento online indisponível; usando sequência offline", e)
        }
        val orderedJson = JSONArray()
        ordenados.forEach { orderedJson.put(it) }
        binding.webView.post {
            binding.webView.evaluateJavascript("carregarRota($orderedJson, null, null, ${origem}, ${casa}, $routeInfo)", null)
        }
    }

    private data class PlanoRota(
        val entregasOrdenadas: MutableList<JSONObject>,
        val distanciaMetros: Double,
        val duracaoSegundos: Double,
        val distanciaOriginalMetros: Double
    )

    private suspend fun otimizarPorDistanciaRodoviaria(
        pontos: List<JSONObject>,
        entregas: List<JSONObject>
    ): PlanoRota? {
        if (pontos.size !in (entregas.size + 1)..(entregas.size + 2) || entregas.isEmpty()) return null
        val temCasa = pontos.size == entregas.size + 2

        val coords = pontos.joinToString(";") { "${it.getDouble("lng")},${it.getDouble("lat")}" }
        val urls = listOf(
            "https://router.project-osrm.org/table/v1/driving/$coords?annotations=distance,duration",
            "https://routing.openstreetmap.de/routed-car/table/v1/driving/$coords?annotations=distance,duration"
        )
        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        var tableJson: JSONObject? = null
        for (url in urls) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", "RotaML/3.0 Android").build()
                val json = withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) JSONObject(resp.body?.string() ?: "{}") else null
                    }
                }
                if (json?.optJSONArray("distances") != null && json.optJSONArray("durations") != null) tableJson = json
                if (tableJson != null) break
            } catch (e: Exception) {
                Log.w("OSRM", "Matriz de rotas indisponível: ${e.message}")
            }
        }

        val json = tableJson ?: return null
        val distancesJson = json.optJSONArray("distances") ?: return null
        val durationsJson = json.optJSONArray("durations") ?: return null
        val totalPontos = pontos.size
        if (distancesJson.length() != totalPontos || durationsJson.length() != totalPontos) return null

        val distances = Array(totalPontos) { DoubleArray(totalPontos) { Double.POSITIVE_INFINITY } }
        val durations = Array(totalPontos) { DoubleArray(totalPontos) { Double.POSITIVE_INFINITY } }
        for (i in 0 until totalPontos) {
            val distanceRow = distancesJson.optJSONArray(i) ?: return null
            val durationRow = durationsJson.optJSONArray(i) ?: return null
            if (distanceRow.length() != totalPontos || durationRow.length() != totalPontos) return null
            for (j in 0 until totalPontos) {
                distances[i][j] = distanceRow.optDouble(j, Double.POSITIVE_INFINITY)
                durations[i][j] = durationRow.optDouble(j, Double.POSITIVE_INFINITY)
            }
        }

        fun custoDaRota(rota: List<Int>, matriz: Array<DoubleArray>): Double {
            var total = 0.0
            for (i in 0 until rota.size - 1) {
                val trecho = matriz[rota[i]][rota[i + 1]]
                if (!trecho.isFinite()) return Double.POSITIVE_INFINITY
                total += trecho
            }
            return total
        }

        fun melhorarDoisOpt(rotaInicial: MutableList<Int>): MutableList<Int> {
            val rota = rotaInicial.toMutableList()
            var melhorou = true
            var iteracoes = 0
            while (melhorou && iteracoes++ < 100) {
                melhorou = false
                val limite = if (temCasa) rota.size - 1 else rota.size
                for (i in 1 until (limite - 1)) {
                    for (k in (i + 1) until limite) {
                        val candidata = rota.toMutableList()
                        candidata.subList(i, k + 1).reverse()
                        if (custoDaRota(candidata, distances) < custoDaRota(rota, distances) - 0.5) {
                            rota.subList(i, k + 1).reverse()
                            melhorou = true
                        }
                    }
                }
            }
            return rota
        }

        val indicesEntregas = (1..entregas.size).toList()
        val ordemOriginal = mutableListOf(0).apply { addAll(indicesEntregas); if (temCasa) add(totalPontos - 1) }
        val distanciaOriginal = custoDaRota(ordemOriginal, distances)
        var melhorRota: MutableList<Int>? = ordemOriginal
        var melhorDistancia = distanciaOriginal

        // Testa cada parada como possível primeira entrega. Isso evita que a
        // escolha gulosa inicial prenda a rota em uma sequência ruim.
        for (primeira in indicesEntregas) {
            val rota = mutableListOf(0, primeira)
            val restantes = indicesEntregas.filter { it != primeira }.toMutableList()
            while (restantes.isNotEmpty()) {
                val atual = rota.last()
                val proxima = restantes.minByOrNull { distances[atual][it] } ?: break
                rota.add(proxima)
                restantes.remove(proxima)
            }
            if (temCasa) rota.add(totalPontos - 1)
            val melhorada = melhorarDoisOpt(rota)
            val distancia = custoDaRota(melhorada, distances)
            if (distancia < melhorDistancia) {
                melhorDistancia = distancia
                melhorRota = melhorada
            }
        }

        val rotaFinal = melhorRota ?: return null
        val duracao = custoDaRota(rotaFinal, durations)
        if (!melhorDistancia.isFinite() || !duracao.isFinite()) return null
        return PlanoRota(
            entregasOrdenadas = (if (temCasa) rotaFinal.drop(1).dropLast(1) else rotaFinal.drop(1)).map { pontos[it] }.toMutableList(),
            distanciaMetros = melhorDistancia,
            duracaoSegundos = duracao,
            distanciaOriginalMetros = distanciaOriginal
        )
    }
    private fun ordenarEntregasPorDistancia(entregas: List<JSONObject>, origem: JSONObject, casa: JSONObject): MutableList<JSONObject> {
        val restantes = entregas.toMutableList()
        val resultado = mutableListOf<JSONObject>()
        var atualLat = origem.getDouble("lat")
        var atualLng = origem.getDouble("lng")
        while (restantes.isNotEmpty()) {
            val proximo = restantes.minByOrNull { distanciaEmLinhaKm(atualLat, atualLng, it.getDouble("lat"), it.getDouble("lng")) } ?: break
            restantes.remove(proximo)
            resultado.add(proximo)
            atualLat = proximo.getDouble("lat")
            atualLng = proximo.getDouble("lng")
        }
        var melhorou = true
        var iteracao = 0
        while (melhorou && iteracao++ < 80) {
            melhorou = false
            for (i in 0 until (resultado.size - 1)) {
                for (k in i + 1 until resultado.size) {
                    val anterior = if (i == 0) origem else resultado[i - 1]
                    val atual = resultado[i]
                    val finalTrecho = resultado[k]
                    val proximo = if (k + 1 < resultado.size) resultado[k + 1] else casa
                    val distanciaAtual = distanciaEntrePontos(anterior, atual) + distanciaEntrePontos(finalTrecho, proximo)
                    val distanciaInvertida = distanciaEntrePontos(anterior, finalTrecho) + distanciaEntrePontos(atual, proximo)
                    if (distanciaInvertida < distanciaAtual - 0.001) {
                        val invertido = resultado.subList(i, k + 1).reversed()
                        for (offset in invertido.indices) resultado[i + offset] = invertido[offset]
                        melhorou = true
                    }
                }
            }
        }
        return resultado
    }

    private fun distanciaEntrePontos(a: JSONObject, b: JSONObject): Double {
        return distanciaEmLinhaKm(a.getDouble("lat"), a.getDouble("lng"), b.getDouble("lat"), b.getDouble("lng"))
    }
    private fun distanciaEmLinhaKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) + kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        return 6371.0 * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }
    fun buscarGeometriaRuasOSRM(coordsList: String, callback: ((Boolean, String?) -> Unit)? = null) {
        if (coordsList.isBlank()) {
            callback?.invoke(false, null)
            return
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val routingServers = listOf(
            "https://routing.openstreetmap.de/routed-car/route/v1/driving/",
            "https://router.project-osrm.org/route/v1/driving/"
        )

        scope.launch(Dispatchers.IO) {
            // 1. Tentar query completa em servidores com prioridade no openstreetmap.de (rápido e estável)
            for (baseUrl in routingServers) {
                try {
                    val url = "$baseUrl$coordsList?overview=full&geometries=geojson"
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; RotaML/2.0)")
                        .build()

                    val resp = client.newCall(request).execute()
                    if (resp.isSuccessful) {
                        val respStr = resp.body?.string() ?: "{}"
                        val json = JSONObject(respStr)
                        val routes = json.optJSONArray("routes")
                        if (routes != null && routes.length() > 0) {
                            val rota = routes.getJSONObject(0)
                            val geom = rota.optJSONObject("geometry")
                            val coords = geom?.optJSONArray("coordinates")
                            if (coords != null && coords.length() > 0) {
                                Log.d("OSRM", "Geometria completa obtida de $baseUrl: ${coords.length()} coordenadas")
                                withContext(Dispatchers.Main) {
                                    callback?.invoke(true, JSONObject().put("coordinates", coords).put("distance", rota.optDouble("distance", 0.0)).toString())
                                }
                                return@launch
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("OSRM", "Tentativa no servidor $baseUrl falhou: ${e.message}")
                }
            }

            // 2. Se a rota inteira falhar, calcula perna a perna (per-leg) para garantir traçado das ruas
            val pontos = coordsList.split(";").filter { it.isNotBlank() }
            if (pontos.size >= 2) {
                try {
                    val accumulatedCoords = JSONArray()
                    var todosOsTrechos = true
                    var distanciaTotal = 0.0

                    for (i in 0 until pontos.size - 1) {
                        val legQuery = "${pontos[i]};${pontos[i + 1]}"
                        var legCoords: JSONArray? = null
                        var distanciaTrecho = Double.NaN

                        for (baseUrl in routingServers) {
                            try {
                                val url = "$baseUrl$legQuery?overview=full&geometries=geojson"
                                val request = Request.Builder()
                                    .url(url)
                                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile; RotaML/2.0)")
                                    .build()
                                val resp = client.newCall(request).execute()
                                if (resp.isSuccessful) {
                                    val json = JSONObject(resp.body?.string() ?: "{}")
                                    val routes = json.optJSONArray("routes")
                                    if (routes != null && routes.length() > 0) {
                                        val rota = routes.getJSONObject(0)
                                        legCoords = rota.optJSONObject("geometry")?.optJSONArray("coordinates")
                                        distanciaTrecho = rota.optDouble("distance", Double.NaN)
                                        if (legCoords != null && legCoords.length() > 0) {
                                            break
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }

                        if (legCoords != null && legCoords.length() > 0) {
                            if (!distanciaTrecho.isFinite()) { todosOsTrechos = false; break }
                            distanciaTotal += distanciaTrecho
                            val startIdx = if (accumulatedCoords.length() > 0) 1 else 0
                            for (k in startIdx until legCoords.length()) {
                                accumulatedCoords.put(legCoords.getJSONArray(k))
                            }
                        } else {
                            todosOsTrechos = false
                            break
                        }
                    }

                    if (todosOsTrechos && accumulatedCoords.length() > 0) {
                        Log.d("OSRM", "Geometria segmentada gerada: ${accumulatedCoords.length()} pontos")
                        withContext(Dispatchers.Main) {
                            callback?.invoke(true, JSONObject().put("coordinates", accumulatedCoords).put("distance", distanciaTotal).toString())
                        }
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e("OSRM", "Erro na reconstrução per-leg", e)
                }
            }

            withContext(Dispatchers.Main) {
                callback?.invoke(false, null)
            }
        }
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun solicitarRastreamentoGPS() {
            runOnUiThread {
                iniciarRastreamentoGPS()
            }
        }

        @JavascriptInterface
        fun buscarGeometriaRuasOSRM(coordsList: String, requestId: String) {
            this@MainActivity.buscarGeometriaRuasOSRM(coordsList) { success, coordsJson ->
                runOnUiThread {
                    val safe = if (success && coordsJson != null) coordsJson else "[]"
                    binding.webView.evaluateJavascript("if (window.receberGeometriaRuasOSRM) window.receberGeometriaRuasOSRM($safe, $success, ${JSONObject.quote(requestId)});", null)
                }
            }
        }

        @JavascriptInterface
        fun recalcularRotaSalvaPartindoDoGPS(paradasJson: String, casaJson: String, requestId: String) {
            scope.launch {
                fun erro(mensagem: String) {
                    binding.webView.evaluateJavascript("if(window.falhaOtimizacao) window.falhaOtimizacao(${JSONObject.quote(requestId)}, ${JSONObject.quote(mensagem)});", null)
                }
                try {
                    val origemAtual = obterLocalizacaoAtualParaRota()
                    if (origemAtual == null) {
                        erro("Não foi possível obter o GPS atual. Ative a localização precisa e tente novamente.")
                        return@launch
                    }
                    val paradas = JSONArray(paradasJson)
                    var casa = if (casaJson == "null" || casaJson.isBlank()) null else JSONObject(casaJson)
                    if (paradas.length() == 0) {
                        erro("Não há paradas pendentes para otimizar.")
                        return@launch
                    }
                    val enderecoCasa = prefs.getString("Endereço de casa", "")?.trim().orEmpty()
                    if ((casa == null || !casa.has("lat") || !casa.has("lng")) && enderecoCasa.isNotBlank()) {
                        @Suppress("DEPRECATION")
                        val resultado = withContext(Dispatchers.IO) {
                            Geocoder(this@MainActivity, Locale.getDefault()).getFromLocationName("$enderecoCasa, Brasil", 1)?.firstOrNull()
                        }
                        if (resultado == null) {
                            erro("Não foi possível localizar seu endereço de casa. Revise-o nas configurações.")
                            return@launch
                        }
                        casa = JSONObject().put("lat", resultado.latitude).put("lng", resultado.longitude).put("endereco", enderecoCasa)
                    }

                    val origem = JSONObject().apply {
                        put("_tipo_rota", "origem")
                        put("endereco", "Sua localização atual")
                        put("lat", origemAtual.latitude)
                        put("lng", origemAtual.longitude)
                        put("precisao_m", if (origemAtual.hasAccuracy()) origemAtual.accuracy else JSONObject.NULL)
                        put("capturada_em", origemAtual.time)
                    }
                    val entregas = (0 until paradas.length()).map { paradas.getJSONObject(it) }
                    if (entregas.any { !it.has("lat") || !it.has("lng") || it.isNull("lat") || it.isNull("lng") }) {
                        erro("Há paradas sem coordenadas. Leia os endereços novamente e gere a rota.")
                        return@launch
                    }
                    val pontos = mutableListOf<JSONObject>().apply {
                        add(origem)
                        addAll(entregas)
                        casa?.let { add(it) }
                    }
                    val plano = otimizarPorDistanciaRodoviaria(pontos, entregas)
                    if (plano == null) {
                        erro("Não foi possível calcular pelas ruas. Verifique a internet e tente novamente.")
                        return@launch
                    }
                    val ordenados = JSONArray().apply { plano.entregasOrdenadas.forEach { put(it) } }
                    val info = JSONObject()
                        .put("fonte", "OSRM matriz de ruas")
                        .put("distancia_m", plano.distanciaMetros)
                        .put("duracao_s", plano.duracaoSegundos)
                        .put("distancia_original_m", if (plano.distanciaOriginalMetros.isFinite()) plano.distanciaOriginalMetros else JSONObject.NULL)
                        .put("comparacao", "ordem anterior")

                    withContext(Dispatchers.Main) {
                        binding.webView.evaluateJavascript(
                            "if (window.atualizarRotaPartindoDoGPS) window.atualizarRotaPartindoDoGPS($ordenados, $origem, ${casa ?: "null"}, $info, ${JSONObject.quote(requestId)});",
                            null
                        )
                    }
                } catch (e: Exception) {
                    Log.w("GPS", "Não foi possível recalcular rota salva a partir do GPS", e)
                    erro("Não foi possível recalcular a rota. Confira GPS, endereço de casa e conexão.")
                }
            }
        }

        @JavascriptInterface
        fun salvarRotaOffline(rotaId: String, jsonStr: String) {
            prefs.edit()
                .putString("offline_rota_$rotaId", jsonStr)
                .putString("offline_rota_ativa_id", rotaId)
                .apply()
        }

        @JavascriptInterface
        fun obterRotaOffline(rotaId: String): String {
            return prefs.getString("offline_rota_$rotaId", "") ?: ""
        }

        @JavascriptInterface
        fun obterUltimaRotaAtivaOffline(): String {
            val id = prefs.getString("offline_rota_ativa_id", "") ?: ""
            if (id.isBlank()) return ""
            return prefs.getString("offline_rota_$id", "") ?: ""
        }

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
        fun iniciarLeituraTelaAoVivo() {
            runOnUiThread {
                this@MainActivity.toggleCapturaAoVivo()
            }
        }

        @JavascriptInterface
        fun gerarRota() {
            runOnUiThread { this@MainActivity.processarRota() }
        }

        @JavascriptInterface
        fun abrirConfiguracoes() {
            runOnUiThread {
                showConfigDialog()
            }
        }

        @JavascriptInterface
        fun listarRotasNuvem() {
            firebaseListarRotas { success, msg, data ->
                runOnUiThread {
                    val safeData = if (success && data != null && data != "null" && data.isNotBlank()) data else "{}"
                    binding.webView.evaluateJavascript("if (window.receberListaRotasFirebase) window.receberListaRotasFirebase($safeData, $success, '${msg.replace("'", "\\'")}');", null)
                }
            }
        }

        @JavascriptInterface
        fun salvarRotaAuto(rotaId: String, rotaJsonStr: String) {
            firebaseSalvarRota(rotaId, rotaJsonStr) { success, msg ->
                runOnUiThread {
                    if (success) {
                        binding.webView.evaluateJavascript("if (window.onRotaSalvaAuto) window.onRotaSalvaAuto('$rotaId', true);", null)
                    } else {
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun excluirRotaNuvem(rotaId: String) {
            firebaseExcluirRota(rotaId) { _, msg ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    binding.webView.evaluateJavascript("showToast('${msg.replace("'", "\\'")}');", null)
                    listarRotasNuvem()
                }
            }
        }

        @JavascriptInterface
        fun atualizarStatusParadaNuvem(rotaId: String, paradaIndex: Int, finalizada: Boolean, finalizadaEm: String) {
            firebaseAtualizarStatusParada(rotaId, paradaIndex, finalizada, finalizadaEm)
        }

        @JavascriptInterface
        fun finalizarRotaNuvem(rotaId: String, status: String) {
            firebaseFinalizarRota(rotaId, status) { _, msg ->
                runOnUiThread {
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    binding.webView.evaluateJavascript("showToast('${msg.replace("'", "\\'")}');", null)
                    listarRotasNuvem()
                }
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
            val url = getFirebaseBaseUrl()
            val sala = getFirebaseSala()
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
        locationListener?.let { locationManager?.removeUpdates(it) }
        pararCapturaPorProjecao()
        LeitorEnviosService.onScreenshotCapabilityError = null
        ScreenCaptureService.onError = null
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
