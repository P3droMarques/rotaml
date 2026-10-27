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
}
