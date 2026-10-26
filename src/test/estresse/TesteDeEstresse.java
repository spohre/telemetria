package estresse;

import static org.junit.jupiter.api.Assertions.*;

import app.ConfiguracaoSimulacao;
import app.ControladorTelemetria;
import app.EstatisticasExecucao;
import app.ExtratoExecucao;
import app.OuvinteTelemetria;
import domain.Alerta;
import domain.LeituraTelemetria;
import domain.RepositorioDeAlertas;
import domain.Severidade;
import domain.TipoSensor;
import infra.AlertaRepository;
import infra.FabricaDeConexoes;
import infra.InicializadorBanco;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

/**
 * Semana 5 — concorrência sob estresse, com o sistema REAL (ControladorTelemetria, sensores,
 * fila, 4 consumidores, log em arquivo e H2 em arquivo), encerrado pelo parar() de verdade.
 *
 * <p>Fila com capacidade 50 para forçar os sensores a esperar (contrapressão). Cada rodada
 * confere: zero perdidas, nenhuma (sensor, sequência) repetida ou faltando, uma linha de
 * log por leitura, alertas no banco == alertas gerados, e tudo dentro do tempo limite
 * (se travar, é deadlock).</p>
 *
 * <p>Fora do {@code mvn test} padrão. Rodar: {@code mvn test -Dgroups=estresse}</p>
 */
@Tag("estresse")
class TesteDeEstresse {

    private static final int CAPACIDADE_FILA = 50;
    private static final Duration DURACAO = Duration.ofSeconds(5);
    private static final Duration LIMITE = Duration.ofSeconds(60);

    @TempDir
    Path raiz;

    @RepeatedTest(value = 5, name = "32 sensores · 10 ms · 4 consumidores · fila 50 — rodada {currentRepetition}/5")
    void trintaEDoisSensores(RepetitionInfo rodada) {
        executar(new ConfiguracaoSimulacao(8, 10, 4), "32 sensores", rodada.getCurrentRepetition());
    }

    @RepeatedTest(value = 5, name = "40 sensores · 10 ms · 4 consumidores · fila 50 — rodada {currentRepetition}/5")
    void cargaMaxima(RepetitionInfo rodada) {
        executar(new ConfiguracaoSimulacao(10, 10, 4), "40 sensores", rodada.getCurrentRepetition());
    }

    /**
     * Banco lento (atraso artificial de 30 ms por gravação, sobre o H2 real): os consumidores
     * não acompanham, a fila de 50 enche e os sensores PRECISAM esperar — contrapressão real.
     */
    @RepeatedTest(value = 5, name = "40 sensores · banco lento (+30 ms/alerta) · fila 50 — rodada {currentRepetition}/5")
    void bancoLentoForcaFilaCheia(RepetitionInfo rodada) {
        long esperas = executar(new ConfiguracaoSimulacao(10, 10, 4), "banco lento", rodada.getCurrentRepetition(),
                ATRASO_BANCO_LENTO_MS);
        assertTrue(esperas > 0, "a fila deveria ter enchido e feito sensores esperarem");
    }

    private static final long ATRASO_BANCO_LENTO_MS = 30;

    private long executar(ConfiguracaoSimulacao cfg, String nome, int numero) {
        return executar(cfg, nome, numero, 0);
    }

    private long executar(ConfiguracaoSimulacao cfg, String nome, int numero, long atrasoBancoMs) {
        FabricaDeConexoes fabrica = new FabricaDeConexoes(
                "jdbc:h2:" + raiz.resolve("telemetria").toAbsolutePath() + ";DB_CLOSE_DELAY=-1", "sa", "");  // como em db.properties
        new InicializadorBanco(fabrica).criarEstrutura();
        AlertaRepository repositorio = new AlertaRepository(fabrica);
        RepositorioDeAlertas usado = atrasoBancoMs == 0 ? repositorio : new BancoLento(repositorio, atrasoBancoMs);
        ControladorTelemetria controlador = new ControladorTelemetria(usado, fabrica.url(), raiz,
                CAPACIDADE_FILA, null, Clock.systemDefaultZone());
        controlador.aplicarConfiguracao(cfg);
        ColetorDeSequencias coletor = new ColetorDeSequencias();
        controlador.registrarOuvinte(coletor);

        ExtratoExecucao extrato = assertTimeoutPreemptively(LIMITE, () -> {
            controlador.iniciar();
            Thread.sleep(DURACAO.toMillis());
            return controlador.parar();
        }, "o sistema não terminou dentro do limite: possível deadlock");

        // ---- 1. zero perdidas
        assertEquals(extrato.leiturasProduzidas(), extrato.leiturasProcessadas(), "produzidas == processadas");
        assertEquals(0, extrato.leiturasPerdidas());

        // ---- 2. cada (sensor, sequência) processado exatamente uma vez, sem buraco
        assertEquals(0, coletor.duplicadas.get(), "leituras processadas mais de uma vez");
        int sensores = cfg.sensoresPorTipo() * 4;
        assertEquals(sensores, coletor.porSensor.size(), "todos os sensores foram processados");
        long totalColetado = 0;
        for (Map.Entry<String, Set<Long>> e : coletor.porSensor.entrySet()) {
            long maior = e.getValue().stream().mapToLong(Long::longValue).max().orElse(0);
            assertEquals(maior, e.getValue().size(), e.getKey() + ": sequência com buraco entre 1 e " + maior);
            totalColetado += e.getValue().size();
        }
        assertEquals(extrato.leiturasProcessadas(), totalColetado);

        // ---- 3. log: uma linha por leitura processada (todas, normais e anômalas)
        List<String> log = lerLog();
        assertEquals(extrato.leiturasProcessadas() + 1, log.size(), "linhas do log == processadas + cabeçalho");
        long anomalasNoLog = log.stream().skip(1).filter(l -> l.contains(";ANOMALA;")).count();

        // ---- 4. banco: todo alerta gerado foi gravado
        assertEquals(extrato.totalAlertas(), extrato.alertasGravados(), "alertas gravados == gerados");
        assertEquals(extrato.totalAlertas(), repositorio.contar(), "linhas na tabela alerta == alertas gerados");
        assertEquals(extrato.totalAlertas(), anomalasNoLog, "linhas ANOMALA no log == alertas");
        assertEquals(0, coletor.falhas.get(), "nenhuma falha durante a execução");

        long esperas = controlador.esperasNaFila();
        System.out.printf("ESTRESSE | %-12s | rodada %d/5 | produzidas %,7d | processadas %,7d | perdidas %d | "
                        + "alertas %,5d (banco %,5d) | sensor esperou fila cheia %,6d vezes | %,d leituras/s%n",
                nome, numero, extrato.leiturasProduzidas(), extrato.leiturasProcessadas(), extrato.leiturasPerdidas(),
                extrato.totalAlertas(), repositorio.contar(), esperas,
                extrato.leiturasProcessadas() / Math.max(1, Duration.between(extrato.inicio(), extrato.fim()).toSeconds()));
        return esperas;
    }

    /** Repositório real com atraso em cada gravação (simula um servidor de banco distante). */
    private record BancoLento(RepositorioDeAlertas real, long atrasoMs) implements RepositorioDeAlertas {
        @Override
        public Alerta salvar(Alerta alerta) {
            try {
                Thread.sleep(atrasoMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return real.salvar(alerta);
        }

        @Override
        public List<Alerta> buscar(String sensorId, TipoSensor tipo, Severidade severidade, Instant desde) {
            return real.buscar(sensorId, tipo, severidade, desde);
        }
    }

    /** Ouvinte que registra cada (sensor, sequência) processado; chamado pelos 4 consumidores. */
    private static final class ColetorDeSequencias implements OuvinteTelemetria {
        final Map<String, Set<Long>> porSensor = new ConcurrentHashMap<>();
        final AtomicLong duplicadas = new AtomicLong();

        @Override
        public void aoProcessarLeitura(LeituraTelemetria leitura, Alerta alerta) {
            boolean nova = porSensor.computeIfAbsent(leitura.sensorId(), k -> ConcurrentHashMap.newKeySet())
                    .add(leitura.sequencia());
            if (!nova) {
                duplicadas.incrementAndGet();
            }
        }

        @Override
        public void aoAtualizarEstatisticas(EstatisticasExecucao estatisticas) {
        }

        @Override
        public void aoOcorrerFalha(String oQueAconteceu, String oQueFazer, Throwable causa) {
            falhas.incrementAndGet();
        }

        final AtomicLong falhas = new AtomicLong();
    }

    private List<String> lerLog() {
        try {
            return Files.readAllLines(raiz.resolve("data/leituras.log"), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
