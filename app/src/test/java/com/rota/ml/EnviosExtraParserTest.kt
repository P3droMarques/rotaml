package com.rota.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnviosExtraParserTest {

    @Test
    fun parsesEnviosScreenWhenAddressNumberIsOnNextLine() {
        val lines = listOf(
            "Seu percurso tem 11 paradas",
            "62 unidades no veículo",
            "Próximas paradas",
            "1",
            "Rua Dona Maria Umbelina Couto",
            "189",
            "Loja Academia Aquarius, CEP 13076011",
            "Entrega 1 unidade | ETIQUETA ##NX215_1...",
            "Estou chegando",
            "2",
            "Rua Tiradentes 1025",
            "Condomínio Condomínio Residencial Parque Itaici, CEP 13023191",
            "Entrega 8 unidades | ETIQUETA ##NX215_2...",
            "10",
            "Rua Paulo José Vilac 128",
            "Vila Iza, CEP 13076690",
            "Entrega 1 unidade | ETIQUETA ##NX215_10...",
            "11",
            "Avenida José de Sousa Campos",
            "2303",
            "Prédio Sesmarias, CEP 13025320",
            "Entrega 1 unidade | ETIQUETA ##NX215_11..."
        )

        val result = EnviosExtraParser.parseLinhas(lines)

        assertEquals(4, result.size)
        assertEquals("Rua Dona Maria Umbelina Couto 189", result[0].endereco)
        assertEquals("13076011", result[0].cep)
        assertEquals(8, result[1].unidades)
        assertEquals(10, result[2].numero)
        assertEquals("Avenida José de Sousa Campos 2303", result[3].endereco)
        assertTrue(result.all { it.etiqueta.isNotBlank() })
        assertEquals(listOf(1, 2, 10, 11), result.map { it.numero })
        assertEquals(listOf("NX215_1", "NX215_2", "NX215_10", "NX215_11"), result.map { it.etiqueta })
    }

    @Test
    fun preservesWrappedLabelsAndAlternativeSackPrefixes() {
        val lines = listOf(
            "1", "Rua Tiradentes 1025", "Entrega 8 unidades | ETIQUETA",
            "##AB742_1...", "Estou chegando", "10", "Rua Paulo José Vilac 128",
            "Entrega 1 unidade | ETIQUETA ##AB742_10..."
        )
        val result = EnviosExtraParser.parseLinhas(lines)
        assertEquals(listOf(1, 10), result.map { it.numero })
        assertEquals(listOf("AB742_1", "AB742_10"), result.map { it.etiqueta })
        assertEquals(8, result[0].unidades)
        assertTrue(result.none { it.etiquetaIncompleta })
    }

    @Test
    fun marksTruncatedAndConflictingLabelsWithoutInventingNumbers() {
        val result = EnviosExtraParser.parseLinhas(listOf(
            "10", "Rua Paulo José Vilac 128", "Entrega 1 unidade | ETIQUETA ##NX215_1...",
            "Rua São Salvador 220", "Entrega 2 unidades | ETIQUETA ##NX215_7..."
        ))
        assertEquals(10, result[0].numero)
        assertEquals("NX215_1", result[0].etiqueta)
        assertTrue(result[0].etiquetaIncompleta)
        assertEquals(0, result[1].numero)
        assertTrue(result[1].etiquetaIncompleta)
    }

    @Test
    fun mergesOverlappingScreensWithoutMixingStopsOrDuplicates() {
        val first = EnviosExtraParser.parseLinhas(listOf(
            "1", "Rua Tiradentes 1025", "Entrega 8 unidades | ETIQUETA ##NX215_1...",
            "2", "Rua São Salvador 220", "Entrega 7 unidades | ETIQUETA ##NX215_2..."
        ))
        val second = EnviosExtraParser.parseLinhas(listOf(
            "2", "Rua São Salvador 220", "Entrega 7 unidades | ETIQUETA ##NX215_2...",
            "10", "Rua Paulo José Vilac 128", "Entrega 1 unidade | ETIQUETA ##NX215_10..."
        ))
        val merged = mutableListOf<ParadaExtra>()
        EnviosExtraParser.mesclarLeituras(merged, first)
        EnviosExtraParser.mesclarLeituras(merged, second)
        assertEquals(listOf(1, 2, 10), merged.map { it.numero })
        assertEquals(listOf("NX215_1", "NX215_2", "NX215_10"), merged.map { it.etiqueta })
        assertEquals(listOf(8, 7, 1), merged.map { it.unidades })
    }

    @Test
    fun flagsDifferentLabelsForSameStopInsteadOfSilentlyReplacingThem() {
        val merged = mutableListOf(ParadaExtra(numero = 1, endereco = "Rua A 10", etiqueta = "NX215_1"))
        EnviosExtraParser.mesclarLeituras(merged, listOf(ParadaExtra(numero = 1, endereco = "Rua A 10", etiqueta = "AB742_1")))
        assertEquals("NX215_1", merged.single().etiqueta)
        assertTrue(merged.single().etiquetaIncompleta)
    }

    @Test
    fun doesNotInventLabelWhenMissing() {
        val result = EnviosExtraParser.parseLinhas(listOf("3", "Rua Tiradentes 1025", "Entrega 2 unidades | ETIQUETA"))
        assertEquals(3, result.single().numero)
        assertEquals("", result.single().etiqueta)
        assertTrue(result.single().etiquetaIncompleta)
    }

    @Test
    fun repeatedPhotosProduceNineVisibleStopsNotElevenInTheHeader() {
        val first = listOf(
            "Seu percurso tem 11 paradas", "62 unidades no veículo", "Próximas paradas",
            "1", "Rua Dona Maria Umbelina Couto", "189", "Loja Academia Aquarius, CEP 13076011", "Entrega 1 unidade | ETIQUETA ##NX215_1...",
            "2", "Rua Tiradentes 1025", "Condomínio Residencial Parque Itaici, CEP 13023191", "Entrega 8 unidades | ETIQUETA", "##NX215_2...",
            "4", "Rua Lotário Novaes 325", "Taquaral, CEP 13076150", "Entrega 2 unidades | ETIQUETA", "##NX215_4..."
        )
        val second = listOf(
            "Seu percurso tem 11 paradas", "10", "Rua Paulo José Vilac 128", "Vila Iza, CEP 13076690", "Entrega 1 unidade | ETIQUETA", "##NX215_10...",
            "3", "Rua Alberto Cerqueira Lima 29", "Condomínio Vox Residencial, CEP 13076010", "Entrega 17 unidades | ETIQUETA", "##NX215_3...",
            "ÁREA 1 | 12:15 a 15:00 hs", "5", "Rua Doutor Mário Natividade 1080", "Condomínio Art Vitta, CEP 13076112", "Entrega 9 unidades | ETIQUETA", "##NX215_5..."
        )
        val third = listOf(
            "8", "Rua Doutor Plínio Barreto 107", "Prédio Aquarius I, CEP 13076590", "Entrega 5 unidades | ETIQUETA", "##NX215_8...",
            "7", "Rua São Salvador 220", "Condomínio Horizon Residence Premium, CEP 13076904", "Entrega 7 unidades | ETIQUETA", "##NX215_7...",
            "11", "Avenida José de Sousa Campos", "2303", "Prédio Sesmarias, CEP 13025320", "Entrega 1 unidade | ETIQUETA", "##NX215_11..."
        )
        val merged = mutableListOf<ParadaExtra>()
        listOf(first, second, third, first).forEach { EnviosExtraParser.mesclarLeituras(merged, EnviosExtraParser.parseLinhas(it)) }
        assertEquals(9, merged.size)
        assertEquals(setOf(1, 2, 3, 4, 5, 7, 8, 10, 11), merged.map { it.numero }.toSet())
        assertEquals(51, merged.sumOf { it.unidades })
        assertTrue(merged.all { it.etiqueta == "NX215_${it.numero}" })
    }

    @Test
    fun repeatedOcrWithPunctuationAccentsOrTrustedLabelMerges() {
        val stops = mutableListOf(ParadaExtra(numero = 2, endereco = "Rua Tiradentes 1025", etiqueta = "NX215_2", unidades = 8))
        EnviosExtraParser.mesclarLeituras(stops, listOf(ParadaExtra(numero = 2, endereco = "R. Tiradentes, 1025", etiqueta = "NX215_2")))
        assertEquals(1, stops.size)
        assertEquals("", stops.single().enderecoAlternativo)
        EnviosExtraParser.mesclarLeituras(stops, listOf(ParadaExtra(numero = 2, endereco = "Rua Tiradentees 1025", etiqueta = "NX215_2")))
        assertEquals(1, stops.size)
        assertEquals("Rua Tiradentees 1025", stops.single().enderecoAlternativo)
        assertEquals(8, stops.single().unidades)
    }

    @Test
    fun doesNotMergeDifferentStopNumbersEvenAtTheSameAddress() {
        val stops = mutableListOf(ParadaExtra(numero = 1, endereco = "Rua A 10", etiqueta = "NX215_1"))
        EnviosExtraParser.mesclarLeituras(stops, listOf(ParadaExtra(numero = 10, endereco = "Rua A 10", etiqueta = "NX215_10")))
        assertEquals(2, stops.size)
    }
}
