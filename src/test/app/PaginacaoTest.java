package app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Paginação dos cartões da aba Monitoramento (4 por página) — lógica pura, sem tela. */
class PaginacaoTest {

    @ParameterizedTest(name = "{0} sensores → {1} página(s), necessária = {2}")
    @CsvSource({"0, 1, false", "1, 1, false", "4, 1, false", "5, 2, true", "8, 2, true", "9, 3, true"})
    void numeroDePaginas(int total, int paginas, boolean necessaria) {
        Paginacao p = new Paginacao(total, 4);
        assertEquals(paginas, p.paginas());
        assertEquals(necessaria, p.necessaria());
    }

    @Test
    void intervaloDeIndicesDeCadaPagina() {
        Paginacao p = new Paginacao(9, 4);
        assertEquals(0, p.inicio(0));
        assertEquals(4, p.fim(0));
        assertEquals(4, p.inicio(1));
        assertEquals(8, p.fim(1));
        assertEquals(8, p.inicio(2));
        assertEquals(9, p.fim(2), "última página tem só o que sobra");
    }

    @Test
    void semSensoresAPaginaUnicaEstaVazia() {
        Paginacao p = new Paginacao(0, 4);
        assertEquals(0, p.inicio(0));
        assertEquals(0, p.fim(0));
    }

    @Test
    void paginaForaDoIntervaloEhAjustada() {
        Paginacao p = new Paginacao(9, 4);
        assertEquals(0, p.ajustar(-1));
        assertEquals(2, p.ajustar(7));
        assertEquals(1, p.ajustar(1));
        // a quantidade diminuiu (ex.: de 9 para 5): estava na página 3, vai para a última que existe
        assertEquals(1, new Paginacao(5, 4).ajustar(2));
        // aumentou: a página atual continua valendo
        assertEquals(1, new Paginacao(13, 4).ajustar(1));
    }

    @Test
    void descricaoParaATela() {
        assertEquals("Página 2 de 3 · sensores 5–8 de 10", new Paginacao(10, 4).descricao(1));
        assertEquals("Página 3 de 3 · sensores 9–10 de 10", new Paginacao(10, 4).descricao(2));
        assertEquals("Página 2 de 2 · sensor 5 de 5", new Paginacao(5, 4).descricao(1));
    }

    @Test
    void validaParametros() {
        assertThrows(IllegalArgumentException.class, () -> new Paginacao(-1, 4));
        assertThrows(IllegalArgumentException.class, () -> new Paginacao(3, 0));
    }
}
