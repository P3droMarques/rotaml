package com.rota.ml

import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

data class ParadaExtra(
    var numero: Int = 0,
    var endereco: String = "",
    var complemento: String = "",
    var bairro: String = "",
    var cidade: String = "Campinas",
    var cep: String = "",
    var unidades: Int = 0,
    var etiqueta: String = "",
    var etiquetaIncompleta: Boolean = false,
    var janela: String = "",
    var destinatario: String = ""
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("parada_num", if (numero > 0) numero else JSONObject.NULL)
        obj.put("parada_original", if (numero > 0) numero else JSONObject.NULL)
        obj.put("destinatario", if (destinatario.isNotBlank()) destinatario else (complemento.ifBlank { if (numero > 0) "Parada $numero" else "Parada sem número identificado" }))
        obj.put("endereco", endereco)
        obj.put("complemento", complemento)
        obj.put("bairro", bairro)
        obj.put("cidade", cidade.ifBlank { "Campinas" })
        obj.put("cep", cep)
        obj.put("janela", janela)

        val pacotesArray = JSONArray()
        val qtd = if (unidades > 0) unidades else 0
        obj.put("quantidade_unidades", if (unidades > 0) unidades else JSONObject.NULL)
        val cleanTag = etiqueta.replace("#", "").trim().uppercase()
        val numeroEtiqueta = Regex("^[A-Z][A-Z0-9]*_(\\d+)$").matchEntire(cleanTag)?.groupValues?.get(1)?.toIntOrNull()
        val estadoEtiqueta = when {
            cleanTag.isBlank() -> if (etiquetaIncompleta) "incompleta" else "ausente"
            etiquetaIncompleta -> "incompleta"
            numeroEtiqueta == null -> "incompleta"
            numero > 0 && numeroEtiqueta != numero -> "conflitante"
            else -> "lida"
        }
        obj.put("etiqueta_base", cleanTag)
        obj.put("etiqueta_status", estadoEtiqueta)

        for (i in 1..qtd) {
            val pkg = JSONObject()
            pkg.put("id", "slot_${endereco.lowercase().trim().hashCode()}_${numero}_${i}")
            pkg.put("id_identificado", false)
            pkg.put("final", "")
            pkg.put("etiqueta", cleanTag)
            pkg.put("conferido", false)
            pacotesArray.put(pkg)
        }
        obj.put("pacotes", pacotesArray)
        return obj
    }
}

object EnviosExtraParser {

    fun mesclarLeituras(destino: MutableList<ParadaExtra>, novas: List<ParadaExtra>) {
        for (nova in novas) {
            val endereco = nova.endereco.trim().lowercase()
            val existente = destino.firstOrNull { antiga ->
                antiga.endereco.trim().lowercase() == endereco &&
                    (antiga.numero <= 0 || nova.numero <= 0 || antiga.numero == nova.numero)
            }
            if (existente == null) {
                destino.add(nova.copy())
                continue
            }
            if (existente.numero <= 0 && nova.numero > 0) existente.numero = nova.numero
            if (existente.cep.isBlank()) existente.cep = nova.cep
            if (existente.bairro.isBlank()) existente.bairro = nova.bairro
            if (existente.complemento.isBlank()) existente.complemento = nova.complemento
            if (existente.janela.isBlank()) existente.janela = nova.janela
            if (existente.destinatario.isBlank()) existente.destinatario = nova.destinatario
            if (nova.unidades > existente.unidades) existente.unidades = nova.unidades
            if (existente.etiqueta.isBlank() || (existente.etiquetaIncompleta && !nova.etiquetaIncompleta && nova.etiqueta.isNotBlank())) {
                existente.etiqueta = nova.etiqueta
                existente.etiquetaIncompleta = nova.etiquetaIncompleta
            } else if (nova.etiqueta.isNotBlank() && !nova.etiquetaIncompleta && existente.etiqueta != nova.etiqueta) {
                // Leituras diferentes precisam de conferência, não de uma escolha arbitrária.
                existente.etiquetaIncompleta = true
            }
        }
    }

    private val ENDERECO_PATTERN = Pattern.compile(
        "^(Rua|Avenida|Av\\.?|R\\.?|Alameda|Al\\.?|Travessa|Tv\\.?|Praça|Pça\\.?|Rodovia|Rod\\.?|Estrada)\\s+[A-Za-zÀ-ÖØ-öø-ÿ0-9\\s\\.\\,\\'\\-]+?\\b(\\d+)\\b",
        Pattern.CASE_INSENSITIVE
    )

    private val CEP_PATTERN = Pattern.compile("CEP\\s*:?\\s*(\\d{5}-?\\d{3}|\\d{8})", Pattern.CASE_INSENSITIVE)
    private val CEP_RAW_PATTERN = Pattern.compile("\\b(\\d{5}-?\\d{3}|\\d{8})\\b")

    private val ETIQUETA_PATTERN = Pattern.compile("#*\\s*([A-Z][A-Z0-9]{1,30})\\s*_\\s*(\\d{1,3})(?!\\d)", Pattern.CASE_INSENSITIVE)

    private val UNIDADES_PATTERN = Pattern.compile("Entrega\\s+(\\d+)\\s+unidades?", Pattern.CASE_INSENSITIVE)
    private val JANELA_PATTERN = Pattern.compile("(\\d{1,2}:\\d{2}\\s*h?\\s*a\\s*\\d{1,2}:\\d{2}\\s*h?)", Pattern.CASE_INSENSITIVE)
    private val NUMERO_PARADA_PATTERN = Pattern.compile("^([1-9]\\d{0,2})$")
    private val NUMERO_ENDERECO_SEPARADO_PATTERN = Pattern.compile("^(\\d{2,5})(?:\\s*[✓✔✿✾❀].*)?$")

    fun parseLinhas(linhas: List<String>): List<ParadaExtra> {
        val paradasMap = LinkedHashMap<String, ParadaExtra>()
        var currentStop: ParadaExtra? = null
        var lastParadaNum = 0
        var lastJanela = ""
        var enderecoPendente: String? = null
        var aguardandoEtiqueta = false

        fun salvarCurrent() {
            currentStop?.let { p ->
                if (p.endereco.isNotBlank()) {
                    val key = p.endereco.lowercase().trim() + "|" + p.numero
                    val existing = paradasMap[key]
                    if (existing == null) {
                        paradasMap[key] = p
                    } else {
                        // Mescla informações mais ricas
                        if (existing.cep.isBlank() && p.cep.isNotBlank()) existing.cep = p.cep
                        if (existing.etiqueta.isBlank() && p.etiqueta.isNotBlank()) existing.etiqueta = p.etiqueta
                        if (existing.unidades <= 1 && p.unidades > 1) existing.unidades = p.unidades
                        if (existing.complemento.isBlank() && p.complemento.isNotBlank()) existing.complemento = p.complemento
                        if (existing.numero <= 0 && p.numero > 0) existing.numero = p.numero
                    }
                }
            }
            currentStop = null
            aguardandoEtiqueta = false
        }

        for (rawLinha in linhas) {
            val linha = rawLinha.trim()
            if (linha.isBlank()) continue

            // Em telas estreitas o Envios Extra/OCR pode quebrar o número do
            // endereço em uma linha separada (ex.: "Avenida ..." + "2303").
            // Guarda o logradouro até que o número apareça, evitando perder a parada.
            if (enderecoPendente != null) {
                val prefixo = enderecoPendente
                val mNumeroEndereco = NUMERO_ENDERECO_SEPARADO_PATTERN.matcher(linha)
                if (mNumeroEndereco.matches()) {
                    salvarCurrent()
                    currentStop = ParadaExtra(
                        numero = lastParadaNum,
                        endereco = "$prefixo ${mNumeroEndereco.group(1)}",
                        janela = lastJanela
                    )
                    enderecoPendente = null
                    lastParadaNum = 0
                    continue
                }
                enderecoPendente = null
            }

            // Ignora textos de cabeçalho e navegação do app Envios Extra
            if (linha.contains("Seu percurso tem", true) ||
                linha.contains("unidades no veículo", true) ||
                linha.contains("Próximas paradas", true) ||
                linha.contains("Paradas finalizadas", true) ||
                linha.contains("Você ainda não tem", true) ||
                linha.contains("Estou chegando", true) ||
                linha.startsWith("ÁREA ", true) ||
                linha.equals("Início", true) ||
                linha.equals("Disponíveis", true) ||
                linha.equals("Agendados", true) ||
                linha.equals("Mais", true)
            ) {
                continue
            }

            // Verifica número da parada (badge verde 1, 2, 3...)
            val mNum = NUMERO_PARADA_PATTERN.matcher(linha)
            if (mNum.matches()) {
                val num = mNum.group(1)?.toIntOrNull() ?: 0
                if (num in 1..200) {
                    lastParadaNum = num
                    continue
                }
            }

            // Verifica janela de entrega (ex: 12:15h a 15:00h)
            val mJanela = JANELA_PATTERN.matcher(linha)
            if (mJanela.find()) {
                lastJanela = mJanela.group(1) ?: ""
                currentStop?.let {
                    if (it.janela.isBlank()) it.janela = lastJanela
                }
                continue
            }

            // Verifica linha de endereço (Rua / Av / etc.)
            val mEnd = ENDERECO_PATTERN.matcher(linha)
            if (mEnd.find() || isLinhaDeEndereco(linha) || isPrefixoDeEndereco(linha)) {
                salvarCurrent()
                val enderecoLimpo = limpaEndereco(linha)
                if (temNumeroDeEndereco(enderecoLimpo)) {
                    currentStop = ParadaExtra(
                        numero = lastParadaNum,
                        endereco = enderecoLimpo,
                        janela = lastJanela
                    )
                    lastParadaNum = 0
                } else {
                    enderecoPendente = enderecoLimpo
                }
                continue
            }

            // Se ainda não temos uma parada atual, pula linhas desconexas
            val parada = currentStop ?: continue

            // Verifica Unidades isoladas
            val mUn = UNIDADES_PATTERN.matcher(linha)
            if (mUn.find()) {
                val un = mUn.group(1)?.toIntOrNull() ?: 1
                parada.unidades = un
            }

            val contemEtiqueta = linha.contains("ETIQUETA", true)
            val mEtq = ETIQUETA_PATTERN.matcher(linha)
            if ((contemEtiqueta || aguardandoEtiqueta || linha.trimStart().startsWith("#")) && mEtq.find()) {
                val prefixo = mEtq.group(1).orEmpty().uppercase()
                val sufixo = mEtq.group(2).orEmpty()
                parada.etiqueta = "${prefixo}_${sufixo}"
                // Uma reticência após um sufixo que não corresponde ao número
                // visível pode esconder outros dígitos. Nunca completamos por suposição.
                val restante = linha.substring(mEtq.end()).trimStart()
                parada.etiquetaIncompleta = restante.startsWith("...") && (parada.numero <= 0 || sufixo.toIntOrNull() != parada.numero)
                if (parada.numero <= 0 && !restante.startsWith("...")) parada.numero = sufixo.toIntOrNull() ?: 0
                aguardandoEtiqueta = false
                continue
            }
            if (contemEtiqueta) {
                aguardandoEtiqueta = true
                parada.etiquetaIncompleta = true
                continue
            }

            // Verifica ID numérico de pacote (ex: 48314806075)
            // Códigos numéricos lidos em outros campos não são a etiqueta da saca.

            // Verifica CEP e detalhes (Ex: "Loja Academia Aquarius, CEP 13076011" ou "Taquaral, CEP 13076150")
            val mCep = CEP_PATTERN.matcher(linha)
            if (mCep.find()) {
                val cepEncontrado = mCep.group(1)?.replace("-", "") ?: ""
                parada.cep = cepEncontrado

                // O que veio antes de ", CEP" é complemento ou bairro
                val partes = linha.split(Regex("(?i),?\\s*CEP"), 2)
                val prefixo = partes[0].trim().trimEnd(',')
                if (prefixo.isNotBlank()) {
                    if (isBairroConhecido(prefixo)) {
                        parada.bairro = prefixo
                    } else {
                        parada.complemento = if (parada.complemento.isBlank()) prefixo else "${parada.complemento} - $prefixo"
                    }
                }
                continue
            }

            val mCepRaw = CEP_RAW_PATTERN.matcher(linha)
            if (mCepRaw.find() && parada.cep.isBlank()) {
                parada.cep = mCepRaw.group(1)?.replace("-", "") ?: ""
                continue
            }

            // Outros detalhes como "Com horário comercial" ou nome de condomínio
            if (linha.contains("comercial", true) || linha.startsWith("Condomínio", true) || linha.startsWith("Prédio", true) || linha.startsWith("Loja", true)) {
                parada.complemento = if (parada.complemento.isBlank()) linha else "${parada.complemento} ($linha)"
            } else if (parada.complemento.isBlank() && linha.length in 4..40 && !linha.contains("Mercado", true)) {
                parada.complemento = linha
            }
        }

        salvarCurrent()

        val resultado = paradasMap.values.toList()
        return resultado
    }

    private fun isLinhaDeEndereco(s: String): Boolean {
        return isPrefixoDeEndereco(s) && temNumeroDeEndereco(s)
    }

    private fun isPrefixoDeEndereco(s: String): Boolean {
        val lower = s.lowercase()
        val prefixes = listOf("rua ", "av. ", "av ", "avenida ", "r. ", "alameda ", "al. ", "travessa ", "tv. ", "praça ", "pça ", "rodovia ", "estrada ")
        return prefixes.any { lower.startsWith(it) }
    }

    private fun temNumeroDeEndereco(s: String): Boolean {
        return Pattern.compile("\\b\\d{1,5}\\b").matcher(s).find()
    }

    private fun limpaEndereco(s: String): String {
        return s.replace(Regex("[✓✔]"), "")
            .replace("Estou chegando", "", ignoreCase = true)
            .trim()
    }

    private fun limpaEtiqueta(s: String): String {
        return s.replace("#", "")
            .replace(Regex("\\.+$"), "") // remove reticências como "..."
            .trim()
    }

    private fun isBairroConhecido(s: String): Boolean {
        val bairros = listOf("taquaral", "guanabara", "cambuí", "cambui", "vila iza", "centro", "botafogo", "bosque", "parque industrial", "nova campinas", "ponte preta", "vila nova", "swift", "jardim brasil", "jardim ipiranga")
        return bairros.any { s.lowercase().contains(it) }
    }
}
