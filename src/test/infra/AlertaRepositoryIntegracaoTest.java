package infra;

import domain.Alerta;
import domain.LeituraTelemetria;
import domain.Severidade;
import domain.TipoSensor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de INTEGRAÇÃO do AlertaRepository (usam banco real H2 em memória).
 *
 * <p>Ficam separados dos testes UNITÁRIOS do AnalisadorDeAnomalias, que não devem
 * depender de banco nem de threads — como pede a especificação.
 * Rodar só estes: {@code mvn test -Dgroups=integracao}.</p>
 */
@Tag("integracao")
class AlertaRepositoryIntegracaoTest {

    private AlertaRepository repositorio;

    @BeforeEach
    void prepararBancoLimpo() {
        // Banco novo e isolado por teste; DB_CLOSE_DELAY mantém vivo entre conexões.
        String url = "jdbc:h2:mem:teste_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        FabricaDeConexoes fabrica = new FabricaDeConexoes(url, "sa", "");
        new InicializadorBanco(fabrica).criarEstrutura();
        repositorio = new AlertaRepository(fabrica);
    }

    private static Alerta alerta(String sensor, TipoSensor tipo, Severidade severidade, Instant momento) {
        var leitura = new LeituraTelemetria(sensor, tipo, 100, momento);
        return Alerta.de(leitura, tipo + " fora do limite", severidade);
    }

    /** Base fixa para as buscas: T0, T0+1min, T0+2min... */
    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    private void povoarHistorico() {
        repositorio.salvar(alerta("TEMP-01", TipoSensor.TEMPERATURA, Severidade.BAIXA, T0));
        repositorio.salvar(alerta("TEMP-01", TipoSensor.TEMPERATURA, Severidade.ALTA, T0.plusSeconds(60)));
        repositorio.salvar(alerta("TEMP-02", TipoSensor.TEMPERATURA, Severidade.ALTA, T0.plusSeconds(120)));
        repositorio.salvar(alerta("VEL-01", TipoSensor.VELOCIDADE, Severidade.CRITICA, T0.plusSeconds(180)));
        repositorio.salvar(alerta("PRES-01", TipoSensor.PRESSAO, Severidade.MEDIA, T0.plusSeconds(240)));
    }

    private static Alerta alerta(String sensor, TipoSensor tipo, double valor) {
        var leitura = new LeituraTelemetria(sensor, tipo, valor, Instant.now().truncatedTo(ChronoUnit.MILLIS));
        return Alerta.de(leitura, tipo + " fora do limite", Severidade.ALTA);
    }

    @Test
    @DisplayName("salvar devolve o alerta com id gerado e ele pode ser lido de volta")
    void salvarERecuperar() {
        Alerta original = alerta("TEMP-01", TipoSensor.TEMPERATURA, 95.3);

        Alerta salvo = repositorio.salvar(original);

        assertNotNull(salvo.id());
        Alerta lido = repositorio.buscarPorId(salvo.id()).orElseThrow();
        assertEquals(salvo, lido, "todos os campos devem sobreviver à ida e volta ao banco");
    }

    @Test
    @DisplayName("não permite salvar novamente um alerta que já tem id")
    void rejeitaAlertaJaPersistido() {
        Alerta salvo = repositorio.salvar(alerta("VEL-01", TipoSensor.VELOCIDADE, 210));
        assertThrows(IllegalArgumentException.class, () -> repositorio.salvar(salvo));
    }

    @Test
    @DisplayName("buscarPorSensor filtra corretamente")
    void buscarPorSensor() {
        repositorio.salvar(alerta("TEMP-01", TipoSensor.TEMPERATURA, 90));
        repositorio.salvar(alerta("TEMP-01", TipoSensor.TEMPERATURA, 99));
        repositorio.salvar(alerta("PRES-01", TipoSensor.PRESSAO, 7.5));

        List<Alerta> doSensor = repositorio.buscarPorSensor("TEMP-01");

        assertEquals(2, doSensor.size());
        assertTrue(doSensor.stream().allMatch(a -> a.sensorId().equals("TEMP-01")));
    }

    @Test
    @DisplayName("valores com aspas não quebram o SQL (PreparedStatement evita injection)")
    void sqlInjectionNaoAfetaBanco() {
        String sensorMalicioso = "X'; DROP TABLE alerta; --";
        repositorio.salvar(alerta(sensorMalicioso, TipoSensor.UMIDADE, 120));

        assertEquals(1, repositorio.buscarPorSensor(sensorMalicioso).size());
        assertEquals(1, repositorio.contar(), "a tabela continua existindo");
    }

    @Test
    @DisplayName("salvarEmLote grava todos e contarPorTipo agrega")
    void salvarEmLote() {
        List<Alerta> lote = List.of(
                alerta("TEMP-01", TipoSensor.TEMPERATURA, 85),
                alerta("TEMP-02", TipoSensor.TEMPERATURA, 88),
                alerta("VEL-01", TipoSensor.VELOCIDADE, 250));

        assertEquals(3, repositorio.salvarEmLote(lote));
        Map<TipoSensor, Long> porTipo = repositorio.contarPorTipo();
        assertEquals(2L, porTipo.get(TipoSensor.TEMPERATURA).longValue());
        assertEquals(1L, porTipo.get(TipoSensor.VELOCIDADE).longValue());
    }

    @Test
    @DisplayName("concorrência: 8 consumidores × 100 alertas = 800 linhas, sem perda nem exceção")
    void gravacaoConcorrenteNaoPerdeAlertas() throws Exception {
        int threads = 8;
        int porThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);      // todas começam juntas → máxima disputa
        List<Throwable> erros = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            String sensor = "SENSOR-" + t;
            pool.submit(() -> {
                try {
                    largada.await();
                    for (int i = 0; i < porThread; i++) {
                        repositorio.salvar(alerta(sensor, TipoSensor.PRESSAO, 10 + i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable e) {
                    erros.add(e);
                }
            });
        }
        largada.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "possível deadlock: threads não terminaram");

        assertTrue(erros.isEmpty(), () -> "erros durante gravação concorrente: " + erros);
        assertEquals((long) threads * porThread, repositorio.contar());
    }

    // ------------------------------------------------------------ buscar (aba Histórico)

    @Test
    @DisplayName("buscar sem filtros devolve tudo, mais recente primeiro")
    void buscarSemFiltros() {
        povoarHistorico();

        List<Alerta> todos = repositorio.buscar(null, null, null, null);

        assertEquals(5, todos.size());
        assertEquals(List.of("PRES-01", "VEL-01", "TEMP-02", "TEMP-01", "TEMP-01"),
                todos.stream().map(Alerta::sensorId).toList());
    }

    @Test
    @DisplayName("buscar combina sensor, tipo e severidade")
    void buscarComFiltrosCombinados() {
        povoarHistorico();

        assertEquals(2, repositorio.buscar("TEMP-01", null, null, null).size());
        assertEquals(3, repositorio.buscar(null, TipoSensor.TEMPERATURA, null, null).size());
        assertEquals(2, repositorio.buscar(null, TipoSensor.TEMPERATURA, Severidade.ALTA, null).size());
        List<Alerta> um = repositorio.buscar("TEMP-01", TipoSensor.TEMPERATURA, Severidade.ALTA, null);
        assertEquals(1, um.size());
        assertEquals(T0.plusSeconds(60), um.get(0).momentoLeitura());
        assertTrue(repositorio.buscar("VEL-01", TipoSensor.TEMPERATURA, null, null).isEmpty());
    }

    @Test
    @DisplayName("buscar por período usa o momento da leitura (inclusivo)")
    void buscarDesde() {
        povoarHistorico();

        List<Alerta> recentes = repositorio.buscar(null, null, null, T0.plusSeconds(180));

        assertEquals(List.of("PRES-01", "VEL-01"), recentes.stream().map(Alerta::sensorId).toList());
    }

    @Test
    @DisplayName("sensor em branco não filtra e o limite corta as mais antigas")
    void buscarSensorEmBrancoELimite() {
        povoarHistorico();

        assertEquals(5, repositorio.buscar("   ", null, null, null).size());
        List<Alerta> duas = repositorio.buscar(null, null, null, null, 2);
        assertEquals(List.of("PRES-01", "VEL-01"), duas.stream().map(Alerta::sensorId).toList());
    }

    @Test
    @DisplayName("filtro com aspas não quebra o SQL da busca")
    void buscarComTextoMalicioso() {
        povoarHistorico();

        assertTrue(repositorio.buscar("X' OR '1'='1", null, null, null).isEmpty());
        assertEquals(5, repositorio.contar());
    }
}
