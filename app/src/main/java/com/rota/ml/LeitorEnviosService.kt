package com.rota.ml

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.LinkedHashSet

class LeitorEnviosService : AccessibilityService() {

    companion object {
        var capturando = false
        val textosCapturados = LinkedHashSet<String>()
        
        fun limpar() {
            textosCapturados.clear()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!capturando) return

        val rootNode = rootInActiveWindow ?: return
        extrairTextos(rootNode)
    }

    private fun extrairTextos(node: AccessibilityNodeInfo?) {
        if (node == null) return

        node.text?.let {
            val s = it.toString().trim()
            if (s.length > 5) { // Evita lixo muito curto
                textosCapturados.add(s)
            }
        }

        for (i in 0 until node.childCount) {
            extrairTextos(node.getChild(i))
        }
    }

    override fun onInterrupt() {}
}
