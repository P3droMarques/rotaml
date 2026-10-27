package com.rota.ml

import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.max

/** Reagrupa texto e número da parada quando aparecem lado a lado na mesma linha. */
fun linhasOcrEmOrdem(texto: Text): List<String> {
    val linhas = texto.textBlocks.flatMap { it.lines }
        .filter { it.text.isNotBlank() && it.boundingBox != null }
        .sortedBy { it.boundingBox!!.centerY() }
    val grupos = mutableListOf<MutableList<Text.Line>>()
    for (linha in linhas) {
        val caixa = linha.boundingBox!!
        val grupo = grupos.lastOrNull()
        val anterior = grupo?.firstOrNull()?.boundingBox
        val mesmaLinha = anterior != null &&
            abs(caixa.centerY() - anterior.centerY()) <= max(caixa.height(), anterior.height()) * 0.55
        if (grupo != null && mesmaLinha) grupo.add(linha) else grupos.add(mutableListOf(linha))
    }
    return grupos.flatMap { grupo -> grupo.sortedBy { it.boundingBox!!.left }.map { it.text.trim() } }
}
