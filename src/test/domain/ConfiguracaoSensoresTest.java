package domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Configuração dos sensores (faixa padrão por tipo + faixa própria de cada sensor + intervalo)
 * — regra pura, sem threads, banco nem arquivo.
 */
class ConfiguracaoSensoresTest {

    private static final ConfiguracaoSensores PADRAO = ConfiguracaoSensores.padrao();

    private static List<String> ids(ConfiguracaoSensores c) {
        return c.sensores().stream().map(SensorConfigurado::id).toList();
    }

    @Nested
    @DisplayName("Padrão")
    class Padrao {

        @Test
        void quatroSensoresUmDeCadaTipoNaOrdemDoEnum() {
            assertEquals(List.of("TEMP-01", "VEL-01", "PRES-01", "UMID-01"), ids(PADRAO));
            assertEquals(List.of(TipoSensor.values()),
                    PADRAO.sensores().stream().map(SensorConfigurado::tipo).toList());
        }

        @Test
        void intervaloDe200ms() {
            assertEquals(200, PADRAO.intervaloMs());
        }

        @Test
        void faixasPadraoSaoAsDaTabelaDoProjeto() {
            assertEquals(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 0, 80), PADRAO.padraoDo(TipoSensor.TEMPERATURA));
            assertEquals(new RegraDeAnomalia(TipoSensor.VELOCIDADE, 0, 120), PADRAO.padraoDo(TipoSensor.VELOCIDADE));
            assertEquals(new RegraDeAnomalia(TipoSensor.PRESSAO, 1, 8), PADRAO.padraoDo(TipoSensor.PRESSAO));
            assertEquals(new RegraDeAnomalia(TipoSensor.UMIDADE, 20, 90), PADRAO.padraoDo(TipoSensor.UMIDADE));
        }

        @Test
        void cadaSensorPadraoUsaAFaixaPadraoDoTipo() {
            for (SensorConfigurado s : PADRAO.sensores()) {
                assertEquals(PADRAO.padraoDo(s.tipo()), s.regra(), s.id());
            }
        }

        @Test
        void faixasPadraoVemDeUmUnicoLugar() {
            for (TipoSensor tipo : TipoSensor.values()) {
                assertEquals(RegraDeAnomalia.padraoDo(tipo), PADRAO.padraoDo(tipo));
            }
        }
    }

    @Nested
    @DisplayName("Adicionar sensor")
    class Adicionar {

        @Test
        void idsSequenciaisPorTipo() {
            ConfiguracaoSensores c = PADRAO
                    .comSensorNovo(TipoSensor.TEMPERATURA)
                    .comSensorNovo(TipoSensor.TEMPERATURA)
                    .comSensorNovo(TipoSensor.PRESSAO);
            assertEquals(List.of("TEMP-01", "VEL-01", "PRES-01", "UMID-01", "TEMP-02", "TEMP-03", "PRES-02"), ids(c));
            assertEquals(List.of("TEMP-01", "TEMP-02", "TEMP-03"),
                    c.sensoresDo(TipoSensor.TEMPERATURA).stream().map(SensorConfigurado::id).toList());
        }

        @Test
        void proximoIdDoTipoAnunciaQualSeraCriado() {
            assertEquals("VEL-02", PADRAO.proximoIdDo(TipoSensor.VELOCIDADE));
            assertEquals("VEL-03", PADRAO.comSensorNovo(TipoSensor.VELOCIDADE).proximoIdDo(TipoSensor.VELOCIDADE));
        }

        @Test
        void sensorNovoHerdaAFaixaPadraoVigenteDoTipo() {
            ConfiguracaoSensores c = PADRAO
                    .comFaixaPadrao(TipoSensor.TEMPERATURA, 10, 60)
                    .comSensorNovo(TipoSensor.TEMPERATURA);
            assertEquals(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 10, 60), c.regraDo("TEMP-02").orElseThrow());
        }

        @Test
        void adicionarNaoAlteraAConfiguracaoOriginal() {
            ConfiguracaoSensores c = PADRAO.comSensorNovo(TipoSensor.UMIDADE);
            assertEquals(4, PADRAO.sensores().size());
            assertEquals(5, c.sensores().size());
        }
    }

    @Nested
    @DisplayName("Alterar faixas e intervalo")
    class Alterar {

        @Test
        void mudarOPadraoNaoAlteraSensoresExistentes() {
            ConfiguracaoSensores c = PADRAO.comFaixaPadrao(TipoSensor.TEMPERATURA, 10, 60);
            assertEquals(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 10, 60), c.padraoDo(TipoSensor.TEMPERATURA));
            assertEquals(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 0, 80), c.regraDo("TEMP-01").orElseThrow());
        }

        @Test
        void mudarAFaixaDeUmSensorNaoAlteraOsOutrosNemOPadrao() {
            ConfiguracaoSensores c = PADRAO.comSensorNovo(TipoSensor.TEMPERATURA)
                    .comFaixaDoSensor("TEMP-02", -10, 50);
            assertEquals(new RegraDeAnomalia(TipoSensor.TEMPERATURA, -10, 50), c.regraDo("TEMP-02").orElseThrow());
            assertEquals(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 0, 80), c.regraDo("TEMP-01").orElseThrow());
            assertEquals(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 0, 80), c.padraoDo(TipoSensor.TEMPERATURA));
        }

        @Test
        void mudarAFaixaMantemAOrdemDosSensores() {
            ConfiguracaoSensores c = PADRAO.comFaixaDoSensor("VEL-01", 10, 100);
            assertEquals(ids(PADRAO), ids(c));
        }

        @Test
        void mudarOIntervalo() {
            assertEquals(300, PADRAO.comIntervalo(300).intervaloMs());
            assertEquals(200, PADRAO.intervaloMs());
        }

        @Test
        void sensorInexistenteNaoTemRegra() {
            assertTrue(PADRAO.regraDo("TEMP-99").isEmpty());
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> PADRAO.comFaixaDoSensor("TEMP-99", 0, 10));
            assertTrue(e.getMessage().contains("TEMP-99"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("Validações")
    class Validacoes {

        @Test
        void minimoPrecisaSerMenorQueMaximo() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> PADRAO.comFaixaDoSensor("TEMP-01", 50, 50));
            assertTrue(e.getMessage().contains("menor"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> PADRAO.comFaixaPadrao(TipoSensor.PRESSAO, 9, 2));
        }

        @Test
        void valoresPrecisamSerFinitos() {
            assertThrows(IllegalArgumentException.class, () -> PADRAO.comFaixaDoSensor("TEMP-01", Double.NaN, 10));
            assertThrows(IllegalArgumentException.class,
                    () -> PADRAO.comFaixaPadrao(TipoSensor.TEMPERATURA, Double.NEGATIVE_INFINITY, 10));
            assertThrows(IllegalArgumentException.class,
                    () -> PADRAO.comFaixaPadrao(TipoSensor.TEMPERATURA, 0, Double.POSITIVE_INFINITY));
        }

        @Test
        void intervaloEntre10e5000ms() {
            assertThrows(IllegalArgumentException.class, () -> PADRAO.comIntervalo(9));
            assertThrows(IllegalArgumentException.class, () -> PADRAO.comIntervalo(5001));
            assertDoesNotThrow(() -> PADRAO.comIntervalo(10));
            assertDoesNotThrow(() -> PADRAO.comIntervalo(5000));
        }

        @Test
        void idsPrecisamSerUnicos() {
            SensorConfigurado t1 = new SensorConfigurado("TEMP-01", RegraDeAnomalia.padraoDo(TipoSensor.TEMPERATURA));
            assertThrows(IllegalArgumentException.class,
                    () -> new ConfiguracaoSensores(200, PADRAO.padroes(), List.of(t1, t1)));
        }

        @Test
        void precisaDeAoMenosUmSensor() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ConfiguracaoSensores(200, PADRAO.padroes(), List.of()));
        }

        @Test
        void precisaDeUmPadraoParaCadaTipo() {
            Map<TipoSensor, RegraDeAnomalia> incompleto = Map.of(
                    TipoSensor.TEMPERATURA, RegraDeAnomalia.padraoDo(TipoSensor.TEMPERATURA));
            assertThrows(IllegalArgumentException.class,
                    () -> new ConfiguracaoSensores(200, incompleto, PADRAO.sensores()));
        }

        @Test
        void padraoPrecisaSerDoMesmoTipoDaChave() {
            Map<TipoSensor, RegraDeAnomalia> trocado = new java.util.EnumMap<>(PADRAO.padroes());
            trocado.put(TipoSensor.UMIDADE, RegraDeAnomalia.padraoDo(TipoSensor.PRESSAO));
            assertThrows(IllegalArgumentException.class,
                    () -> new ConfiguracaoSensores(200, trocado, PADRAO.sensores()));
        }

        @Test
        void sensorPrecisaDeIdNaoVazio() {
            RegraDeAnomalia r = RegraDeAnomalia.padraoDo(TipoSensor.TEMPERATURA);
            assertThrows(IllegalArgumentException.class, () -> new SensorConfigurado(" ", r));
            assertThrows(NullPointerException.class, () -> new SensorConfigurado(null, r));
            assertThrows(NullPointerException.class, () -> new SensorConfigurado("TEMP-01", null));
        }
    }

    @Nested
    @DisplayName("Imutabilidade")
    class Imutabilidade {

        @Test
        void colecoesNaoPodemSerAlteradasPorQuemConsulta() {
            assertThrows(UnsupportedOperationException.class, () -> PADRAO.sensores().clear());
            assertThrows(UnsupportedOperationException.class, () -> PADRAO.padroes().clear());
            assertThrows(UnsupportedOperationException.class,
                    () -> PADRAO.sensoresDo(TipoSensor.TEMPERATURA).clear());
        }

        @Test
        void copiaDefensivaDasColecoesRecebidas() {
            java.util.List<SensorConfigurado> lista = new java.util.ArrayList<>(PADRAO.sensores());
            ConfiguracaoSensores c = new ConfiguracaoSensores(200, PADRAO.padroes(), lista);
            lista.clear();
            assertEquals(4, c.sensores().size());
        }

        @Test
        void igualdadePorValor() {
            assertEquals(ConfiguracaoSensores.padrao(), ConfiguracaoSensores.padrao());
            assertEquals(PADRAO.comSensorNovo(TipoSensor.PRESSAO), PADRAO.comSensorNovo(TipoSensor.PRESSAO));
        }
    }

    @Nested
    @DisplayName("Quantidade por tipo (modo console e testes)")
    class QuantidadePorTipo {

        @Test
        void criaNSensoresDeCadaTipoAgrupadosPorTipo() {
            ConfiguracaoSensores c = PADRAO.comQuantidadePorTipo(2);
            assertEquals(List.of("TEMP-01", "TEMP-02", "VEL-01", "VEL-02", "PRES-01", "PRES-02", "UMID-01", "UMID-02"),
                    ids(c));
        }

        @Test
        void mantemAFaixaDosSensoresQueJaExistiamENovosUsamOPadraoVigente() {
            ConfiguracaoSensores c = PADRAO.comFaixaDoSensor("TEMP-01", 5, 50)
                    .comFaixaPadrao(TipoSensor.TEMPERATURA, 10, 60)
                    .comQuantidadePorTipo(2);
            assertEquals(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 5, 50), c.regraDo("TEMP-01").orElseThrow());
            assertEquals(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 10, 60), c.regraDo("TEMP-02").orElseThrow());
        }

        @Test
        void reduzMantendoOsPrimeirosDeCadaTipo() {
            ConfiguracaoSensores c = PADRAO.comQuantidadePorTipo(3).comQuantidadePorTipo(1);
            assertEquals(ids(PADRAO), ids(c));
            assertEquals(PADRAO.intervaloMs(), c.intervaloMs());
        }

        @Test
        void quantidadeEntre1e10() {
            assertThrows(IllegalArgumentException.class, () -> PADRAO.comQuantidadePorTipo(0));
            assertThrows(IllegalArgumentException.class, () -> PADRAO.comQuantidadePorTipo(11));
        }
    }

    @Nested
    @DisplayName("Remover sensor")
    class Remover {

        @Test
        void removeSoOSensorPedidoEMantemAOrdemDosOutros() {
            ConfiguracaoSensores c = PADRAO.comSensorNovo(TipoSensor.TEMPERATURA)
                    .comSensorNovo(TipoSensor.TEMPERATURA)
                    .comFaixaDoSensor("TEMP-03", 5, 50)
                    .semSensor("TEMP-02");
            assertEquals(List.of("TEMP-01", "VEL-01", "PRES-01", "UMID-01", "TEMP-03"), ids(c));
            assertEquals(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 5, 50), c.regraDo("TEMP-03").orElseThrow());
            assertTrue(c.regraDo("TEMP-02").isEmpty());
        }

        @Test
        void podeRemoverTodosOsSensoresDeUmTipo() {
            ConfiguracaoSensores c = PADRAO.semSensor("VEL-01");
            assertTrue(c.sensoresDo(TipoSensor.VELOCIDADE).isEmpty());
            assertEquals(new RegraDeAnomalia(TipoSensor.VELOCIDADE, 0, 120), c.padraoDo(TipoSensor.VELOCIDADE),
                    "o padrão do tipo continua lá para sensores novos");
            assertEquals("VEL-01", c.proximoIdDo(TipoSensor.VELOCIDADE));
        }

        @Test
        void naoRemoveOUltimoSensorDaConfiguracao() {
            ConfiguracaoSensores um = PADRAO.semSensor("VEL-01").semSensor("PRES-01").semSensor("UMID-01");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> um.semSensor("TEMP-01"));
            assertTrue(e.getMessage().contains("pelo menos um sensor"), e.getMessage());
        }

        @Test
        void sensorInexistente() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PADRAO.semSensor("TEMP-99"));
            assertTrue(e.getMessage().contains("TEMP-99"), e.getMessage());
        }

        @Test
        void removerNaoAlteraAOriginal() {
            PADRAO.semSensor("UMID-01");
            assertEquals(4, PADRAO.sensores().size());
        }
    }
}
