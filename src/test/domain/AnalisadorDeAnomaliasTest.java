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
}
