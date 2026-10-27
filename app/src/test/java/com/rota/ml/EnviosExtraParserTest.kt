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
    }
}
