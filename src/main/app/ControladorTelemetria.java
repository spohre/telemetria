package app;

import domain.Alerta;
import domain.AnalisadorDeAnomalias;
import domain.GeradorPasseioAleatorio;
import domain.LeituraTelemetria;
import domain.RepositorioDeAlertas;
import domain.Sensor;
import domain.Severidade;
import domain.TipoSensor;
import infra.FilaDeLeituras;
import infra.LogArquivo;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Controlador real do sistema — liga {@link Sensor}, {@link FilaDeLeituras},
 * {@link ProcessadorDeLeituras}, {@link AnalisadorDeAnomalias}, o repositório de alertas e o
 * {@link LogArquivo}. A interface Swing e o modo console só conhecem
 * {@link ControladorMonitoramento}.
 *
 * <h2>Threads de uma execução</h2>
 * <ul>
 *   <li>{@code sensor-<ID>} — uma por sensor (produtores);</li>
 *   <li>{@code consumidor-<n>} — os {@link ProcessadorDeLeituras};</li>
 *   <li>{@code estatisticas} — {@link ScheduledExecutorService} que publica os contadores a cada 1 s;</li>
 *   <li>{@code log-escritor} — a única que escreve no arquivo de log.</li>
 * </ul>
 *
 * <h2>Encerramento ({@link #parar()}) — sem perda e sem deadlock</h2>
 * <ol>
 *   <li>interrompe os sensores e espera cada um ({@code join});</li>
 *   <li>coloca uma pílula de fim por consumidor na fila e espera os consumidores, que
 *       drenam tudo antes de sair;</li>
 *   <li>desliga o agendador de estatísticas ({@code shutdown} + {@code awaitTermination});</li>
 *   <li>fecha o log (grava o que estava pendente) e monta o extrato.</li>
 * </ol>
 * <p>Os métodos de ciclo de vida são {@code synchronized} neste objeto só para não rodarem
 * dois ao mesmo tempo (ex.: dois cliques). Nenhuma thread de sensor, consumidor ou
 * estatística usa esse lock, então segurá-lo durante o {@code join} não cria deadlock.</p>
 */
public class ControladorTelemetria implements ControladorMonitoramento {

    public static final ConfiguracaoSimulacao CONFIGURACAO_PADRAO = new ConfiguracaoSimulacao(1, 200, 1);

    static final Path CAMINHO_LOG = LogArquivo.CAMINHO_PADRAO;
    /** Sem extrato, uma pausa maior que esta entre duas leituras do log separa duas execuções. */
    static final Duration SEPARACAO_ENTRE_EXECUCOES = Duration.ofSeconds(60);
    private static final long ESPERA_SENSOR_MS = 5_000;
    private static final long ESPERA_CONSUMIDORES_MS = 60_000;
    private static final Map<TipoSensor, String> PREFIXOS = Map.of(
            TipoSensor.TEMPERATURA, "TEMP",
            TipoSensor.VELOCIDADE, "VEL",
            TipoSensor.PRESSAO, "PRES",
            TipoSensor.UMIDADE, "UMID");

    private final RepositorioDeAlertas repositorio;
    private final String descricaoBanco;
    private final Path raiz;
    private final int capacidadeFila;
    private final Clock relogio;
    private final PrintStream console;
    private final AnalisadorDeAnomalias analisador = new AnalisadorDeAnomalias();

    private final List<OuvinteTelemetria> ouvintes = new CopyOnWriteArrayList<>();
    private final OuvinteTelemetria difusor = new Difusor();

    private volatile FaseSistema fase = FaseSistema.PARADO;
    private volatile ConfiguracaoSimulacao configuracao = CONFIGURACAO_PADRAO;

    // Estado de uma execução: criado em iniciar(), lido pelas estatísticas (volatile).
    private volatile Execucao execucao;

    /** Tudo o que pertence a uma execução (de Iniciar a Parar). */
    private record Execucao(Instant inicio, ConfiguracaoSimulacao config, FilaDeLeituras fila,
                            ContadoresExecucao contadores, LogArquivo log, List<Sensor> sensores,
                            List<Thread> threadsSensores, List<Thread> threadsConsumidores,
                            ScheduledExecutorService agendador) {
    }

    /**
     * @param repositorio    onde os alertas são gravados e consultados
     * @param descricaoBanco texto para o extrato (ex.: a URL JDBC)
     * @param raiz           pasta base: o log vai em {@code raiz/data/leituras.log} e os extratos em {@code raiz/data/}
     * @param capacidadeFila capacidade da {@link FilaDeLeituras}
     * @param console        onde imprimir as linhas de console; {@code null} = silencioso
     */
    public ControladorTelemetria(RepositorioDeAlertas repositorio, String descricaoBanco, Path raiz,
                                 int capacidadeFila, PrintStream console, Clock relogio) {
        this.repositorio = Objects.requireNonNull(repositorio, "repositorio");
        this.descricaoBanco = Objects.requireNonNull(descricaoBanco, "descricaoBanco");
        this.raiz = Objects.requireNonNull(raiz, "raiz");
        this.capacidadeFila = capacidadeFila;
        this.relogio = Objects.requireNonNull(relogio, "relogio");
        this.console = console;
        if (console != null) {
            ouvintes.add(new OuvinteConsole(console, relogio));
        }
    }

    public ControladorTelemetria(RepositorioDeAlertas repositorio, String descricaoBanco, Path raiz, PrintStream console) {
        this(repositorio, descricaoBanco, raiz, FilaDeLeituras.CAPACIDADE_PADRAO, console, Clock.systemDefaultZone());
    }

    // ================================================================== ciclo de vida

    @Override
    public synchronized void iniciar() {
        if (fase != FaseSistema.PARADO) {
            throw new IllegalStateException("O sistema já está em execução");
        }
        ConfiguracaoSimulacao cfg = configuracao;
        LogArquivo log;
        try {
            log = new LogArquivo(raiz.resolve(CAMINHO_LOG), FormatoMensagens.CABECALHO_LOG);
        } catch (UncheckedIOException e) {
            difusor.aoOcorrerFalha("Não foi possível abrir o arquivo de log " + raiz.resolve(CAMINHO_LOG) + ".",
                    "Verifique se a pasta data/ existe e se o arquivo não está aberto em outro programa.", e);
            return;
        }

        FilaDeLeituras fila = new FilaDeLeituras(capacidadeFila);
        ContadoresExecucao contadores = new ContadoresExecucao();

        List<Thread> consumidores = new ArrayList<>();
        for (int i = 1; i <= cfg.consumidores(); i++) {
            ProcessadorDeLeituras p = new ProcessadorDeLeituras(fila, analisador, repositorio, log, difusor, contadores);
            consumidores.add(new Thread(p, "consumidor-" + i));
        }

        List<Sensor> sensores = new ArrayList<>();
        List<Thread> threadsSensores = new ArrayList<>();
        for (TipoSensor tipo : TipoSensor.values()) {
            for (int n = 1; n <= cfg.sensoresPorTipo(); n++) {
                String id = String.format("%s-%02d", PREFIXOS.get(tipo), n);
                Sensor s = new Sensor(id, tipo, cfg.intervaloMs(),
                        new GeradorPasseioAleatorio(analisador.regraDo(tipo), new Random()), fila, relogio);
                sensores.add(s);
                threadsSensores.add(new Thread(s, "sensor-" + id));
            }
        }

        ScheduledExecutorService agendador = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "estatisticas");
            t.setDaemon(true);
            return t;
        });

        Execucao ex = new Execucao(relogio.instant(), cfg, fila, contadores, log, List.copyOf(sensores),
                List.copyOf(threadsSensores), List.copyOf(consumidores), agendador);
        execucao = ex;

        imprimir(FormatoMensagens.Nivel.INFO, "sistema", sensores.size() + " sensores e " + cfg.consumidores()
                + " consumidor(es) iniciados (intervalo " + cfg.intervaloMs() + " ms)");
        consumidores.forEach(Thread::start);       // consumidores primeiro: já esperam na fila
        threadsSensores.forEach(Thread::start);
        agendador.scheduleAtFixedRate(this::publicarEstatisticas, 1, 1, TimeUnit.SECONDS);
        fase = FaseSistema.EXECUTANDO;
    }

    @Override
    public synchronized void pausar() {
        if (fase != FaseSistema.EXECUTANDO) {
            return;
        }
        execucao.sensores().forEach(Sensor::pausar);
        fase = FaseSistema.PAUSADO;
        imprimir(FormatoMensagens.Nivel.INFO, "sistema", "Sensores pausados");
    }

    @Override
    public synchronized void retomar() {
        if (fase != FaseSistema.PAUSADO) {
            return;
        }
        execucao.sensores().forEach(Sensor::retomar);
        fase = FaseSistema.EXECUTANDO;
        imprimir(FormatoMensagens.Nivel.INFO, "sistema", "Sensores retomados");
    }

    /**
     * Encerra a execução sem perder leituras e devolve o extrato (também gravado em arquivo).
     *
     * @throws IllegalStateException se o sistema já está parado ou se alguma thread não terminou no tempo limite
     */
    @Override
    public synchronized ExtratoExecucao parar() {
        Execucao ex = execucao;
        if (fase == FaseSistema.PARADO || ex == null) {
            throw new IllegalStateException("O sistema não está em execução");
        }
        // Todos os passos são tentados mesmo se algum falhar, para não deixar log aberto nem
        // consumidores esperando; o primeiro problema é reportado no fim.
        String problema = null;
        try {
            // 1. sensores: ninguém mais publica
            ex.threadsSensores().forEach(Thread::interrupt);
            for (Thread t : ex.threadsSensores()) {
                problema = primeiroProblema(problema, aguardar(t, ESPERA_SENSOR_MS));
            }
            // 2. uma pílula por consumidor VIVO, atrás de todas as leituras; eles drenam a fila e saem
            int consumidoresVivos = (int) ex.threadsConsumidores().stream().filter(Thread::isAlive).count();
            ex.fila().sinalizarFim(consumidoresVivos);
            for (Thread t : ex.threadsConsumidores()) {
                problema = primeiroProblema(problema, aguardar(t, ESPERA_CONSUMIDORES_MS));
            }
            // 3. estatísticas
            ex.agendador().shutdown();
            ex.agendador().awaitTermination(2, TimeUnit.SECONDS);
            // 4. log: grava o que estava pendente e fecha o arquivo
            ex.log().fechar();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Encerramento interrompido antes de terminar", e);
        } finally {
            fase = FaseSistema.PARADO;
        }
        if (problema != null) {
            throw new IllegalStateException(problema);
        }

        publicarEstatisticas(ex);   // números finais na barra de status
        ExtratoExecucao extrato = new ExtratoExecucao(ex.inicio(), relogio.instant(), ex.config(),
                ex.fila().publicadas(), ex.contadores().processadas(), ex.contadores().alertasPorTipo(),
                ex.contadores().alertasSalvos(), raiz.resolve(CAMINHO_LOG).toString(), descricaoBanco);

        String caminhoExtrato;
        try {
            caminhoExtrato = exportarExtrato(extrato);
        } catch (UncheckedIOException e) {
            caminhoExtrato = "(não gravado: " + e.getCause().getMessage() + ")";
            imprimir(FormatoMensagens.Nivel.AVISO, "sistema", "Extrato não pôde ser gravado em arquivo");
        }
        imprimir(FormatoMensagens.Nivel.INFO, "sistema", "Encerrado: "
                + FormatoMensagens.inteiro(extrato.leiturasProcessadas()) + " leituras, "
                + FormatoMensagens.inteiro(extrato.leiturasPerdidas()) + " perdidas, "
                + FormatoMensagens.inteiro(extrato.totalAlertas()) + " alertas. Extrato: " + caminhoExtrato);
        return extrato;
    }

    /** Espera a thread terminar; devolve a descrição do problema se ela não terminou no tempo limite. */
    private String aguardar(Thread t, long ms) throws InterruptedException {
        t.join(ms);
        if (t.isAlive()) {
            imprimir(FormatoMensagens.Nivel.ERRO, "sistema", "A thread " + t.getName() + " não terminou em " + ms + " ms");
            return "A thread " + t.getName() + " não terminou dentro do tempo limite";
        }
        return null;
    }

    private static String primeiroProblema(String atual, String novo) {
        return atual != null ? atual : novo;
    }

    @Override
    public FaseSistema fase() {
        return fase;
    }

    @Override
    public void registrarOuvinte(OuvinteTelemetria ouvinte) {
        ouvintes.add(Objects.requireNonNull(ouvinte, "ouvinte"));
    }

    // ================================================================== estatísticas

    private void publicarEstatisticas() {
        Execucao ex = execucao;
        if (ex != null) {
            publicarEstatisticas(ex);
        }
    }

    private void publicarEstatisticas(Execucao ex) {
        difusor.aoAtualizarEstatisticas(estatisticas(ex));
    }

    /** Fotografia dos contadores da execução atual (ou da última, se já parou). */
    public EstatisticasExecucao estatisticas() {
        Execucao ex = execucao;
        return ex == null ? EstatisticasExecucao.vazia(capacidadeFila) : estatisticas(ex);
    }

    private EstatisticasExecucao estatisticas(Execucao ex) {
        int vivas = 0;
        for (Thread t : ex.threadsSensores()) {
            vivas += t.isAlive() ? 1 : 0;
        }
        for (Thread t : ex.threadsConsumidores()) {
            vivas += t.isAlive() ? 1 : 0;
        }
        return new EstatisticasExecucao(ex.fila().publicadas(), ex.contadores().processadas(),
                ex.fila().tamanho(), ex.fila().capacidade(), ex.contadores().alertasGerados(), vivas,
                ex.contadores().bancoDisponivel(),
                Duration.between(ex.inicio(), relogio.instant()).toSeconds());
    }

    /** Vezes em que um sensor encontrou a fila cheia e esperou (execução atual ou última). */
    public long esperasNaFila() {
        Execucao ex = execucao;
        return ex == null ? 0 : ex.fila().esperasPorEspaco();
    }

    // ================================================================== consultas e configuração

    @Override
    public List<Alerta> buscarAlertas(FiltroAlertas filtro) {
        Instant desde = filtro.periodo() == null ? null : relogio.instant().minus(filtro.periodo());
        String sensor = filtro.sensorId() == null || filtro.sensorId().isBlank() ? null : filtro.sensorId().trim();
        return repositorio.buscar(sensor, filtro.tipo(), filtro.severidade(), desde);
    }

    @Override
    public List<String> sensoresNoHistorico() {
        return repositorio.sensoresComAlertas();
    }

    /**
     * Regras para a aba "Regras e sensores": faixa aceita e a escala de severidade
     * (quanto a leitura passou do limite). A severidade "principal" é a mínima, BAIXA.
     */
    @Override
    public List<RegraExibida> regras() {
        return analisador.regras().stream()
                .map(r -> new RegraExibida(r.tipo(), r.minimo(), r.maximo(), Severidade.BAIXA,
                        analisador.escala(r.tipo())))
                .toList();
    }

    @Override
    public ConfiguracaoSimulacao configuracao() {
        return configuracao;
    }

    @Override
    public synchronized void aplicarConfiguracao(ConfiguracaoSimulacao nova) {
        if (fase != FaseSistema.PARADO) {
            throw new IllegalStateException("Só é possível alterar a simulação com o sistema parado");
        }
        configuracao = Objects.requireNonNull(nova, "nova");
    }

    /** Grava {@code raiz/data/extrato-AAAAMMDD-HHmmss.txt} (UTF-8) e devolve o caminho. */
    @Override
    public String exportarExtrato(ExtratoExecucao extrato) {
        Path arquivo = raiz.resolve(FormatoMensagens.nomeArquivoExtrato(extrato.fim()));
        try {
            Files.createDirectories(arquivo.getParent());
            Files.writeString(arquivo, FormatoMensagens.extrato(extrato), StandardCharsets.UTF_8);
            return arquivo.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível gravar o extrato em " + arquivo, e);
        }
    }

    /**
     * Leituras da última execução, lidas de {@code data/leituras.log}.
     *
     * <p>O log acumula todas as execuções. A janela da última vem do extrato mais recente
     * (início na linha "Período", fim no nome do arquivo). Se não houver extrato, ou se o
     * log tiver leituras depois dele (execução interrompida sem Parar), vale o último bloco
     * de leituras sem pausa maior que {@link #SEPARACAO_ENTRE_EXECUCOES}.</p>
     *
     * @throws UncheckedIOException se o log existe mas não pôde ser lido
     */
    @Override
    public HistoricoExecucao ultimaExecucao() {
        Path log = raiz.resolve(CAMINHO_LOG);
        if (!Files.isRegularFile(log)) {
            return HistoricoExecucao.VAZIO;
        }
        Optional<Instant[]> janela = janelaDoUltimoExtrato();
        Instant de = janela.map(j -> j[0].minusSeconds(1)).orElse(null);
        Instant ate = janela.map(j -> j[1].plusSeconds(1)).orElse(null);

        List<LeituraRegistrada> naJanela = new ArrayList<>();
        List<LeituraRegistrada> ultimoBloco = new ArrayList<>();
        Instant anterior = null;
        Instant maisRecente = null;
        try (Stream<String> linhas = Files.lines(log, StandardCharsets.UTF_8)) {
            for (String linha : (Iterable<String>) linhas::iterator) {
                Optional<LeituraRegistrada> lida = FormatoMensagens.lerLinhaLog(linha);
                if (lida.isEmpty()) {
                    continue;
                }
                LeituraRegistrada r = lida.get();
                Instant m = r.leitura().momento();
                if (anterior != null && Duration.between(anterior, m).compareTo(SEPARACAO_ENTRE_EXECUCOES) > 0) {
                    ultimoBloco.clear();
                }
                ultimoBloco.add(r);
                anterior = m;
                maisRecente = maisRecente == null || m.isAfter(maisRecente) ? m : maisRecente;
                if (de != null && !m.isBefore(de) && !m.isAfter(ate)) {
                    naJanela.add(r);
                }
            }
        } catch (IOException | UncheckedIOException e) {
            IOException causa = e instanceof IOException io ? io : ((UncheckedIOException) e).getCause();
            throw new UncheckedIOException("Não foi possível ler o log " + log, causa);
        }
        boolean extratoEhOUltimo = ate != null && !naJanela.isEmpty() && !maisRecente.isAfter(ate);
        return HistoricoExecucao.de(extratoEhOUltimo ? naJanela : ultimoBloco);
    }

    /** [início, fim] do extrato mais recente em {@code raiz/data}, se houver um legível. */
    private Optional<Instant[]> janelaDoUltimoExtrato() {
        Path pasta = raiz.resolve(CAMINHO_LOG).getParent();
        if (pasta == null || !Files.isDirectory(pasta)) {
            return Optional.empty();
        }
        try (Stream<Path> arquivos = Files.list(pasta)) {
            Optional<Path> ultimo = arquivos
                    .filter(p -> FormatoMensagens.fimDoArquivoExtrato(p.getFileName().toString()).isPresent())
                    .max((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
            if (ultimo.isEmpty()) {
                return Optional.empty();
            }
            Instant fim = FormatoMensagens.fimDoArquivoExtrato(ultimo.get().getFileName().toString()).orElseThrow();
            Optional<Instant> inicio = FormatoMensagens.inicioDoExtrato(
                    Files.readString(ultimo.get(), StandardCharsets.UTF_8));
            return inicio.map(i -> new Instant[]{i, fim});
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();     // sem extrato legível: usa a separação por pausa
        }
    }

    // ================================================================== auxiliares

    private void imprimir(FormatoMensagens.Nivel nivel, String origem, String mensagem) {
        if (console != null) {
            console.println(FormatoMensagens.console(relogio.instant(), nivel, origem, mensagem));
        }
    }

    /** Repassa cada evento a todos os ouvintes; um ouvinte com defeito não atrapalha os outros. */
    private final class Difusor implements OuvinteTelemetria {

        @Override
        public void aoProcessarLeitura(LeituraTelemetria leitura, Alerta alerta) {
            for (OuvinteTelemetria o : ouvintes) {
                try {
                    o.aoProcessarLeitura(leitura, alerta);
                } catch (RuntimeException e) {
                    imprimir(FormatoMensagens.Nivel.ERRO, "sistema", "Ouvinte falhou: " + e);
                }
            }
        }

        @Override
        public void aoAtualizarEstatisticas(EstatisticasExecucao estatisticas) {
            for (OuvinteTelemetria o : ouvintes) {
                try {
                    o.aoAtualizarEstatisticas(estatisticas);
                } catch (RuntimeException e) {
                    imprimir(FormatoMensagens.Nivel.ERRO, "sistema", "Ouvinte falhou: " + e);
                }
            }
        }

        @Override
        public void aoOcorrerFalha(String oQueAconteceu, String oQueFazer, Throwable causa) {
            for (OuvinteTelemetria o : ouvintes) {
                try {
                    o.aoOcorrerFalha(oQueAconteceu, oQueFazer, causa);
                } catch (RuntimeException e) {
                    imprimir(FormatoMensagens.Nivel.ERRO, "sistema", "Ouvinte falhou: " + e);
                }
            }
        }
    }
}
