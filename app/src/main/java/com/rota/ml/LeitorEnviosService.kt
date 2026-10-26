package com.rota.ml

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.LinkedHashSet

class LeitorEnviosService : AccessibilityService() {

    companion object {
        var isRunning = false
        var capturando = false
        val textosCapturados = LinkedHashSet<String>()
        val paradasDetectadas = mutableListOf<ParadaExtra>()
        var instance: LeitorEnviosService? = null
        var onUpdateListener: ((totalLinhas: Int, totalParadas: Int) -> Unit)? = null

        fun limpar() {
            textosCapturados.clear()
            paradasDetectadas.clear()
            onUpdateListener?.invoke(0, 0)
        }
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var txtOverlayStatus: TextView? = null
    private var btnOverlayConcluir: Button? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ultimoTamanhoParadas = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        instance = this

        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = 50
        serviceInfo = info

        Log.d("LeitorEnviosService", "Serviço de Acessibilidade conectado com sucesso!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!capturando || event == null) return

        // 1. Extrai texto dos registros do evento
        try {
            event.text.forEach { charSeq ->
                val s = charSeq?.toString()?.trim() ?: ""
                if (s.length >= 2) {
                    textosCapturados.add(s)
                }
            }
        } catch (_: Exception) {}

        // 2. Extrai nós da fonte do evento e da janela ativa
        val nodesToScan = mutableListOf<AccessibilityNodeInfo>()
        try {
            event.source?.let { nodesToScan.add(it) }
        } catch (_: Exception) {}

        try {
            rootInActiveWindow?.let { nodesToScan.add(it) }
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                windows?.forEach { w ->
                    w.root?.let { nodesToScan.add(it) }
                }
            } catch (_: Exception) {}
        }

        var novosItens = false
        val tamanhoAntes = textosCapturados.size

        for (node in nodesToScan) {
            try {
                extrairTextosRecursivo(node)
            } catch (_: Exception) {}
        }

        if (textosCapturados.size > tamanhoAntes) {
            novosItens = true
        }

        // Se encontrou novos textos, roda o analisador inteligente de paradas
        if (novosItens) {
            processarParadasEmTempoReal()
        }
    }

    private fun extrairTextosRecursivo(node: AccessibilityNodeInfo?) {
        if (node == null) return

        try {
            node.text?.let {
                val s = it.toString().trim()
                if (s.length >= 2) {
                    textosCapturados.add(s)
                }
            }

            node.contentDescription?.let {
                val s = it.toString().trim()
                if (s.length >= 2) {
                    textosCapturados.add(s)
                }
            }

            for (i in 0 until node.childCount) {
                extrairTextosRecursivo(node.getChild(i))
            }
        } catch (_: Exception) {}
    }

    private fun processarParadasEmTempoReal() {
        val lista = textosCapturados.toList()
        val paradas = EnviosExtraParser.parseLinhas(lista)

        synchronized(paradasDetectadas) {
            paradasDetectadas.clear()
            paradasDetectadas.addAll(paradas)
        }

        val totalParadas = paradas.size
        val totalLinhas = lista.size

        // Se detectou novas paradas, dá feedback tátil
        if (totalParadas > ultimoTamanhoParadas) {
            ultimoTamanhoParadas = totalParadas
            vibrarSuave()
        }

        mainHandler.post {
            atualizarOverlay(totalParadas)
            onUpdateListener?.invoke(totalLinhas, totalParadas)
        }
    }

    fun iniciarCapturaComOverlay() {
        capturando = true
        limpar()
        ultimoTamanhoParadas = 0
        mainHandler.post {
            mostrarOverlay()
        }
    }

    fun pararCapturaEFecharOverlay() {
        capturando = false
        mainHandler.post {
            removerOverlay()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun mostrarOverlay() {
        if (floatingView != null) return

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 120
            }

            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32f
                setColor(Color.parseColor("#F0161B22")) // Fundo escuro premium
                setStroke(2, Color.parseColor("#FFE600")) // Borda amarela Mercado Livre
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = bgDrawable
                setPadding(20, 14, 16, 14)
                gravity = Gravity.CENTER_VERTICAL
                elevation = 16f
            }

            val txtStatus = TextView(this).apply {
                text = "⚡ RotaML: 0 paradas"
                setTextColor(Color.parseColor("#F0F6FC"))
                textSize = 12f
                setPadding(0, 0, 12, 0)
            }
            txtOverlayStatus = txtStatus
            container.addView(txtStatus)

            // Botão OCR para capturar a tela por imagem diretamente
            val btnOcr = Button(this).apply {
                text = "📷 OCR"
                textSize = 11f
                setTextColor(Color.WHITE)
                val btnOcrBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 20f
                    setColor(Color.parseColor("#30363D"))
                    setStroke(1, Color.parseColor("#FFE600"))
                }
                background = btnOcrBg
                setPadding(14, 6, 14, 6)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 8
                }
                layoutParams = lp
                setOnClickListener {
                    executarOcrDaTelaAtual()
                }
            }
            container.addView(btnOcr)

            val btnConcluir = Button(this).apply {
                text = "Gerar Rota ✅"
                textSize = 11f
                setTextColor(Color.BLACK)
                val btnBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 20f
                    setColor(Color.parseColor("#FFE600"))
                }
                background = btnBg
                setPadding(18, 6, 18, 6)
                setOnClickListener {
                    concluirCapturaViaOverlay()
                }
            }
            btnOverlayConcluir = btnConcluir
            container.addView(btnConcluir)

            // Permite arrastar o overlay para qualquer canto da tela
            container.setOnTouchListener(object : View.OnTouchListener {
                var initialX = 0
                var initialY = 0
                var initialTouchX = 0f
                var initialTouchY = 0f
                var isMoving = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isMoving = false
                            return false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                                isMoving = true
                                params.x = initialX + dx
                                params.y = initialY + dy
                                windowManager?.updateViewLayout(floatingView, params)
                                return true
                            }
                        }
                    }
                    return false
                }
            })

            floatingView = container
            windowManager?.addView(floatingView, params)

        } catch (e: Exception) {
            Log.e("LeitorEnviosService", "Não foi possível exibir overlay flutuante: ${e.message}")
        }
    }

    private fun atualizarOverlay(totalParadas: Int) {
        txtOverlayStatus?.let { tv ->
            if (totalParadas == 0) {
                tv.text = "⚡ RotaML: Role ou toque em OCR"
                tv.setTextColor(Color.parseColor("#FFE600"))
            } else {
                tv.text = "⚡ RotaML: $totalParadas paradas lidas!"
                tv.setTextColor(Color.parseColor("#10B981")) // Verde sucesso
            }
        }
    }

    // Executa OCR da tela através da API nativa de captura de tela de acessibilidade do Android 11+
    private fun executarOcrDaTelaAtual() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            txtOverlayStatus?.text = "⚡ Lendo texto da tela..."
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hwBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                        val copyBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, true)
                        hwBuffer.close()

                        if (copyBitmap != null) {
                            processarBitmapComMlKit(copyBitmap)
                        } else {
                            txtOverlayStatus?.text = "⚡ Erro ao ler tela"
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        txtOverlayStatus?.text = "⚡ Falha na captura ($errorCode)"
                    }
                })
            } catch (e: Exception) {
                txtOverlayStatus?.text = "⚡ Erro OCR: ${e.message}"
            }
        } else {
            txtOverlayStatus?.text = "⚡ Use a opção 🖼️ Ler Foto no app"
        }
    }

    private fun processarBitmapComMlKit(bitmap: Bitmap) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val s = line.text.trim()
                            if (s.length >= 2) {
                                textosCapturados.add(s)
                            }
                        }
                    }
                    processarParadasEmTempoReal()
                }
                .addOnFailureListener { e ->
                    txtOverlayStatus?.text = "⚡ Erro no OCR: ${e.message}"
                }
        } catch (e: Exception) {
            Log.e("LeitorEnviosService", "Erro processando OCR", e)
        }
    }

    private fun concluirCapturaViaOverlay() {
        capturando = false
        removerOverlay()

        // Abre o app RotaML automaticamente já processando
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("EXTRA_PROCESSAR_AUTOMATICO", true)
        }
        startActivity(intent)
    }

    private fun removerOverlay() {
        try {
            floatingView?.let {
                windowManager?.removeView(it)
                floatingView = null
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun vibrarSuave() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(40)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        isRunning = false
        capturando = false
        instance = null
        removerOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {
        capturando = false
        removerOverlay()
    }
}
