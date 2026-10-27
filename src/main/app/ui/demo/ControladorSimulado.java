package app.ui.demo;

import app.ConfiguracaoSimulacao;
import app.ControladorMonitoramento;
import app.EstatisticasExecucao;
import app.ExtratoExecucao;
import app.FaseSistema;
import app.FiltroAlertas;
import app.FormatoMensagens;
import app.HistoricoExecucao;
import app.LeituraRegistrada;
import app.OuvinteTelemetria;
import app.RegraExibida;
import domain.Alerta;
import domain.LeituraTelemetria;
import domain.Severidade;
import domain.TipoSensor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controlador FALSO, só para desenvolver e demonstrar a interface antes do backend real.
 * Gera leituras aleatórias em threads, usa uma BlockingQueue e guarda alertas em memória.
 *
 * <p><b>Substitua</b> por um controlador que use Sensor, FilaDeLeituras,
 * AnalisadorDeAnomalias, AlertaRepository e LogArquivo. A interface não muda nada,
 * porque só depende de {@link ControladorMonitoramento}.</p>
 */
public class ControladorSimulado implements ControladorMonitoramento {

    private static final int CAPACIDADE = 1000;
    private static final String[] PREFIXO = {"TEMP", "VEL", "PRES", "UMID"};

    private final List<RegraExibida> regras = List.of(
            new RegraExibida(TipoSensor.TEMPERATURA, 0, 80, Severidade.ALTA),
            new RegraExibida(TipoSensor.VELOCIDADE, 0, 120, Severidade.MEDIA),
            new RegraExibida(TipoSensor.PRESSAO, 1, 6, Severidade.CRITICA),
            new RegraExibida(TipoSensor.UMIDADE, 20, 90, Severidade.BAIXA));
    private final List<Alerta> banco = new CopyOnWriteArrayList<>();
    private final AtomicLong proximoId = new AtomicLong(1);
    private final AtomicLong produzidas = new AtomicLong();
    private final AtomicLong processadas = new AtomicLong();
    private final Map<TipoSensor, Map<Severidade, Long>> contagem = new ConcurrentHashMap<>();

    private volatile OuvinteTelemetria ouvinte;
    private volatile FaseSistema fase = FaseSistema.PARADO;
    private volatile ConfiguracaoSimulacao config = new ConfiguracaoSimulacao(1, 400, 1);
    private volatile Instant inicio;
    private ScheduledExecutorService sensores;
    private ExecutorService consumidores;
    private BlockingQueue<LeituraTelemetria> fila;

    @Override
    public synchronized void iniciar() {
        fila = new LinkedBlockingQueue<>(CAPACIDADE);
        produzidas.set(0);
        processadas.set(0);
        contagem.clear();
        inicio = Instant.now();
        int total = config.sensoresPorTipo() * TipoSensor.values().length;
        sensores = Executors.newScheduledThreadPool(total + 1);
        consumidores = Executors.newFixedThreadPool(config.consumidores());

        for (TipoSensor tipo : TipoSensor.values()) {
            for (int i = 1; i <= config.sensoresPorTipo(); i++) {
                String id = String.format("%s-%02d", PREFIXO[tipo.ordinal()], i);
                sensores.scheduleAtFixedRate(() -> produzir(id, tipo),
                        ThreadLocalRandom.current().nextInt(50), config.intervaloMs(), TimeUnit.MILLISECONDS);
            }
        }
        sensores.scheduleAtFixedRate(this::publicarEstatisticas, 0, 1, TimeUnit.SECONDS);
        for (int i = 0; i < config.consumidores(); i++) {
            consumidores.submit(this::consumir);
        }
        fase = FaseSistema.EXECUTANDO;
        System.out.println(FormatoMensagens.console(Instant.now(), FormatoMensagens.Nivel.INFO, "sistema",
                total + " sensores e " + config.consumidores() + " consumidor(es) iniciados (intervalo "
                        + config.intervaloMs() + " ms)"));
    }

    private void produzir(String id, TipoSensor tipo) {
        if (fase != FaseSistema.EXECUTANDO) {
            return;
        }
        RegraExibida r = regras.get(tipo.ordinal());
        double amplitude = r.maximo() - r.minimo();
        double v = r.minimo() + amplitude * (0.55 + ThreadLocalRandom.current().nextGaussian() * 0.22);
        try {
            fila.put(new LeituraTelemetria(id, tipo, Math.round(v * 10) / 10.0, Instant.now()));
            produzidas.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void consumir() {
        try {
            while (true) {
                LeituraTelemetria l = fila.take();
                RegraExibida r = regras.get(l.tipo().ordinal());
                Alerta alerta = null;
                if (l.valor() < r.minimo() || l.valor() > r.maximo()) {
                    String regra = l.valor() > r.maximo()
                            ? "acima do limite " + FormatoMensagens.valorComUnidade(r.maximo(), l.tipo())
                            : "abaixo do mínimo " + FormatoMensagens.valorComUnidade(r.minimo(), l.tipo());
                    alerta = Alerta.de(l, regra, r.severidade()).comId(proximoId.getAndIncrement());
                    banco.add(alerta);
                    contagem.computeIfAbsent(l.tipo(), t -> new ConcurrentHashMap<>())
                            .merge(r.severidade(), 1L, Long::sum);
                    System.out.println(FormatoMensagens.consoleAlerta(alerta));
                }
                processadas.incrementAndGet();
                OuvinteTelemetria o = ouvinte;
                if (o != null) {
                    o.aoProcessarLeitura(l, alerta);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();     // encerramento gracioso
        }
    }

    private void publicarEstatisticas() {
        OuvinteTelemetria o = ouvinte;
        if (o != null && inicio != null) {
            o.aoAtualizarEstatisticas(new EstatisticasExecucao(produzidas.get(), processadas.get(), fila.size(),
                    CAPACIDADE, banco.size(), config.sensoresPorTipo() * 4 + config.consumidores(), true,
                    java.time.Duration.between(inicio, Instant.now()).toSeconds()));
        }
    }

    @Override
    public void pausar() {
        fase = FaseSistema.PAUSADO;
    }

    @Override
    public void retomar() {
        fase = FaseSistema.EXECUTANDO;
    }

    @Override
    public synchronized ExtratoExecucao parar() {
        fase = FaseSistema.PAUSADO;          // sensores param de produzir
        sensores.shutdownNow();
        try {
            while (!fila.isEmpty()) {         // deixa os consumidores drenarem a fila
                Thread.sleep(20);
            }
            consumidores.shutdownNow();
            consumidores.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        fase = FaseSistema.PARADO;
        Map<TipoSensor, Map<Severidade, Long>> copia = new EnumMap<>(TipoSensor.class);
        contagem.forEach((t, m) -> copia.put(t, new EnumMap<>(m)));
        ExtratoExecucao e = new ExtratoExecucao(inicio, Instant.now(), config, produzidas.get(), processadas.get(),
                copia, copia.values().stream().flatMap(m -> m.values().stream()).mapToLong(Long::longValue).sum(),
                "data/leituras.log", "(simulado em memória)");
        System.out.println(FormatoMensagens.console(Instant.now(), FormatoMensagens.Nivel.INFO, "sistema",
                "Encerrado: " + FormatoMensagens.inteiro(e.leiturasProcessadas()) + " leituras, "
                        + e.leiturasPerdidas() + " perdidas, " + e.totalAlertas() + " alertas."));
        return e;
    }

    @Override
    public FaseSistema fase() {
        return fase;
    }

    @Override
    public void registrarOuvinte(OuvinteTelemetria o) {
        this.ouvinte = o;
    }

    @Override
    public List<Alerta> buscarAlertas(FiltroAlertas f) {
        dormir(400);                          // simula latência de banco (prova que a EDT não trava)
        Instant corte = f.periodo() == null ? Instant.MIN : Instant.now().minus(f.periodo());
        List<Alerta> r = new ArrayList<>();
        for (Alerta a : banco) {
            if ((f.sensorId() == null || f.sensorId().equalsIgnoreCase(a.sensorId()))
                    && (f.tipo() == null || f.tipo() == a.tipo())
                    && (f.severidade() == null || f.severidade() == a.severidade())
                    && a.momentoLeitura().isAfter(corte)) {
                r.add(0, a);
            }
        }
        return r;
    }

    @Override
    public List<String> sensoresNoHistorico() {
        return banco.stream().map(Alerta::sensorId).distinct().sorted().toList();
    }

    @Override
    public List<RegraExibida> regras() {
        return regras;
    }

    @Override
    public ConfiguracaoSimulacao configuracao() {
        return config;
    }

    @Override
    public void aplicarConfiguracao(ConfiguracaoSimulacao nova) {
        if (fase != FaseSistema.PARADO) {
            throw new IllegalStateException("Só é possível alterar a simulação com o sistema parado");
        }
        config = nova;
    }

    @Override
    public String exportarExtrato(ExtratoExecucao e) {
        String caminho = FormatoMensagens.nomeArquivoExtrato(e.fim());
        try {
            java.nio.file.Path p = java.nio.file.Path.of(caminho);
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, FormatoMensagens.extrato(e));
            return caminho;
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    /**
     * Uma "execução anterior" inventada (3 min, 10 min atrás) para a aba Monitoramento já
     * abrir com gráficos — o controlador real lê isto do log.
     */
    @Override
    public HistoricoExecucao ultimaExecucao() {
        dormir(300);
        List<LeituraRegistrada> leituras = new ArrayList<>();
        Instant fim = Instant.now().minusSeconds(600);
        Instant inicio = fim.minusSeconds(180);
        java.util.Random rnd = new java.util.Random(7);
        for (TipoSensor tipo : TipoSensor.values()) {
            RegraExibida r = regras.get(tipo.ordinal());
            double amplitude = r.maximo() - r.minimo();
            for (int i = 1; i <= config.sensoresPorTipo(); i++) {
                String id = String.format("%s-%02d", PREFIXO[tipo.ordinal()], i);
                double v = r.minimo() + amplitude * 0.55;
                for (Instant t = inicio; t.isBefore(fim); t = t.plusMillis(config.intervaloMs())) {
                    v += rnd.nextGaussian() * amplitude * 0.03 + (r.minimo() + amplitude * 0.55 - v) * 0.02;
                    double lido = Math.round(v * 10) / 10.0;
                    Severidade sev = lido < r.minimo() || lido > r.maximo() ? r.severidade() : null;
                    leituras.add(new LeituraRegistrada(new LeituraTelemetria(id, tipo, lido, t), sev));
                }
            }
        }
        return HistoricoExecucao.de(leituras);
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
