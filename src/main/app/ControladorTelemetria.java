package app;

import domain.Alerta;
import domain.AnalisadorDeAnomalias;
import domain.GeradorPorEventosDiscretos;
import domain.LeituraTelemetria;
import domain.ParametrosDeEpisodios;
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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>Os valores de cada sensor vêm de um {@link GeradorPorEventosDiscretos} próprio (com
 * {@code new Random()}: cada execução é diferente), configurado por {@link ParametrosDeEpisodios}.</p>
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
    private volatile ParametrosDeEpisodios parametrosEpisodios = ParametrosDeEpisodios.padrao();

    // Estado de uma execução: criado em iniciar(), lido pelas estatísticas (volatile).
    private volatile Execucao execucao;

    /** Tudo o que pertence a uma execução (de Iniciar a Parar). */
    /**
     * @param sensores        todos os sensores da execução, por id (fixo depois de criado)
     * @param threadsSensores thread ATUAL de cada sensor. Muda quando um sensor é desligado e
     *                        ligado de novo (nova thread); escrito só sob o lock deste controlador
     *                        e lido também pelas estatísticas, por isso ConcurrentHashMap.
     */
    private record Execucao(Instant inicio, ConfiguracaoSimulacao config, FilaDeLeituras fila,
                            ContadoresExecucao contadores, LogArquivo log, Map<String, Sensor> sensores,
                            Map<String, Thread> threadsSensores, List<Thread> threadsConsumidores,
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

    /**
     * Liga todos os sensores. Com o sistema PARADO, sobe a execução inteira (fila, consumidores,
     * log, estatísticas). Já EM EXECUÇÃO (ex.: começou por um sensor só), liga os que estão
     * desligados.
     *
     * @throws IllegalStateException se o sistema está pausado ou se todos já estão ligados
     */
    @Override
    public synchronized void iniciar() {
        if (fase == FaseSistema.PARADO) {
            iniciarExecucao(id -> true);
            return;
        }
        Execucao ex = execucao;
        List<String> desligados = fase == FaseSistema.EXECUTANDO && ex != null
                ? ex.sensores().keySet().stream().filter(id -> !ex.threadsSensores().get(id).isAlive()).toList()
                : List.of();
        if (desligados.isEmpty()) {
            throw new IllegalStateException(fase == FaseSistema.PAUSADO
                    ? "O sistema está pausado: clique em Retomar" : "O sistema já está em execução");
        }
        desligados.forEach(id -> ligarThread(ex, id));
        imprimir(FormatoMensagens.Nivel.INFO, "sistema", desligados.size() + " sensor(es) ligado(s): "
                + String.join(", ", desligados));
    }

    /**
     * Sobe uma execução: cria fila, consumidores, log, estatísticas e TODOS os sensores da
     * configuração, mas só inicia a thread dos sensores aceitos por {@code ligar}. Os demais
     * ficam com uma thread ainda não iniciada (estado DESLIGADO) e podem ser ligados depois.
     *
     * @return {@code false} se o log não pôde ser aberto (o motivo já foi avisado ao ouvinte)
     */
    private boolean iniciarExecucao(java.util.function.Predicate<String> ligar) {
        ConfiguracaoSimulacao cfg = configuracao;
        ParametrosDeEpisodios episodios = parametrosEpisodios;
        LogArquivo log;
        try {
            log = new LogArquivo(raiz.resolve(CAMINHO_LOG), FormatoMensagens.CABECALHO_LOG);
        } catch (UncheckedIOException e) {
            difusor.aoOcorrerFalha("Não foi possível abrir o arquivo de log " + raiz.resolve(CAMINHO_LOG) + ".",
                    "Verifique se a pasta data/ existe e se o arquivo não está aberto em outro programa.", e);
            return false;
        }

        FilaDeLeituras fila = new FilaDeLeituras(capacidadeFila);
        ContadoresExecucao contadores = new ContadoresExecucao();

        List<Thread> consumidores = new ArrayList<>();
        for (int i = 1; i <= cfg.consumidores(); i++) {
            ProcessadorDeLeituras p = new ProcessadorDeLeituras(fila, analisador, repositorio, log, difusor, contadores);
            consumidores.add(new Thread(p, "consumidor-" + i));
        }

        Map<String, Sensor> sensores = new TreeMap<>();                    // ordem de id
        Map<String, Thread> threadsSensores = new ConcurrentHashMap<>();
        sensoresDaConfiguracao(cfg).forEach((id, tipo) -> {
            Sensor s = new Sensor(id, tipo, cfg.intervaloMs(),
                    GeradorPorEventosDiscretos.para(analisador, tipo, episodios, new Random()), fila, relogio);
            sensores.put(id, s);
            threadsSensores.put(id, new Thread(s, "sensor-" + id));
        });

        ScheduledExecutorService agendador = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "estatisticas");
            t.setDaemon(true);
            return t;
        });

        Execucao ex = new Execucao(relogio.instant(), cfg, fila, contadores, log,
                java.util.Collections.unmodifiableMap(sensores), threadsSensores, List.copyOf(consumidores), agendador);
        execucao = ex;

        List<String> ligados = sensores.keySet().stream().filter(ligar).toList();
        String quantos = ligados.size() == sensores.size() ? sensores.size() + " sensores"
                : ligados.size() + " de " + sensores.size() + " sensores";
        imprimir(FormatoMensagens.Nivel.INFO, "sistema", quantos + " e " + cfg.consumidores()
                + " consumidor(es) iniciados (intervalo " + cfg.intervaloMs() + " ms)");
        imprimir(FormatoMensagens.Nivel.INFO, "sistema", FormatoMensagens.resumoEpisodios(episodios, cfg.intervaloMs()));
        consumidores.forEach(Thread::start);       // consumidores primeiro: já esperam na fila
        ligados.forEach(id -> threadsSensores.get(id).start());
        agendador.scheduleAtFixedRate(this::publicarEstatisticas, 1, 1, TimeUnit.SECONDS);
        fase = FaseSistema.EXECUTANDO;
        return true;
    }

    /** Ids e tipos dos sensores que uma configuração cria (TEMP-01, TEMP-02…, VEL-01…), em ordem de id. */
    static Map<String, TipoSensor> sensoresDaConfiguracao(ConfiguracaoSimulacao cfg) {
        Map<String, TipoSensor> r = new TreeMap<>();
        for (TipoSensor tipo : TipoSensor.values()) {
            for (int n = 1; n <= cfg.sensoresPorTipo(); n++) {
                r.put(String.format("%s-%02d", PREFIXOS.get(tipo), n), tipo);
            }
        }
        return r;
    }

    /** Thread nova para o sensor (a anterior, se houve, já terminou ou nunca começou). Só sob o lock deste objeto. */
    private void ligarThread(Execucao ex, String sensorId) {
        Sensor s = ex.sensores().get(sensorId);
        s.retomar();                       // se foi desligado durante uma pausa, volta produzindo
        Thread t = new Thread(s, "sensor-" + sensorId);
        ex.threadsSensores().put(sensorId, t);
        t.start();
    }

    @Override
    public synchronized void pausar() {
        if (fase != FaseSistema.EXECUTANDO) {
            return;
        }
        execucao.sensores().values().forEach(Sensor::pausar);
        fase = FaseSistema.PAUSADO;
        imprimir(FormatoMensagens.Nivel.INFO, "sistema", "Sensores pausados");
    }

    @Override
    public synchronized void retomar() {
        if (fase != FaseSistema.PAUSADO) {
            return;
        }
        execucao.sensores().values().forEach(Sensor::retomar);
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
            ex.threadsSensores().values().forEach(Thread::interrupt);
            for (Thread t : ex.threadsSensores().values()) {
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

    // ================================================================== controle de um sensor

    /**
     * Liga um sensor desligado numa thread nova ({@code sensor-<ID>}). Com o sistema PARADO,
     * primeiro sobe a execução (fila, consumidores, log) com SÓ este sensor ligado. O objeto {@link Sensor} é o
     * mesmo, então a sequência continua de onde parou (sem repetir nem pular). Seguro porque a
     * thread anterior já terminou: {@link #desligarSensor} espera com {@code join}, e
     * {@code join} → {@code start} garante que a thread nova enxerga o estado deixado pela antiga.
     */
    @Override
    public synchronized void iniciarSensor(String sensorId) {
        if (fase == FaseSistema.PARADO) {
            // sistema todo parado: sobe a execução (fila, consumidores, log) só com este sensor
            if (!sensoresDaConfiguracao(configuracao).containsKey(sensorId)) {
                throw new IllegalArgumentException("Sensor desconhecido: " + sensorId);
            }
            if (iniciarExecucao(sensorId::equals)) {
                imprimir(FormatoMensagens.Nivel.INFO, sensorId, "Sensor ligado (os demais continuam desligados)");
            }
            return;
        }
        Execucao ex = execucaoEmAndamento();
        sensor(ex, sensorId);
        if (ex.threadsSensores().get(sensorId).isAlive()) {
            throw new IllegalStateException("O sensor " + sensorId + " já está ligado");
        }
        ligarThread(ex, sensorId);
        imprimir(FormatoMensagens.Nivel.INFO, sensorId, "Sensor ligado");
    }

    @Override
    public synchronized void pausarSensor(String sensorId) {
        Sensor s = sensorLigado(sensorId);
        s.pausar();
        imprimir(FormatoMensagens.Nivel.INFO, sensorId, "Sensor pausado");
    }

    @Override
    public synchronized void retomarSensor(String sensorId) {
        Sensor s = sensorLigado(sensorId);
        s.retomar();
        imprimir(FormatoMensagens.Nivel.INFO, sensorId, "Sensor retomado");
    }

    /**
     * Interrompe a thread do sensor e espera ela terminar (no máximo {@code ESPERA_SENSOR_MS}).
     * Se nenhum outro sensor continua ligado, encerra o sistema como {@link #parar()}.
     * Segurar o lock deste controlador durante o {@code join} não causa deadlock: a thread do
     * sensor nunca usa esse lock. A leitura que estava sendo publicada no momento da interrupção
     * não é contada (a sequência só avança depois do {@code put}), então nada se perde.
     */
    @Override
    public synchronized Optional<ExtratoExecucao> desligarSensor(String sensorId) {
        Execucao ex = execucaoEmAndamento();
        sensor(ex, sensorId);
        Thread t = ex.threadsSensores().get(sensorId);
        if (!t.isAlive()) {
            throw new IllegalStateException("O sensor " + sensorId + " já está desligado");
        }
        t.interrupt();
        try {
            String problema = aguardar(t, ESPERA_SENSOR_MS);
            if (problema != null) {
                throw new IllegalStateException(problema);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Desligamento do sensor " + sensorId + " interrompido", e);
        }
        imprimir(FormatoMensagens.Nivel.INFO, sensorId, "Sensor desligado");
        if (ex.threadsSensores().values().stream().noneMatch(Thread::isAlive)) {
            // nenhum sensor ligado: o mesmo que o Parar geral (drena a fila, fecha o log, extrato)
            imprimir(FormatoMensagens.Nivel.INFO, "sistema", "Último sensor desligado: encerrando o sistema");
            return Optional.of(parar());       // mesmo lock (reentrante): nada muda entre a checagem e o parar
        }
        return Optional.empty();
    }

    /**
     * Sensores disponíveis: os da execução em andamento ou, com o sistema parado, os que a
     * configuração atual cria. Sem lock (só lê campos volatile). Pode rodar na EDT.
     */
    @Override
    public Map<String, TipoSensor> sensoresDisponiveis() {
        Execucao ex = execucao;
        if (fase == FaseSistema.PARADO || ex == null) {
            return sensoresDaConfiguracao(configuracao);
        }
        Map<String, TipoSensor> r = new java.util.LinkedHashMap<>();
        ex.sensores().forEach((id, s) -> r.put(id, s.tipo()));
        return r;
    }

    /** Sem lock de propósito: só lê (volatile, isAlive e o monitor de pausa do sensor). Pode rodar na EDT. */
    @Override
    public Map<String, EstadoSensor> estadosSensores() {
        Execucao ex = execucao;
        Map<String, EstadoSensor> estados = new java.util.LinkedHashMap<>();
        if (fase == FaseSistema.PARADO || ex == null) {
            sensoresDaConfiguracao(configuracao).keySet().forEach(id -> estados.put(id, EstadoSensor.DESLIGADO));
            return estados;
        }
        ex.sensores().forEach((id, s) -> {
            Thread t = ex.threadsSensores().get(id);
            estados.put(id, t == null || !t.isAlive() ? EstadoSensor.DESLIGADO
                    : (s.estaPausado() ? EstadoSensor.PAUSADO : EstadoSensor.EXECUTANDO));
        });
        return estados;
    }

    /** Controle individual só faz sentido com fila e consumidores rodando e o sistema não pausado. */
    private Execucao execucaoEmAndamento() {
        Execucao ex = execucao;
        if (fase != FaseSistema.EXECUTANDO || ex == null) {
            throw new IllegalStateException(fase == FaseSistema.PAUSADO
                    ? "O sistema está pausado: clique em Retomar antes de controlar um sensor"
                    : "O sensor está desligado: clique em Iniciar no cartão dele");
        }
        return ex;
    }

    private static Sensor sensor(Execucao ex, String sensorId) {
        Sensor s = ex.sensores().get(sensorId);
        if (s == null) {
            throw new IllegalArgumentException("Sensor desconhecido: " + sensorId);
        }
        return s;
    }

    private Sensor sensorLigado(String sensorId) {
        Execucao ex = execucaoEmAndamento();
        Sensor s = sensor(ex, sensorId);
        if (!ex.threadsSensores().get(sensorId).isAlive()) {
            throw new IllegalStateException("O sensor " + sensorId + " está desligado: clique em Iniciar no cartão dele");
        }
        return s;
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
        for (Thread t : ex.threadsSensores().values()) {
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

    /** Parâmetros da simulação de episódios de anomalia usados na próxima execução. */
    public ParametrosDeEpisodios parametrosEpisodios() {
        return parametrosEpisodios;
    }

    /** Troca os parâmetros de episódio — como {@link #aplicarConfiguracao}, só com o sistema PARADO. */
    public synchronized void aplicarParametrosEpisodios(ParametrosDeEpisodios novos) {
        if (fase != FaseSistema.PARADO) {
            throw new IllegalStateException("Só é possível alterar a simulação com o sistema parado");
        }
        parametrosEpisodios = Objects.requireNonNull(novos, "novos");
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
