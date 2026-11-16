package domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Regras de anomalia testadas de forma isolada: sem threads, sem banco, sem arquivo.
 *
 * <p>Faixas aceitas (limites inclusivos): TEMPERATURA 0–80 · VELOCIDADE 0–120 ·
 * PRESSAO 1–8 · UMIDADE 20–90. Severidade = desvio além do limite em % da largura da
 * faixa: até 5 % BAIXA · até 15 % MEDIA · até 30 % ALTA · acima CRITICA.</p>
 */
class AnalisadorDeAnomaliasTest {

    private static final Instant T = Instant.parse("2026-10-20T12:00:00Z");
    private final AnalisadorDeAnomalias analisador = new AnalisadorDeAnomalias();

    private static LeituraTelemetria leitura(String id, TipoSensor tipo, double valor) {
        return new LeituraTelemetria(id, tipo, valor, T, 1L);
    }

    private Optional<Alerta> analisar(TipoSensor tipo, double valor) {
        return analisador.analisar(leitura("X1", tipo, valor));
    }

    private Severidade severidade(TipoSensor tipo, double valor) {
        return analisar(tipo, valor).orElseThrow(
                () -> new AssertionError(tipo + " " + valor + " deveria gerar alerta")).severidade();
    }

    // ------------------------------------------------ portados do projeto antigo

    @Nested
    @DisplayName("Regras por tipo (portados do projeto antigo)")
    class Portados {

        @Test
        void temperaturaAcimaDoLimiteGeraAlerta() {
            assertTrue(analisar(TipoSensor.TEMPERATURA, 80.1).isPresent());
        }

        @Test
        void temperaturaExatamenteNoLimiteNaoGeraAlerta() {
            assertTrue(analisar(TipoSensor.TEMPERATURA, 80.0).isEmpty());
        }

        @Test
        void velocidadeAbaixoDaFaixaGeraAlerta() {
            assertTrue(analisar(TipoSensor.VELOCIDADE, -0.1).isPresent());
        }

        @Test
        void velocidadeAcimaDaFaixaGeraAlerta() {
            assertTrue(analisar(TipoSensor.VELOCIDADE, 120.1).isPresent());
        }

        @Test
        void velocidadeNosLimitesDaFaixaNaoGeraAlerta() {
            assertTrue(analisar(TipoSensor.VELOCIDADE, 0.0).isEmpty());
            assertTrue(analisar(TipoSensor.VELOCIDADE, 120.0).isEmpty());
        }

        @Test
        void pressaoAbaixoDaFaixaGeraAlerta() {
            assertTrue(analisar(TipoSensor.PRESSAO, 0.5).isPresent());
        }

        @Test
        void pressaoAcimaDaFaixaGeraAlerta() {
            assertTrue(analisar(TipoSensor.PRESSAO, 8.5).isPresent());
        }

        @Test
        void pressaoNosLimitesDaFaixaNaoGeraAlerta() {
            assertTrue(analisar(TipoSensor.PRESSAO, 1.0).isEmpty());
            assertTrue(analisar(TipoSensor.PRESSAO, 8.0).isEmpty());
        }

        @Test
        void cadaTipoUsaSuaPropriaRegra() {
            // 100 é anômalo para temperatura, mas normal para velocidade
            assertTrue(analisar(TipoSensor.TEMPERATURA, 100).isPresent());
            assertTrue(analisar(TipoSensor.VELOCIDADE, 100).isEmpty());
        }

        @Test
        void alertaCarregaSensorValorMomentoERegraCorretos() {
            Alerta alerta = analisador.analisar(leitura("T7", TipoSensor.TEMPERATURA, 95.0)).orElseThrow();

            assertNull(alerta.id(), "alerta novo ainda não foi persistido");
            assertEquals("T7", alerta.sensorId());
            assertEquals(TipoSensor.TEMPERATURA, alerta.tipo());
            assertEquals(95.0, alerta.valorLido());
            assertEquals(T, alerta.momentoLeitura());
            assertFalse(alerta.regraViolada().isBlank());
        }
    }

    // ------------------------------------------------ umidade e faixas mínimas

    @Nested
    @DisplayName("Umidade e limites inferiores")
    class Umidade {

        @Test
        void umidadeAbaixoDaFaixaGeraAlerta() {
            assertTrue(analisar(TipoSensor.UMIDADE, 19.9).isPresent());
        }

        @Test
        void umidadeAcimaDaFaixaGeraAlerta() {
            assertTrue(analisar(TipoSensor.UMIDADE, 90.1).isPresent());
        }

        @Test
        void umidadeNosLimitesDaFaixaNaoGeraAlerta() {
            assertTrue(analisar(TipoSensor.UMIDADE, 20.0).isEmpty());
            assertTrue(analisar(TipoSensor.UMIDADE, 90.0).isEmpty());
        }

        @Test
        void temperaturaAbaixoDeZeroGeraAlerta() {
            assertTrue(analisar(TipoSensor.TEMPERATURA, -0.1).isPresent());
            assertTrue(analisar(TipoSensor.TEMPERATURA, 0.0).isEmpty());
        }
    }

    // ------------------------------------------------ severidade

    @Nested
    @DisplayName("Severidade pelo desvio além do limite")
    class SeveridadePorDesvio {

        // Temperatura: largura 80 → 5 % = 4, 15 % = 12, 30 % = 24 acima de 80
        @ParameterizedTest(name = "temperatura {0} → {1}")
        @CsvSource({
                "80.1,  BAIXA",
                "84.0,  BAIXA",     // exatamente 5 %
                "84.1,  MEDIA",
                "92.0,  MEDIA",     // exatamente 15 %
                "92.1,  ALTA",
                "104.0, ALTA",      // exatamente 30 %
                "104.1, CRITICA",
                "500.0, CRITICA"
        })
        void fronteirasNaTemperatura(double valor, Severidade esperada) {
            assertEquals(esperada, severidade(TipoSensor.TEMPERATURA, valor));
        }

        // Velocidade: largura 120 → 5 % = 6, 15 % = 18, 30 % = 36 abaixo de 0
        @ParameterizedTest(name = "velocidade {0} → {1}")
        @CsvSource({
                "-6.0,  BAIXA",
                "-6.1,  MEDIA",
                "-18.0, MEDIA",
                "-18.1, ALTA",
                "-36.0, ALTA",
                "-36.1, CRITICA"
        })
        void fronteirasAbaixoDaFaixaNaVelocidade(double valor, Severidade esperada) {
            assertEquals(esperada, severidade(TipoSensor.VELOCIDADE, valor));
        }

        @Test
        void severidadeUsaALarguraDaFaixaDoProprioTipo() {
            // mesmo desvio absoluto (+1 acima do máximo), faixas de larguras diferentes
            assertEquals(Severidade.BAIXA, severidade(TipoSensor.TEMPERATURA, 81.0));   // 1/80 = 1,25 %
            assertEquals(Severidade.MEDIA, severidade(TipoSensor.PRESSAO, 9.0));        // 1/7  ≈ 14,3 %
        }

        @Test
        void pressaoComDesvioGrandeECritica() {
            assertEquals(Severidade.CRITICA, severidade(TipoSensor.PRESSAO, 11.0));     // 3/7 ≈ 42,9 %
            assertEquals(Severidade.BAIXA, severidade(TipoSensor.PRESSAO, 8.3));        // 0,3/7 ≈ 4,3 %
        }

        @Test
        void umidadeAbaixoComSeveridadeMedia() {
            assertEquals(Severidade.MEDIA, severidade(TipoSensor.UMIDADE, 14.0));       // 6/70 ≈ 8,6 %
        }
    }

    // ------------------------------------------------ texto da regra e catálogo

    @Nested
    @DisplayName("Texto da regra violada e lista de regras")
    class TextoERegras {

        @Test
        void regraVioladaAcimaDoLimiteIndicaLimiteEUnidade() {
            Alerta a = analisar(TipoSensor.TEMPERATURA, 92.5).orElseThrow();
            assertEquals("acima do limite 80,0 °C", a.regraViolada());
        }

        @Test
        void regraVioladaAbaixoDoLimiteIndicaLimiteEUnidade() {
            Alerta a = analisar(TipoSensor.PRESSAO, 0.5).orElseThrow();
            assertEquals("abaixo do limite 1,0 bar", a.regraViolada());
        }

        @Test
        void regrasListaUmaFaixaPorTipoNaOrdemDoEnum() {
            List<RegraDeAnomalia> regras = analisador.regras();
            assertEquals(List.of(
                    new RegraDeAnomalia(TipoSensor.TEMPERATURA, 0, 80),
                    new RegraDeAnomalia(TipoSensor.VELOCIDADE, 0, 120),
                    new RegraDeAnomalia(TipoSensor.PRESSAO, 1, 8),
                    new RegraDeAnomalia(TipoSensor.UMIDADE, 20, 90)), regras);
        }

        @Test
        void escalaDeSeveridadeEmUnidadesDoSensor() {
            assertEquals(List.of(
                    new DegrauDeSeveridade(Severidade.BAIXA, 4.0),
                    new DegrauDeSeveridade(Severidade.MEDIA, 12.0),
                    new DegrauDeSeveridade(Severidade.ALTA, 24.0),
                    new DegrauDeSeveridade(Severidade.CRITICA, Double.POSITIVE_INFINITY)),
                    analisador.escala(TipoSensor.TEMPERATURA));
            assertEquals(36.0, analisador.escala(TipoSensor.VELOCIDADE).get(2).desvioMaximo(), 1e-9);
            assertTrue(analisador.escala(TipoSensor.UMIDADE).get(3).semLimite());
        }

        @Test
        void escalaBateComASeveridadeCalculadaNasFronteiras() {
            for (TipoSensor tipo : TipoSensor.values()) {
                RegraDeAnomalia r = analisador.regraDo(tipo);
                for (DegrauDeSeveridade d : analisador.escala(tipo)) {
                    if (!d.semLimite()) {
                        assertEquals(d.severidade(), severidade(tipo, r.maximo() + d.desvioMaximo()),
                                tipo + " no teto de " + d.severidade());
                    }
                }
            }
        }

        @Test
        void regrasNaoPodemSerAlteradasPorQuemConsulta() {
            assertThrows(UnsupportedOperationException.class,
                    () -> analisador.regras().add(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 0, 1)));
        }

    }

    // ------------------------------------------------ faixa própria de cada sensor

    @Nested
    @DisplayName("Faixa por sensor")
    class FaixaPorSensor {

        /** TEMP-01 com a faixa padrão (0–80) e TEMP-02 com 10–60. */
        private final ConfiguracaoSensores cfg = ConfiguracaoSensores.padrao()
                .comSensorNovo(TipoSensor.TEMPERATURA)
                .comFaixaDoSensor("TEMP-02", 10, 60);
        private final AnalisadorDeAnomalias porSensor = new AnalisadorDeAnomalias(cfg);

        private Optional<Alerta> analisar(String id, double valor) {
            return porSensor.analisar(leitura(id, TipoSensor.TEMPERATURA, valor));
        }

        @Test
        void construtorSemArgumentosUsaAConfiguracaoPadrao() {
            AnalisadorDeAnomalias padrao = new AnalisadorDeAnomalias(ConfiguracaoSensores.padrao());
            for (TipoSensor tipo : TipoSensor.values()) {
                assertEquals(padrao.regraDo(tipo), analisador.regraDo(tipo));
            }
            assertEquals(padrao.regras(), analisador.regras());
        }

        @Test
        void mesmoValorEhNormalNumSensorEAlertaNoOutro() {
            assertTrue(analisar("TEMP-01", 70).isEmpty(), "70 dentro de 0–80");
            assertTrue(analisar("TEMP-02", 70).isPresent(), "70 fora de 10–60");
            assertTrue(analisar("TEMP-01", 5).isEmpty(), "5 dentro de 0–80");
            assertTrue(analisar("TEMP-02", 5).isPresent(), "5 abaixo de 10");
        }

        @Test
        void limitesDaFaixaPersonalizadaSaoInclusivos() {
            assertTrue(analisar("TEMP-02", 60.0).isEmpty());
            assertTrue(analisar("TEMP-02", 10.0).isEmpty());
            assertTrue(analisar("TEMP-02", 60.1).isPresent());
            assertTrue(analisar("TEMP-02", 9.9).isPresent());
        }

        @ParameterizedTest(name = "TEMP-02 (10–60) {0} → {1}")
        @CsvSource({
                // largura 50: BAIXA até 2,5 · MEDIA até 7,5 · ALTA até 15
                "62.5, BAIXA", "62.6, MEDIA",
                "67.5, MEDIA", "67.6, ALTA",
                "75.0, ALTA",  "75.1, CRITICA",
                "7.5,  BAIXA", "7.4,  MEDIA",
        })
        void fronteirasDeSeveridadeSobreALarguraDaFaixaDoSensor(double valor, Severidade esperada) {
            assertEquals(esperada, analisar("TEMP-02", valor).orElseThrow().severidade());
        }

        @Test
        void regraVioladaMostraOLimiteDoProprioSensor() {
            assertEquals("acima do limite 60,0 °C", analisar("TEMP-02", 70).orElseThrow().regraViolada());
            assertEquals("abaixo do limite 10,0 °C", analisar("TEMP-02", 5).orElseThrow().regraViolada());
        }

        @Test
        void sensorDesconhecidoUsaOPadraoDoTipo() {
            assertTrue(analisar("TEMP-77", 70).isEmpty(), "padrão 0–80");
            assertEquals("acima do limite 80,0 °C", analisar("TEMP-77", 81).orElseThrow().regraViolada());
        }

        @Test
        void leituraDeOutroTipoComIdConfiguradoUsaOPadraoDoTipoDaLeitura() {
            Optional<Alerta> a = porSensor.analisar(leitura("TEMP-02", TipoSensor.PRESSAO, 9));
            assertEquals("acima do limite 8,0 bar", a.orElseThrow().regraViolada());
        }

        @Test
        void regraEEscalaPorSensor() {
            assertEquals(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 10, 60),
                    porSensor.regraDo("TEMP-02", TipoSensor.TEMPERATURA));
            assertEquals(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 0, 80),
                    porSensor.regraDo("TEMP-77", TipoSensor.TEMPERATURA));
            assertEquals(List.of(2.5, 7.5, 15.0), porSensor.escala("TEMP-02", TipoSensor.TEMPERATURA).stream()
                    .limit(3).map(DegrauDeSeveridade::desvioMaximo).toList());
            assertEquals(porSensor.escala(TipoSensor.TEMPERATURA),
                    AnalisadorDeAnomalias.escalaPara(porSensor.regraDo(TipoSensor.TEMPERATURA)));
        }

        @Test
        void padraoDoTipoNaConfiguracaoSubstituiOPadraoDoProjeto() {
            AnalisadorDeAnomalias a = new AnalisadorDeAnomalias(
                    ConfiguracaoSensores.padrao().comFaixaPadrao(TipoSensor.PRESSAO, 2, 6));
            assertEquals(new RegraDeAnomalia(TipoSensor.PRESSAO, 2, 6), a.regraDo(TipoSensor.PRESSAO));
            // PRES-01 foi criado antes da mudança do padrão: continua 1–8
            assertTrue(a.analisar(leitura("PRES-01", TipoSensor.PRESSAO, 7)).isEmpty());
            // sensor que não está na configuração usa o padrão vigente (2–6)
            assertTrue(a.analisar(leitura("PRES-77", TipoSensor.PRESSAO, 7)).isPresent());
        }
    }
}
