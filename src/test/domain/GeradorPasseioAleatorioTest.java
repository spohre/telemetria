package domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Simulação de valores — sem threads. */
class GeradorPasseioAleatorioTest {

    private static final RegraDeAnomalia TEMPERATURA = new RegraDeAnomalia(TipoSensor.TEMPERATURA, 0, 80);
    private static final RegraDeAnomalia PRESSAO = new RegraDeAnomalia(TipoSensor.PRESSAO, 1, 8);
    private static final int AMOSTRAS = 20_000;

    private static boolean foraDaFaixa(RegraDeAnomalia r, double v) {
        return v < r.minimo() || v > r.maximo();
    }

    @Test
    void mesmaSementeGeraMesmaSequencia() {
        GeradorDeValores a = new GeradorPasseioAleatorio(TEMPERATURA, new Random(7));
        GeradorDeValores b = new GeradorPasseioAleatorio(TEMPERATURA, new Random(7));
        for (int i = 0; i < 1000; i++) {
            assertEquals(a.proximoValor(), b.proximoValor(), "amostra " + i);
        }
    }

    @Test
    void semPicosNuncaSaiDaFaixa() {
        GeradorDeValores g = new GeradorPasseioAleatorio(PRESSAO, new Random(1), 0.0);
        for (int i = 0; i < AMOSTRAS; i++) {
            double v = g.proximoValor();
            assertFalse(foraDaFaixa(PRESSAO, v), "valor " + v + " fora de 1–8");
        }
    }

    @Test
    void cercaDeCincoPorCentoDasLeiturasSaoPicosForaDaFaixa() {
        GeradorDeValores g = new GeradorPasseioAleatorio(TEMPERATURA, new Random(3));
        int fora = 0;
        for (int i = 0; i < AMOSTRAS; i++) {
            if (foraDaFaixa(TEMPERATURA, g.proximoValor())) {
                fora++;
            }
        }
        double taxa = (double) fora / AMOSTRAS;
        assertTrue(taxa > 0.04 && taxa < 0.06, "taxa de picos = " + taxa);
    }

    @Test
    void sempreComPicosTodosOsValoresSaemDaFaixaEmAmbosOsLados() {
        GeradorDeValores g = new GeradorPasseioAleatorio(PRESSAO, new Random(5), 1.0);
        boolean acima = false;
        boolean abaixo = false;
        for (int i = 0; i < 2000; i++) {
            double v = g.proximoValor();
            assertTrue(foraDaFaixa(PRESSAO, v), "pico " + v + " deveria estar fora de 1–8");
            acima |= v > PRESSAO.maximo();
            abaixo |= v < PRESSAO.minimo();
        }
        assertTrue(acima && abaixo);
    }

    @Test
    void valoresTemNoMaximoUmaCasaDecimal() {
        GeradorDeValores g = new GeradorPasseioAleatorio(TEMPERATURA, new Random(11));
        for (int i = 0; i < 1000; i++) {
            double v = g.proximoValor();
            assertEquals(Math.round(v * 10) / 10.0, v, 1e-9);
        }
    }

    @Test
    void rejeitaProbabilidadeInvalida() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeradorPasseioAleatorio(TEMPERATURA, new Random(), 1.5));
    }
}
