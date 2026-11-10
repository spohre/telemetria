package domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Parâmetros da simulação de episódios — validação e valores padrão. */
class ParametrosDeEpisodiosTest {

    private static final ParametrosDeEpisodios PADRAO = ParametrosDeEpisodios.padrao();

    /** Padrão com outras durações de fase. */
    private static ParametrosDeEpisodios fases(int aqMin, int aqMax, int crMin, int crMax, int esMin, int esMax) {
        return new ParametrosDeEpisodios(PADRAO.mediaEntreEpisodios(), aqMin, aqMax, crMin, crMax, esMin, esMax,
                PADRAO.probabilidadeDeCrise(), PADRAO.pesosSeveridade(), PADRAO.probabilidadeAcima());
    }

    private static ParametrosDeEpisodios com(double prob, Map<Severidade, Double> pesos, Map<TipoSensor, Double> acima) {
        return new ParametrosDeEpisodios(PADRAO.mediaEntreEpisodios(), PADRAO.aquecimentoMinimo(),
                PADRAO.aquecimentoMaximo(), PADRAO.duracaoMinima(), PADRAO.duracaoMaxima(), PADRAO.esfriamentoMinimo(),
                PADRAO.esfriamentoMaximo(), prob, pesos, acima);
    }

    @Test
    void padraoTemOsValoresCombinados() {
        assertEquals(50.0, PADRAO.mediaEntreEpisodios());
        assertEquals(6, PADRAO.aquecimentoMinimo());
        assertEquals(12, PADRAO.aquecimentoMaximo());
        assertEquals(6, PADRAO.duracaoMinima());
        assertEquals(14, PADRAO.duracaoMaxima());
        assertEquals(6, PADRAO.esfriamentoMinimo());
        assertEquals(12, PADRAO.esfriamentoMaximo());
        assertEquals(0.8, PADRAO.probabilidadeDeCrise());
        assertEquals(Map.of(Severidade.BAIXA, 40.0, Severidade.MEDIA, 30.0,
                Severidade.ALTA, 20.0, Severidade.CRITICA, 10.0), PADRAO.pesosSeveridade());
        assertEquals(1.0, PADRAO.probabilidadeAcima().get(TipoSensor.VELOCIDADE), "velocidade só acima");
        assertEquals(0.8, PADRAO.probabilidadeAcima().get(TipoSensor.TEMPERATURA));
        assertEquals(0.5, PADRAO.probabilidadeAcima().get(TipoSensor.PRESSAO));
        assertEquals(0.5, PADRAO.probabilidadeAcima().get(TipoSensor.UMIDADE));
    }

    @Test
    void rejeitaMediaEntreEpisodiosNaoPositivaOuNaoFinita() {
        for (double media : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> PADRAO.comMediaEntreEpisodios(media), "média " + media);
        }
    }

    @Test
    void rejeitaDuracaoDaCriseMenorQueDoisOuMinimaMaiorQueAMaxima() {
        assertThrows(IllegalArgumentException.class, () -> fases(4, 10, 1, 12, 4, 10), "crise precisa subir e descer");
        assertThrows(IllegalArgumentException.class, () -> fases(4, 10, 13, 12, 4, 10));
        assertDoesNotThrow(() -> fases(4, 10, 5, 5, 4, 10), "duração fixa é permitida");
    }

    @Test
    void rejeitaAquecimentoOuEsfriamentoNegativosOuInvertidos() {
        assertThrows(IllegalArgumentException.class, () -> fases(-1, 10, 4, 12, 4, 10));
        assertThrows(IllegalArgumentException.class, () -> fases(11, 10, 4, 12, 4, 10));
        assertThrows(IllegalArgumentException.class, () -> fases(4, 10, 4, 12, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> fases(4, 10, 4, 12, 11, 10));
        assertDoesNotThrow(() -> fases(0, 0, 4, 12, 0, 0), "sem rampas é permitido");
    }

    @Test
    void rejeitaProbabilidadeDeCriseForaDeZeroAUm() {
        var pesos = PADRAO.pesosSeveridade();
        var acima = PADRAO.probabilidadeAcima();
        assertThrows(IllegalArgumentException.class, () -> com(0.0, pesos, acima), "0 = nunca haveria alerta");
        assertThrows(IllegalArgumentException.class, () -> com(1.01, pesos, acima));
        assertDoesNotThrow(() -> com(1.0, pesos, acima));
    }

    @Test
    void rejeitaPesosNegativosTodosZeroOuSeveridadeFaltando() {
        var acima = PADRAO.probabilidadeAcima();
        Map<Severidade, Double> negativo = new EnumMap<>(PADRAO.pesosSeveridade());
        negativo.put(Severidade.ALTA, -1.0);
        Map<Severidade, Double> zeros = new EnumMap<>(Map.of(Severidade.BAIXA, 0.0, Severidade.MEDIA, 0.0,
                Severidade.ALTA, 0.0, Severidade.CRITICA, 0.0));
        Map<Severidade, Double> faltando = new EnumMap<>(PADRAO.pesosSeveridade());
        faltando.remove(Severidade.CRITICA);

        assertThrows(IllegalArgumentException.class, () -> com(0.8, negativo, acima));
        assertThrows(IllegalArgumentException.class, () -> com(0.8, zeros, acima));
        assertThrows(IllegalArgumentException.class, () -> com(0.8, faltando, acima));
    }

    @Test
    void rejeitaProbabilidadeAcimaForaDaFaixaOuTipoFaltando() {
        var pesos = PADRAO.pesosSeveridade();
        Map<TipoSensor, Double> fora = new EnumMap<>(PADRAO.probabilidadeAcima());
        fora.put(TipoSensor.PRESSAO, 1.5);
        Map<TipoSensor, Double> faltando = new EnumMap<>(PADRAO.probabilidadeAcima());
        faltando.remove(TipoSensor.UMIDADE);

        assertThrows(IllegalArgumentException.class, () -> com(0.8, pesos, fora));
        assertThrows(IllegalArgumentException.class, () -> com(0.8, pesos, faltando));
    }

    @Test
    void velocidadeNaoAceitaDesvioAbaixo() {
        Map<TipoSensor, Double> acima = new EnumMap<>(PADRAO.probabilidadeAcima());
        acima.put(TipoSensor.VELOCIDADE, 0.9);
        var e = assertThrows(IllegalArgumentException.class, () -> com(0.8, PADRAO.pesosSeveridade(), acima));
        assertTrue(e.getMessage().toLowerCase().contains("velocidade"), e.getMessage());
    }

    @Test
    void mapasSaoCopiasImutaveis() {
        Map<Severidade, Double> pesos = new EnumMap<>(PADRAO.pesosSeveridade());
        ParametrosDeEpisodios p = com(0.8, pesos, PADRAO.probabilidadeAcima());
        pesos.put(Severidade.BAIXA, 999.0);

        assertEquals(40.0, p.pesosSeveridade().get(Severidade.BAIXA), "cópia defensiva");
        assertThrows(UnsupportedOperationException.class, () -> p.pesosSeveridade().put(Severidade.BAIXA, 1.0));
        assertThrows(UnsupportedOperationException.class, () -> p.probabilidadeAcima().put(TipoSensor.PRESSAO, 1.0));
    }

    @Test
    void comMediaEntreEpisodiosTrocaSoAMedia() {
        ParametrosDeEpisodios p = PADRAO.comMediaEntreEpisodios(20);
        assertEquals(20.0, p.mediaEntreEpisodios());
        assertEquals(PADRAO.aquecimentoMaximo(), p.aquecimentoMaximo());
        assertEquals(PADRAO.duracaoMinima(), p.duracaoMinima());
        assertEquals(PADRAO.pesosSeveridade(), p.pesosSeveridade());
    }

    @Test
    void comProbabilidadeDeCriseTrocaSoAProbabilidade() {
        ParametrosDeEpisodios p = PADRAO.comProbabilidadeDeCrise(1.0);
        assertEquals(1.0, p.probabilidadeDeCrise());
        assertEquals(PADRAO.mediaEntreEpisodios(), p.mediaEntreEpisodios());
        assertEquals(PADRAO.esfriamentoMinimo(), p.esfriamentoMinimo());
    }
}
