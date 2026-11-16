package domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Gerador de valores por eventos discretos — sem threads. O {@link AnalisadorDeAnomalias}
 * real é o oráculo: é ele que diz se um valor é alerta e com qual severidade.
 */
class GeradorPorEventosDiscretosTest {

    private static final AnalisadorDeAnomalias ANALISADOR = new AnalisadorDeAnomalias();
    private static final ParametrosDeEpisodios PADRAO = ParametrosDeEpisodios.padrao();
    /** Igual ao padrão, mas todo episódio passa do limite. */
    private static final ParametrosDeEpisodios SEMPRE_CRISE = PADRAO.comProbabilidadeDeCrise(1.0);
    private static final int LEITURAS = 200_000;

    private static GeradorPorEventosDiscretos gerador(TipoSensor tipo, ParametrosDeEpisodios p, long semente) {
        return GeradorPorEventosDiscretos.para(ANALISADOR, tipo, p, new Random(semente));
    }

    private static Optional<Alerta> analisar(TipoSensor tipo, double valor) {
        return ANALISADOR.analisar(new LeituraTelemetria("S-01", tipo, valor, Instant.EPOCH));
    }

    /** Distância até o limite do lado do episódio (positiva = ainda dentro da faixa). */
    private static double distanciaAoLimite(RegraDeAnomalia faixa, EpisodioDeAnomalia e, double v) {
        return e.direcao() == EpisodioDeAnomalia.Direcao.ACIMA ? faixa.maximo() - v : v - faixa.minimo();
    }

    @ParameterizedTest
    @EnumSource(TipoSensor.class)
    void foraDaCriseNuncaGeraAlertaNemNoAquecimentoNemNoEsfriamento(TipoSensor tipo) {
        GeradorPorEventosDiscretos g = gerador(tipo, PADRAO, 1);
        int alertas = 0;
        for (int i = 0; i < LEITURAS; i++) {
            double v = g.proximoValor();
            boolean alerta = analisar(tipo, v).isPresent();
            if (g.episodioAtual().isEmpty()) {
                assertFalse(alerta, "valor " + v + " fora da crise (fase " + g.fase() + ") no passo " + g.passo());
            }
            alertas += alerta ? 1 : 0;
        }
        assertTrue(alertas > 0, "houve alertas");
    }

    @ParameterizedTest
    @EnumSource(TipoSensor.class)
    void criseQuePassaDoLimiteAlertaEmTodaLeituraEOPicoEhASeveridadeAlvo(TipoSensor tipo) {
        GeradorPorEventosDiscretos g = gerador(tipo, SEMPRE_CRISE, 2);
        RegraDeAnomalia faixa = ANALISADOR.regraDo(tipo);
        EpisodioDeAnomalia corrente = null;
        Severidade pico = null;
        int crises = 0;
        for (int i = 0; i < LEITURAS; i++) {
            double v = g.proximoValor();
            Optional<EpisodioDeAnomalia> ep = g.episodioAtual();
            if (corrente != null && (ep.isEmpty() || !ep.get().equals(corrente))) {   // a crise acabou
                assertEquals(corrente.severidadeAlvo(), pico, "pico da crise " + corrente);
                corrente = null;
                crises++;
            }
            if (ep.isEmpty()) {
                continue;
            }
            EpisodioDeAnomalia e = ep.get();
            Alerta alerta = analisar(tipo, v).orElseThrow(
                    () -> new AssertionError("crise que passa do limite: todo valor é alerta, mas veio " + v));
            assertTrue(alerta.severidade().compareTo(e.severidadeAlvo()) <= 0,
                    "severidade " + alerta.severidade() + " acima do alvo em " + e + " (valor " + v + ")");
            assertTrue(distanciaAoLimite(faixa, e, v) < 0, "lado do episódio: " + v + " em " + e);
            if (corrente == null) {
                corrente = e;
                pico = alerta.severidade();
            } else if (alerta.severidade().compareTo(pico) > 0) {
                pico = alerta.severidade();
            }
        }
        assertTrue(crises > 1000, "crises: " + crises);
    }

    @ParameterizedTest
    @EnumSource(TipoSensor.class)
    void criseQueNaoPassaDoLimiteFicaLogoAntesDeleSemAlerta(TipoSensor tipo) {
        GeradorPorEventosDiscretos g = gerador(tipo, PADRAO, 3);
        RegraDeAnomalia faixa = ANALISADOR.regraDo(tipo);
        int conferidas = 0;
        for (int i = 0; i < LEITURAS; i++) {
            double v = g.proximoValor();
            Optional<EpisodioDeAnomalia> ep = g.episodioAtual();
            if (ep.isEmpty() || ep.get().passaDoLimite()) {
                continue;
            }
            conferidas++;
            assertTrue(analisar(tipo, v).isEmpty(), "quase-crise não gera alerta: " + v);
            double d = distanciaAoLimite(faixa, ep.get(), v);
            assertTrue(d >= 0 && d <= faixa.largura() * 0.05 + 0.05, "logo antes do limite: " + v + " em " + ep.get());
        }
        assertTrue(conferidas > 500, "leituras de quase-crise: " + conferidas);
    }

    @ParameterizedTest
    @EnumSource(TipoSensor.class)
    void aquecimentoSobeAteOLimiteEEsfriamentoComecaPertoDele(TipoSensor tipo) {
        GeradorPorEventosDiscretos g = gerador(tipo, SEMPRE_CRISE, 4);
        RegraDeAnomalia faixa = ANALISADOR.regraDo(tipo);
        double perto = faixa.largura() * 0.05 + 0.05;
        Double antesDoAquecimento = null;
        double ultimoValor = Double.NaN;
        CicloDeEpisodios.Fase faseAnterior = CicloDeEpisodios.Fase.NORMAL;
        EpisodioDeAnomalia anterior = null;
        int conferidos = 0;
        for (int i = 0; i < LEITURAS; i++) {
            double v = g.proximoValor();
            CicloDeEpisodios.Fase fase = g.fase();
            EpisodioDeAnomalia e = g.episodioEmAndamento().orElse(anterior);
            if (fase == CicloDeEpisodios.Fase.AQUECENDO && faseAnterior == CicloDeEpisodios.Fase.NORMAL) {
                antesDoAquecimento = ultimoValor;
            }
            if (fase == CicloDeEpisodios.Fase.AQUECENDO) {
                assertTrue(distanciaAoLimite(faixa, e, v) >= 0, "aquecimento ainda dentro da faixa: " + v);
            }
            if (fase == CicloDeEpisodios.Fase.CRISE && faseAnterior == CicloDeEpisodios.Fase.AQUECENDO) {
                double fimDoAquecimento = ultimoValor;
                assertTrue(distanciaAoLimite(faixa, e, fimDoAquecimento) <= perto,
                        "o aquecimento termina logo antes do limite: " + fimDoAquecimento + " em " + e);
                if (antesDoAquecimento != null && !antesDoAquecimento.isNaN()
                        && distanciaAoLimite(faixa, e, antesDoAquecimento) > perto) {   // se já não estava colado no limite
                    assertTrue(distanciaAoLimite(faixa, e, fimDoAquecimento) < distanciaAoLimite(faixa, e, antesDoAquecimento),
                            "aqueceu na direção do limite: " + antesDoAquecimento + " → " + fimDoAquecimento);
                }
                conferidos++;
            }
            if (fase == CicloDeEpisodios.Fase.ESFRIANDO && faseAnterior == CicloDeEpisodios.Fase.CRISE) {
                double d = distanciaAoLimite(faixa, e, v);
                assertTrue(d >= 0, "esfriamento já volta para dentro da faixa: " + v);
            }
            faseAnterior = fase;
            ultimoValor = v;
            anterior = e;
        }
        assertTrue(conferidos > 1000, "aquecimentos conferidos: " + conferidos);
    }

    @ParameterizedTest
    @EnumSource(TipoSensor.class)
    void pulosEntreLeiturasSeguidasSaoModeradosEnquantoNoGeradorAntigoNao(TipoSensor tipo) {
        RegraDeAnomalia faixa = ANALISADOR.regraDo(tipo);
        double[] novo = pulosEmFracaoDaLargura(gerador(tipo, SEMPRE_CRISE, 5), faixa);
        double[] antigo = pulosEmFracaoDaLargura(new GeradorPasseioAleatorio(faixa, new Random(5)), faixa);
        assertTrue(percentil99(novo) <= 0.15, "99 % dos pulos até 15 % da faixa; foi " + percentil99(novo));
        assertTrue(novo[novo.length - 1] <= 0.30, "maior pulo até 30 % da faixa; foi " + novo[novo.length - 1]);
        assertTrue(percentil99(antigo) > 0.40, "no gerador antigo os picos pulavam " + percentil99(antigo));
    }

    private static double[] pulosEmFracaoDaLargura(GeradorDeValores g, RegraDeAnomalia faixa) {
        double[] pulos = new double[LEITURAS - 1];
        double anterior = g.proximoValor();
        for (int i = 0; i < pulos.length; i++) {
            double v = g.proximoValor();
            pulos[i] = Math.abs(v - anterior) / faixa.largura();
            anterior = v;
        }
        Arrays.sort(pulos);
        return pulos;
    }

    private static double percentil99(double[] ordenados) {
        return ordenados[(int) (ordenados.length * 0.99)];
    }

    @Test
    void comProbabilidadeDeCriseUmCadaCriseViraExatamenteUmaRajadaDeAlertas() {
        GeradorPorEventosDiscretos g = gerador(TipoSensor.PRESSAO, SEMPRE_CRISE, 6);
        List<long[]> rajadas = new ArrayList<>();          // [primeiro passo, passo depois do último]
        List<EpisodioDeAnomalia> crises = new ArrayList<>();
        for (int i = 0; i < LEITURAS; i++) {
            double v = g.proximoValor();
            long passo = g.passo();
            g.episodioAtual().filter(e -> e.inicio() == passo).ifPresent(crises::add);
            if (analisar(TipoSensor.PRESSAO, v).isPresent()) {
                long[] ultima = rajadas.isEmpty() ? null : rajadas.get(rajadas.size() - 1);
                if (ultima != null && ultima[1] == passo) {
                    ultima[1] = passo + 1;
                } else {
                    rajadas.add(new long[] {passo, passo + 1});
                }
            }
        }
        if (g.episodioAtual().isPresent()) {      // última crise cortada pelo fim do teste
            crises.remove(crises.size() - 1);
            rajadas.remove(rajadas.size() - 1);
        }
        assertEquals(crises.size(), rajadas.size(), "uma rajada por crise");
        for (int i = 0; i < crises.size(); i++) {
            assertEquals(crises.get(i).inicio(), rajadas.get(i)[0]);
            assertEquals(crises.get(i).fim(), rajadas.get(i)[1]);
        }
    }

    @Test
    void alertasVemEmRajadasEnquantoNoGeradorAntigoVinhamIsolados() {
        GeradorDeValores novo = gerador(TipoSensor.TEMPERATURA, PADRAO, 7);
        GeradorDeValores antigo = new GeradorPasseioAleatorio(ANALISADOR.regraDo(TipoSensor.TEMPERATURA), new Random(7));
        double rajadaNova = tamanhoMedioDasRajadas(novo);
        double rajadaAntiga = tamanhoMedioDasRajadas(antigo);
        assertTrue(rajadaNova >= 6, "rajada média nova = " + rajadaNova);
        assertTrue(rajadaAntiga < 1.2, "rajada média antiga = " + rajadaAntiga);
    }

    private static double tamanhoMedioDasRajadas(GeradorDeValores g) {
        int rajadas = 0;
        int alertas = 0;
        boolean anteriorFoiAlerta = false;
        for (int i = 0; i < LEITURAS; i++) {
            boolean alerta = analisar(TipoSensor.TEMPERATURA, g.proximoValor()).isPresent();
            if (alerta) {
                alertas++;
                rajadas += anteriorFoiAlerta ? 0 : 1;
            }
            anteriorFoiAlerta = alerta;
        }
        return (double) alertas / rajadas;
    }

    @Test
    void taxaDeAlertasFicaPertoDaEsperada() {
        GeradorDeValores g = gerador(TipoSensor.UMIDADE, PADRAO, 8);
        int alertas = 0;
        for (int i = 0; i < LEITURAS; i++) {
            alertas += analisar(TipoSensor.UMIDADE, g.proximoValor()).isPresent() ? 1 : 0;
        }
        double crise = (PADRAO.duracaoMinima() + PADRAO.duracaoMaxima()) / 2.0;
        double ciclo = PADRAO.mediaEntreEpisodios() + crise
                + (PADRAO.aquecimentoMinimo() + PADRAO.aquecimentoMaximo()) / 2.0
                + (PADRAO.esfriamentoMinimo() + PADRAO.esfriamentoMaximo()) / 2.0;
        double esperada = PADRAO.probabilidadeDeCrise() * crise / ciclo;
        double taxa = (double) alertas / LEITURAS;
        assertEquals(esperada, taxa, 0.01, "taxa = " + taxa + ", esperada = " + esperada);   // ~7 %
    }

    @Test
    void velocidadeNuncaFicaAbaixoDaFaixa() {
        GeradorDeValores g = gerador(TipoSensor.VELOCIDADE, SEMPRE_CRISE, 9);
        for (int i = 0; i < LEITURAS; i++) {
            double v = g.proximoValor();
            assertTrue(v >= 0, "velocidade negativa: " + v);
        }
    }

    @Test
    void mesmaSementeGeraMesmaSerie() {
        GeradorDeValores a = gerador(TipoSensor.TEMPERATURA, PADRAO, 10);
        GeradorDeValores b = gerador(TipoSensor.TEMPERATURA, PADRAO, 10);
        for (int i = 0; i < 20_000; i++) {
            assertEquals(a.proximoValor(), b.proximoValor(), "leitura " + i);
        }
    }

    @ParameterizedTest
    @EnumSource(TipoSensor.class)
    void valoresSaoFinitosComNoMaximoUmaCasaDecimal(TipoSensor tipo) {
        GeradorDeValores g = gerador(tipo, SEMPRE_CRISE, 11);
        for (int i = 0; i < 20_000; i++) {
            double v = g.proximoValor();
            assertTrue(Double.isFinite(v));
            assertEquals(Math.round(v * 10) / 10.0, v, 1e-9);
        }
    }

    @Test
    void relogioSimuladoAvancaUmPassoPorLeitura() {
        GeradorPorEventosDiscretos g = gerador(TipoSensor.PRESSAO, PADRAO, 12);
        assertEquals(0, g.passo());
        for (int i = 1; i <= 100; i++) {
            g.proximoValor();
            assertEquals(i, g.passo());
        }
    }

    @Test
    void rejeitaEscalaQueNaoSejaBaixaMediaAltaCriticaEmOrdem() {
        RegraDeAnomalia faixa = ANALISADOR.regraDo(TipoSensor.TEMPERATURA);
        List<DegrauDeSeveridade> escala = ANALISADOR.escala(TipoSensor.TEMPERATURA);
        Random r = new Random();
        assertThrows(IllegalArgumentException.class,
                () -> new GeradorPorEventosDiscretos(faixa, escala.subList(0, 3), PADRAO, r), "faltando CRITICA");
        assertThrows(IllegalArgumentException.class,
                () -> new GeradorPorEventosDiscretos(faixa, List.of(escala.get(1), escala.get(0), escala.get(2), escala.get(3)),
                        PADRAO, r), "fora de ordem");
        assertDoesNotThrow(() -> new GeradorPorEventosDiscretos(faixa, escala, PADRAO, r));
    }

    @Test
    void fabricaUsaAsFaixasDoAnalisadorSemDuplicarNumeros() {
        for (TipoSensor tipo : TipoSensor.values()) {
            GeradorPorEventosDiscretos g = gerador(tipo, PADRAO, 13);
            assertEquals(ANALISADOR.regraDo(tipo), g.faixa());
            assertEquals(tipo, g.faixa().tipo());
        }
    }

    // ------------------------------------------------ faixa própria do sensor

    /** TEMP-02 com faixa 10–60 (padrão do tipo continua 0–80). */
    private static final ConfiguracaoSensores PERSONALIZADA = ConfiguracaoSensores.padrao()
            .comSensorNovo(TipoSensor.TEMPERATURA)
            .comFaixaDoSensor("TEMP-02", 10, 60);
    private static final AnalisadorDeAnomalias POR_SENSOR = new AnalisadorDeAnomalias(PERSONALIZADA);

    @Test
    void comFaixaPersonalizadaForaDaCriseNuncaAlertaEACriseCruzaOLimiteDoSensor() {
        RegraDeAnomalia faixa = POR_SENSOR.regraDo("TEMP-02", TipoSensor.TEMPERATURA);
        GeradorPorEventosDiscretos g = GeradorPorEventosDiscretos.para(faixa, SEMPRE_CRISE, new Random(21));
        assertEquals(faixa, g.faixa());
        int alertas = 0;
        for (int i = 0; i < LEITURAS; i++) {
            double v = g.proximoValor();
            Optional<Alerta> alerta = POR_SENSOR.analisar(
                    new LeituraTelemetria("TEMP-02", TipoSensor.TEMPERATURA, v, Instant.EPOCH));
            if (g.episodioAtual().isEmpty()) {
                assertFalse(alerta.isPresent(), "valor " + v + " fora da crise (fase " + g.fase() + ")");
                assertTrue(v >= 10 && v <= 60, "fora da crise fica na faixa do sensor: " + v);
            } else {
                assertTrue(alerta.isPresent(), "crise que passa do limite: todo valor é alerta, mas veio " + v);
                assertTrue(alerta.get().severidade().compareTo(g.episodioAtual().get().severidadeAlvo()) <= 0);
                alertas++;
            }
        }
        assertTrue(alertas > 1000, "alertas: " + alertas);
    }

    @Test
    void comFaixaPersonalizadaOsValoresNaoSeguemAFaixaPadraoDoTipo() {
        RegraDeAnomalia faixa = POR_SENSOR.regraDo("TEMP-02", TipoSensor.TEMPERATURA);
        GeradorPorEventosDiscretos g = GeradorPorEventosDiscretos.para(faixa, PADRAO, new Random(22));
        double maior = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < LEITURAS; i++) {
            double v = g.proximoValor();
            if (g.episodioEmAndamento().isEmpty()) {
                maior = Math.max(maior, v);
            }
        }
        assertTrue(maior <= 60, "passeio normal respeita o máximo 60 do sensor, maior = " + maior);
    }

    @Test
    void fabricaPorFaixaUsaAEscalaDaPropriaFaixa() {
        RegraDeAnomalia faixa = new RegraDeAnomalia(TipoSensor.PRESSAO, 2, 6);
        GeradorPorEventosDiscretos g = GeradorPorEventosDiscretos.para(faixa, PADRAO, new Random(23));
        assertEquals(faixa, g.faixa());
    }
}
