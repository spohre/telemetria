package app;

import static org.junit.jupiter.api.Assertions.*;

import domain.ConfiguracaoSensores;
import domain.LeituraTelemetria;
import domain.ParametrosDeEpisodios;
import domain.Severidade;
import domain.TipoSensor;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Controlador real com repositório em memória e pasta temporária (sem H2). */
class ControladorTelemetriaTest {

    @TempDir
    Path raiz;

    private final Dubles.RepositorioFalso repositorio = new Dubles.RepositorioFalso();
    private final Dubles.OuvinteFalso ouvinte = new Dubles.OuvinteFalso();
    private final ByteArrayOutputStream saida = new ByteArrayOutputStream();
    private ControladorTelemetria controlador;

    private final Dubles.ConfiguracaoFalsa configuracoes = new Dubles.ConfiguracaoFalsa();

    /** Controlador com N sensores por tipo (TEMP-01…, faixas padrão), o intervalo e os consumidores dados. */
    private ControladorTelemetria novo(int sensoresPorTipo, int intervaloMs, int consumidores) {
        configuracoes.gravada = ConfiguracaoSensores.padrao().comQuantidadePorTipo(sensoresPorTipo).comIntervalo(intervaloMs);
        return novo(new ConfiguracaoSimulacao(consumidores));
    }

    /** Controlador com a configuração que estiver em {@code configuracoes} (vazia = primeira execução). */
    private ControladorTelemetria novo(ConfiguracaoSimulacao cfg) {
        controlador = new ControladorTelemetria(repositorio, configuracoes, "jdbc:teste", raiz, 1000,
                new PrintStream(saida, true, StandardCharsets.UTF_8), Clock.systemDefaultZone());
        controlador.aplicarConfiguracao(cfg);
        controlador.registrarOuvinte(ouvinte);
        return controlador;
    }

    @AfterEach
    void garantirParado() {
        if (controlador != null && controlador.fase() != FaseSistema.PARADO) {
            controlador.parar();
        }
    }

    private static void esperarAte(BooleanSupplier condicao, String mensagem) throws InterruptedException {
        long limite = System.nanoTime() + 5_000_000_000L;
        while (!condicao.getAsBoolean()) {
            if (System.nanoTime() > limite) {
                fail("tempo esgotado: " + mensagem);
            }
            Thread.sleep(5);
        }
    }

    private static Set<String> threadsVivas() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive)
                .map(Thread::getName).collect(Collectors.toSet());
    }

    @Test
    void configuracaoPadraoTemPeloMenosTresSensores() {
        novo(ControladorTelemetria.CONFIGURACAO_PADRAO);       // repositório vazio = primeira execução
        assertTrue(controlador.sensoresDisponiveis().size() >= 3);
    }

    @Test
    void cicloCompletoSemPerdaComLogEExtrato() throws Exception {
        novo(1, 10, 2);

        controlador.iniciar();
        assertEquals(FaseSistema.EXECUTANDO, controlador.fase());
        esperarAte(() -> controlador.estatisticas().leiturasProcessadas() >= 300, "300 leituras processadas");

        ExtratoExecucao e = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> controlador.parar());

        assertEquals(FaseSistema.PARADO, controlador.fase());
        assertEquals(0, e.leiturasPerdidas(), "produzidas == processadas");
        assertEquals(e.leiturasProduzidas(), e.leiturasProcessadas());
        assertEquals(repositorio.salvos.size(), e.alertasGravados());
        assertEquals(e.totalAlertas(), e.alertasGravados(), "todo alerta gerado foi salvo");

        List<String> log = Files.readAllLines(raiz.resolve("data/leituras.log"), StandardCharsets.UTF_8);
        assertEquals(e.leiturasProcessadas() + 1, log.size(), "uma linha por leitura + cabeçalho");
        assertEquals(FormatoMensagens.CABECALHO_LOG, log.get(0));

        try (var arquivos = Files.list(raiz.resolve("data"))) {
            assertTrue(arquivos.anyMatch(p -> p.getFileName().toString().matches("extrato-\\d{8}-\\d{6}\\.txt")),
                    "parar() grava o extrato");
        }
    }

    @Test
    void cadaSensorEConsumidorRodaNaSuaThreadEElasTerminamAoParar() throws Exception {
        novo(2, 20, 2);
        controlador.iniciar();

        Set<String> vivas = threadsVivas();
        for (String id : List.of("TEMP-01", "TEMP-02", "VEL-01", "VEL-02", "PRES-01", "PRES-02", "UMID-01", "UMID-02")) {
            assertTrue(vivas.contains("sensor-" + id), "falta a thread sensor-" + id);
        }
        assertTrue(vivas.contains("consumidor-1") && vivas.contains("consumidor-2"));
        assertEquals(10, controlador.estatisticas().threadsAtivas());

        controlador.parar();

        Set<String> depois = threadsVivas();
        assertTrue(depois.stream().noneMatch(n -> n.startsWith("sensor-") || n.startsWith("consumidor-")),
                "threads restantes: " + depois);
        assertEquals(0, controlador.estatisticas().threadsAtivas());
    }

    @Test
    void pausarCongelaAProducaoERetomarContinua() throws Exception {
        novo(1, 10, 1);
        controlador.iniciar();
        esperarAte(() -> controlador.estatisticas().leiturasProduzidas() >= 20, "20 leituras");

        controlador.pausar();
        assertEquals(FaseSistema.PAUSADO, controlador.fase());
        Thread.sleep(60);
        long congelado = controlador.estatisticas().leiturasProduzidas();
        Thread.sleep(150);
        assertEquals(congelado, controlador.estatisticas().leiturasProduzidas());

        controlador.retomar();
        assertEquals(FaseSistema.EXECUTANDO, controlador.fase());
        esperarAte(() -> controlador.estatisticas().leiturasProduzidas() > congelado + 10, "voltar a produzir");
    }

    @Test
    void pararDuranteAPausaTambemDrenaTudo() throws Exception {
        novo(1, 10, 1);
        controlador.iniciar();
        esperarAte(() -> controlador.estatisticas().leiturasProduzidas() >= 20, "20 leituras");
        controlador.pausar();

        ExtratoExecucao e = controlador.parar();

        assertEquals(0, e.leiturasPerdidas());
    }

    @Test
    void estatisticasChegamAoOuvinteACadaSegundo() throws Exception {
        List<EstatisticasExecucao> recebidas = new java.util.concurrent.CopyOnWriteArrayList<>();
        novo(1, 50, 1);
        controlador.registrarOuvinte(new Dubles.OuvinteFalso() {
            @Override
            public void aoAtualizarEstatisticas(EstatisticasExecucao e) {
                recebidas.add(e);
            }
        });
        controlador.iniciar();

        esperarAte(() -> recebidas.size() >= 2, "2 publicações de estatísticas");

        EstatisticasExecucao ultima = recebidas.get(recebidas.size() - 1);
        assertEquals(1000, ultima.capacidadeFila());
        assertTrue(ultima.leiturasProduzidas() > 0);
        assertTrue(ultima.bancoConectado());
    }

    @Test
    void naoIniciaDuasVezesENaoMudaConfiguracaoEmExecucao() {
        novo(1, 50, 1);
        controlador.iniciar();

        assertThrows(IllegalStateException.class, () -> controlador.iniciar());
        assertThrows(IllegalStateException.class,
                () -> controlador.aplicarConfiguracao(new ConfiguracaoSimulacao(2)));

        controlador.parar();
        controlador.aplicarConfiguracao(new ConfiguracaoSimulacao(2));
        assertEquals(2, controlador.configuracao().consumidores());
        assertThrows(IllegalStateException.class, () -> controlador.parar(), "já parado");
    }

    @Test
    void regrasExibidasTrazemFaixaEEscalaDeSeveridade() {
        novo(1, 200, 1);
        List<RegraExibida> regras = controlador.regras();

        assertEquals(List.of(TipoSensor.TEMPERATURA, TipoSensor.VELOCIDADE, TipoSensor.PRESSAO, TipoSensor.UMIDADE),
                regras.stream().map(RegraExibida::tipo).toList());
        RegraExibida temperatura = regras.get(0);
        assertEquals(0.0, temperatura.minimo());
        assertEquals(80.0, temperatura.maximo());
        assertEquals(Severidade.BAIXA, temperatura.severidade(), "severidade mínima de uma violação");
        assertEquals(List.of(4.0, 12.0, 24.0), temperatura.escala().stream().limit(3)
                .map(d -> d.desvioMaximo()).toList());
        assertEquals(Severidade.CRITICA, temperatura.escala().get(3).severidade());
        assertTrue(regras.stream().allMatch(r -> r.escala().size() == 4));
    }

    @Test
    void buscarAlertasConverteOPeriodoEmInstanteEFiltra() throws Exception {
        novo(1, 10, 1);
        controlador.iniciar();
        esperarAte(() -> repositorio.salvos.size() >= 5, "alguns alertas salvos");
        controlador.parar();

        Instant antes = Instant.now();
        List<?> ultimaHora = controlador.buscarAlertas(
                new FiltroAlertas(null, null, null, Duration.ofHours(1)));
        assertEquals(repositorio.salvos.size(), ultimaHora.size());
        Instant desde = repositorio.ultimoDesde;
        assertNotNull(desde);
        assertTrue(!desde.isAfter(antes.minus(Duration.ofHours(1)).plusSeconds(1)));

        assertTrue(controlador.buscarAlertas(new FiltroAlertas("NAO-EXISTE", null, null, null)).isEmpty());
        assertEquals(repositorio.salvos.size(), controlador.buscarAlertas(FiltroAlertas.todos()).size());
        assertNull(repositorio.ultimoDesde, "sem período = sem filtro de data");
    }

    @Test
    void bancoForaDoArNaoPerdeLeiturasEExtratoMostraAlertasNaoGravados() throws Exception {
        repositorio.fora.set(true);
        novo(1, 10, 1);
        controlador.iniciar();
        esperarAte(() -> controlador.estatisticas().alertasGerados() >= 3, "alguns alertas gerados");

        ExtratoExecucao e = controlador.parar();

        assertEquals(0, e.leiturasPerdidas());
        assertEquals(0, e.alertasGravados());
        assertTrue(e.totalAlertas() >= 3);
        assertEquals(1, ouvinte.falhas.size(), "aviso único de banco fora");
        assertTrue(FormatoMensagens.extrato(e).contains("<<< VERIFICAR"));
    }

    @Test
    void parametrosDeEpisodiosSoMudamComOSistemaParado() {
        novo(1, 50, 1);
        assertEquals(ParametrosDeEpisodios.padrao(), controlador.parametrosEpisodios(), "padrão");
        ParametrosDeEpisodios frequentes = ParametrosDeEpisodios.padrao().comMediaEntreEpisodios(10);

        controlador.iniciar();
        assertThrows(IllegalStateException.class, () -> controlador.aplicarParametrosEpisodios(frequentes));
        controlador.parar();

        controlador.aplicarParametrosEpisodios(frequentes);
        assertEquals(frequentes, controlador.parametrosEpisodios());
    }

    @Test
    void alertasDaExecucaoRealVemEmRajadasPorSensor() throws Exception {
        novo(1, 10, 1);
        controlador.aplicarParametrosEpisodios(ParametrosDeEpisodios.padrao().comMediaEntreEpisodios(20));
        controlador.iniciar();
        esperarAte(() -> ouvinte.processadas.size() >= 1200, "leituras suficientes");
        controlador.parar();

        int alertas = 0;
        int rajadas = 0;
        Map<String, List<Dubles.OuvinteFalso.Processada>> porSensor = ouvinte.processadas.stream()
                .collect(Collectors.groupingBy(p -> p.leitura().sensorId()));
        for (List<Dubles.OuvinteFalso.Processada> serie : porSensor.values()) {
            serie.sort(Comparator.comparingLong(p -> p.leitura().sequencia()));
            boolean anterior = false;
            for (Dubles.OuvinteFalso.Processada p : serie) {
                boolean alerta = p.alerta() != null;
                alertas += alerta ? 1 : 0;
                rajadas += alerta && !anterior ? 1 : 0;
                anterior = alerta;
            }
        }
        assertTrue(rajadas > 5, "rajadas: " + rajadas);
        double media = (double) alertas / rajadas;
        assertTrue(media >= 2.0, "alertas por rajada = " + media + " (gerador antigo ~1,05)");
    }

    @Test
    void consoleAnunciaASimulacaoPorEventosDiscretos() throws Exception {
        novo(1, 200, 1);
        controlador.iniciar();
        controlador.parar();
        String console = saida.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("Simulação por eventos discretos: ~10,0 s normais (50 leituras) em média entre episódios; "
                + "cada episódio aquece 1,2 a 2,4 s"), console);
    }

    @Test
    void consoleMostraInicioAlertasEEncerramentoMasNaoLeiturasNormais() throws Exception {
        novo(1, 10, 1);
        controlador.iniciar();
        esperarAte(() -> repositorio.salvos.size() >= 3, "alguns alertas");
        controlador.parar();

        String console = saida.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("INFO    [sistema]     4 sensores e 1 consumidor(es) iniciados (intervalo 10 ms)"), console);
        assertTrue(console.lines().anyMatch(l -> l.contains("ALERTA") && l.contains("salvo #")), console);
        assertTrue(console.contains("Encerrado:"), console);
        assertTrue(console.contains(" 0 perdidas"), console);
        long linhas = console.lines().count();
        assertTrue(linhas < controlador.estatisticas().leiturasProcessadas(), "leituras normais não vão para o console");
    }

    @Test
    void exportarExtratoGravaArquivoUtf8() throws Exception {
        novo(1, 20, 1);
        controlador.iniciar();
        ExtratoExecucao e = controlador.parar();

        Path arquivo = Path.of(controlador.exportarExtrato(e));

        String conteudo = Files.readString(arquivo, StandardCharsets.UTF_8);
        assertTrue(conteudo.startsWith("EXTRATO DA EXECUÇÃO"));
        assertTrue(conteudo.contains("jdbc:teste"));
    }

    // ------------------------------------------------------------------ controle de um sensor

    private long processadasDo(String sensorId) {
        return ouvinte.processadas.stream().filter(p -> p.leitura().sensorId().equals(sensorId)).count();
    }

    @Test
    void desligarUmSensorParaSoEleEOsOutrosContinuam() throws Exception {
        novo(1, 10, 1);
        controlador.iniciar();
        esperarAte(() -> processadasDo("TEMP-01") >= 5, "TEMP-01 produzir");

        controlador.desligarSensor("TEMP-01");

        assertEquals(EstadoSensor.DESLIGADO, controlador.estadosSensores().get("TEMP-01"));
        assertEquals(EstadoSensor.EXECUTANDO, controlador.estadosSensores().get("VEL-01"));
        assertFalse(threadsVivas().contains("sensor-TEMP-01"), "a thread do sensor terminou");
        Thread.sleep(50);                                    // o que já estava na fila termina de passar
        long temp = processadasDo("TEMP-01");
        long vel = processadasDo("VEL-01");
        Thread.sleep(150);
        assertEquals(temp, processadasDo("TEMP-01"), "desligado não produz mais");
        assertTrue(processadasDo("VEL-01") > vel, "os outros sensores continuam");
    }

    @Test
    void religarUmSensorContinuaASequenciaSemRepetirNemPerder() throws Exception {
        novo(1, 10, 1);
        controlador.iniciar();
        esperarAte(() -> processadasDo("PRES-01") >= 5, "PRES-01 produzir");
        controlador.desligarSensor("PRES-01");
        controlador.iniciarSensor("PRES-01");
        assertEquals(EstadoSensor.EXECUTANDO, controlador.estadosSensores().get("PRES-01"));
        long antes = processadasDo("PRES-01");
        esperarAte(() -> processadasDo("PRES-01") >= antes + 5, "PRES-01 voltar a produzir");

        ExtratoExecucao e = controlador.parar();

        List<Long> seq = ouvinte.processadas.stream().map(Dubles.OuvinteFalso.Processada::leitura)
                .filter(l -> l.sensorId().equals("PRES-01")).map(domain.LeituraTelemetria::sequencia)
                .sorted().toList();
        for (int i = 0; i < seq.size(); i++) {
            assertEquals(i + 1L, seq.get(i).longValue(), "sequência 1, 2, 3... sem buraco nem repetição");
        }
        assertEquals(0, e.leiturasPerdidas());
        assertEquals(EstadoSensor.DESLIGADO, controlador.estadosSensores().get("PRES-01"), "parado = todos desligados");
    }

    @Test
    void pausarUmSensorCongelaSoEleERetomarContinua() throws Exception {
        novo(1, 10, 1);
        controlador.iniciar();
        esperarAte(() -> processadasDo("UMID-01") >= 3, "UMID-01 produzir");

        controlador.pausarSensor("UMID-01");
        assertEquals(EstadoSensor.PAUSADO, controlador.estadosSensores().get("UMID-01"));
        assertTrue(threadsVivas().contains("sensor-UMID-01"), "pausado: a thread continua viva");
        Thread.sleep(60);
        long congelado = processadasDo("UMID-01");
        long temp = processadasDo("TEMP-01");
        Thread.sleep(150);
        assertEquals(congelado, processadasDo("UMID-01"));
        assertTrue(processadasDo("TEMP-01") > temp);

        controlador.retomarSensor("UMID-01");
        esperarAte(() -> processadasDo("UMID-01") > congelado + 3, "UMID-01 voltar a produzir");
    }

    @Test
    void sensoresFicamDisponiveisEDesligadosAntesDeQualquerExecucao() {
        novo(2, 50, 1);
        assertEquals(List.of("PRES-01", "PRES-02", "TEMP-01", "TEMP-02", "UMID-01", "UMID-02", "VEL-01", "VEL-02"),
                List.copyOf(controlador.sensoresDisponiveis().keySet()), "um por tipo × 2, em ordem de id");
        assertEquals(TipoSensor.VELOCIDADE, controlador.sensoresDisponiveis().get("VEL-02"));
        assertTrue(controlador.estadosSensores().values().stream().allMatch(e -> e == EstadoSensor.DESLIGADO));
        assertThrows(IllegalArgumentException.class, () -> controlador.iniciarSensor("XYZ-99"));
        assertEquals(FaseSistema.PARADO, controlador.fase(), "id inválido não inicia nada");
    }

    @Test
    void ligarUmSensorComTudoParadoIniciaOSistemaSoComEle() throws Exception {
        novo(1, 10, 1);

        controlador.iniciarSensor("VEL-01");

        assertEquals(FaseSistema.EXECUTANDO, controlador.fase());
        assertEquals(EstadoSensor.EXECUTANDO, controlador.estadosSensores().get("VEL-01"));
        assertEquals(EstadoSensor.DESLIGADO, controlador.estadosSensores().get("TEMP-01"));
        assertEquals(2, controlador.estatisticas().threadsAtivas(), "1 sensor + 1 consumidor");
        esperarAte(() -> processadasDo("VEL-01") >= 5, "VEL-01 produzir");
        assertEquals(0, processadasDo("TEMP-01"), "os outros continuam desligados");

        controlador.iniciar();                                    // Iniciar geral: liga os que faltam
        assertTrue(controlador.estadosSensores().values().stream().allMatch(e -> e == EstadoSensor.EXECUTANDO));
        assertThrows(IllegalStateException.class, () -> controlador.iniciar(), "todos já ligados");
        esperarAte(() -> processadasDo("TEMP-01") >= 3, "TEMP-01 produzir");

        ExtratoExecucao e = controlador.parar();
        assertEquals(0, e.leiturasPerdidas());
        assertTrue(controlador.estadosSensores().values().stream().allMatch(s -> s == EstadoSensor.DESLIGADO));
    }

    @Test
    void controleIndividualRespeitaPausaGeralEIdValido() throws Exception {
        novo(1, 50, 1);
        assertThrows(IllegalStateException.class, () -> controlador.pausarSensor("TEMP-01"), "parado: nada a pausar");

        controlador.iniciar();
        assertThrows(IllegalArgumentException.class, () -> controlador.desligarSensor("XYZ-99"));
        assertThrows(IllegalStateException.class, () -> controlador.iniciarSensor("TEMP-01"), "já ligado");
        controlador.desligarSensor("TEMP-01");
        assertThrows(IllegalStateException.class, () -> controlador.pausarSensor("TEMP-01"), "desligado");

        controlador.pausar();                                  // pausa geral bloqueia o controle individual
        assertThrows(IllegalStateException.class, () -> controlador.iniciarSensor("TEMP-01"));
        controlador.retomar();
        controlador.iniciarSensor("TEMP-01");
        assertEquals(4, controlador.estadosSensores().size());
        assertEquals(5, controlador.estatisticas().threadsAtivas(), "4 sensores + 1 consumidor");
    }

    @Test
    void desligarOUltimoSensorLigadoEquivaleAParar() throws Exception {
        novo(1, 10, 2);
        controlador.iniciar();
        esperarAte(() -> controlador.estatisticas().leiturasProcessadas() >= 40, "40 leituras");
        for (String id : List.of("PRES-01", "TEMP-01", "UMID-01")) {
            assertTrue(controlador.desligarSensor(id).isEmpty(), "ainda há sensor ligado: o sistema continua");
        }
        assertEquals(FaseSistema.EXECUTANDO, controlador.fase());

        java.util.Optional<ExtratoExecucao> extrato = controlador.desligarSensor("VEL-01");

        assertTrue(extrato.isPresent(), "o último desligado devolve o extrato, como parar()");
        assertEquals(FaseSistema.PARADO, controlador.fase());
        assertEquals(0, extrato.get().leiturasPerdidas());
        assertEquals(extrato.get().leiturasProduzidas(), extrato.get().leiturasProcessadas());
        Set<String> vivas = threadsVivas();
        assertTrue(vivas.stream().noneMatch(n -> n.startsWith("sensor-") || n.startsWith("consumidor-")),
                "threads restantes: " + vivas);
        try (var arquivos = Files.list(raiz.resolve("data"))) {
            assertTrue(arquivos.anyMatch(p -> p.getFileName().toString().startsWith("extrato-")), "extrato gravado");
        }
    }

    @Test
    void sensorPausadoContaComoLigadoAoDesligarOsOutros() throws Exception {
        novo(1, 50, 1);
        controlador.iniciarSensor("TEMP-01");                // sistema sobe só com TEMP-01
        controlador.iniciarSensor("VEL-01");
        controlador.pausarSensor("VEL-01");

        assertTrue(controlador.desligarSensor("TEMP-01").isEmpty(), "VEL-01 está pausado, não desligado");
        assertEquals(FaseSistema.EXECUTANDO, controlador.fase());
        assertTrue(controlador.desligarSensor("VEL-01").isPresent(), "agora sim era o último");
        assertEquals(FaseSistema.PARADO, controlador.fase());
    }

    // ------------------------------------------------------------------ última execução (gráficos ao abrir)

    private void escreverLog(Instant... momentos) throws Exception {
        Path log = raiz.resolve("data/leituras.log");
        Files.createDirectories(log.getParent());
        StringBuilder sb = new StringBuilder(FormatoMensagens.CABECALHO_LOG).append('\n');
        for (Instant m : momentos) {
            sb.append(FormatoMensagens.linhaLog(new LeituraTelemetria("TEMP-01", TipoSensor.TEMPERATURA, 50, m), null, "t"))
                    .append('\n');
        }
        Files.writeString(log, sb.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void ultimaExecucaoSemLogEVazia() {
        novo(1, 200, 1);
        assertTrue(controlador.ultimaExecucao().vazio());
    }

    @Test
    void ultimaExecucaoTrazSoAsLeiturasDaExecucaoMaisRecente() throws Exception {
        Instant antiga = Instant.now().minus(Duration.ofHours(2)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        escreverLog(antiga, antiga.plusSeconds(1));         // execução anterior, já no log

        novo(1, 10, 1);
        controlador.iniciar();
        esperarAte(() -> controlador.estatisticas().leiturasProcessadas() >= 40, "40 leituras processadas");
        ExtratoExecucao e = controlador.parar();

        HistoricoExecucao h = controlador.ultimaExecucao();
        assertEquals(e.leiturasProcessadas(), h.leituras().size(), "todas desta execução e nenhuma da anterior");
        assertTrue(h.inicio().isAfter(antiga.plusSeconds(60)));
        assertEquals(e.totalAlertas(), h.leituras().stream().filter(r -> r.severidade() != null).count(),
                "as leituras com alerta voltam com a severidade");
    }

    @Test
    void ultimaExecucaoSemExtratoUsaOUltimoBlocoSemPausaLonga() throws Exception {
        Instant agora = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Instant antiga = agora.minus(Duration.ofHours(2));
        Instant recente = agora.minus(Duration.ofMinutes(10));
        escreverLog(antiga, antiga.plusSeconds(1), recente, recente.plusSeconds(1), recente.plusSeconds(2));

        novo(1, 200, 1);
        HistoricoExecucao h = controlador.ultimaExecucao();

        assertEquals(3, h.leituras().size());
        assertEquals(recente, h.inicio());
        assertEquals(recente.plusSeconds(2), h.fim());
    }

    // ================================================================== configuração dos sensores

    @Test
    void primeiraExecucaoUsaOPadraoEGravaNoRepositorio() {
        novo(ControladorTelemetria.CONFIGURACAO_PADRAO);          // repositório vazio
        assertEquals(ConfiguracaoSensores.padrao(), controlador.configuracaoSensores());
        assertEquals(ConfiguracaoSensores.padrao(), configuracoes.gravada, "o padrão foi gravado");
        assertEquals(List.of("PRES-01", "TEMP-01", "UMID-01", "VEL-01"),
                List.copyOf(controlador.sensoresDisponiveis().keySet()));
    }

    @Test
    void bancoForaDoArAoAbrirUsaOPadraoSoEmMemoriaEAvisa() {
        configuracoes.fora.set(true);
        novo(ControladorTelemetria.CONFIGURACAO_PADRAO);
        assertEquals(ConfiguracaoSensores.padrao(), controlador.configuracaoSensores());
        assertTrue(saida.toString(StandardCharsets.UTF_8).contains("AVISO"), "avisou no console");
    }

    @Test
    void novoControladorComOMesmoRepositorioRecuperaTudo() {
        novo(1, 50, 1);
        controlador.adicionarSensor(TipoSensor.TEMPERATURA);
        controlador.alterarFaixaSensor("TEMP-02", 10, 60);
        controlador.alterarFaixaPadrao(TipoSensor.PRESSAO, 2, 6);
        controlador.alterarIntervalo(300);
        ConfiguracaoSensores antes = controlador.configuracaoSensores();

        ControladorTelemetria reaberto = new ControladorTelemetria(repositorio, configuracoes, "jdbc:teste", raiz,
                1000, null, Clock.systemDefaultZone());

        assertEquals(antes, reaberto.configuracaoSensores());
        assertEquals(300, reaberto.configuracaoSensores().intervaloMs());
        assertTrue(reaberto.sensoresDisponiveis().containsKey("TEMP-02"));
    }

    @Test
    void sensorAdicionadoComOSistemaParadoApareceERodaAoIniciar() throws InterruptedException {
        novo(1, 10, 1);
        ConfiguracaoSensores nova = controlador.adicionarSensor(TipoSensor.VELOCIDADE);

        assertEquals(nova, controlador.configuracaoSensores());
        assertEquals(nova, configuracoes.gravada, "gravado no repositório");
        assertEquals(TipoSensor.VELOCIDADE, controlador.sensoresDisponiveis().get("VEL-02"));
        assertEquals(EstadoSensor.DESLIGADO, controlador.estadosSensores().get("VEL-02"));

        controlador.iniciar();
        esperarAte(() -> processadasDo("VEL-02") >= 3, "VEL-02 produzir");
        assertEquals(EstadoSensor.EXECUTANDO, controlador.estadosSensores().get("VEL-02"));
        ExtratoExecucao extrato = controlador.parar();
        assertEquals(5, extrato.sensores().sensores().size());
    }

    @Test
    void sensorAdicionadoPodeSerLigadoSozinhoComTudoParado() throws InterruptedException {
        novo(1, 10, 1);
        controlador.adicionarSensor(TipoSensor.UMIDADE);
        controlador.iniciarSensor("UMID-02");
        esperarAte(() -> processadasDo("UMID-02") >= 3, "UMID-02 produzir");
        assertEquals(EstadoSensor.DESLIGADO, controlador.estadosSensores().get("UMID-01"));
    }

    @Test
    void alterarConfiguracaoDosSensoresEmExecucaoOuPausadoEhRecusado() {
        novo(1, 50, 1);
        controlador.iniciar();
        ConfiguracaoSensores antes = controlador.configuracaoSensores();
        long gravacoes = configuracoes.gravacoes.get();

        assertThrows(IllegalStateException.class, () -> controlador.adicionarSensor(TipoSensor.TEMPERATURA));
        assertThrows(IllegalStateException.class, () -> controlador.alterarFaixaSensor("TEMP-01", 1, 2));
        assertThrows(IllegalStateException.class, () -> controlador.alterarFaixaPadrao(TipoSensor.PRESSAO, 1, 2));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> controlador.alterarIntervalo(300));
        assertTrue(e.getMessage().contains("Pare o sistema"), e.getMessage());
        controlador.pausar();
        assertThrows(IllegalStateException.class, () -> controlador.adicionarSensor(TipoSensor.TEMPERATURA));

        assertEquals(antes, controlador.configuracaoSensores());
        assertEquals(gravacoes, configuracoes.gravacoes.get(), "nada gravado");
        controlador.retomar();
    }

    @Test
    void valorInvalidoEhRecusadoComMensagemENaoGrava() {
        novo(1, 50, 1);
        long gravacoes = configuracoes.gravacoes.get();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controlador.alterarFaixaSensor("TEMP-01", 50, 10));
        assertTrue(e.getMessage().contains("menor"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> controlador.alterarFaixaSensor("XYZ-01", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> controlador.alterarIntervalo(5));
        assertEquals(gravacoes, configuracoes.gravacoes.get());
    }

    @Test
    void bancoQueNaoGravaNaoAlteraAConfiguracaoEExplicaOQueFazer() {
        novo(1, 50, 1);
        ConfiguracaoSensores antes = controlador.configuracaoSensores();
        configuracoes.fora.set(true);

        FalhaDeOperacao f = assertThrows(FalhaDeOperacao.class, () -> controlador.adicionarSensor(TipoSensor.PRESSAO));
        assertFalse(f.oQueAconteceu().isBlank());
        assertFalse(f.oQueFazer().isBlank());
        assertNotNull(f.getCause(), "causa técnica vai junto");
        assertThrows(FalhaDeOperacao.class, () -> controlador.alterarIntervalo(300));

        assertEquals(antes, controlador.configuracaoSensores(), "configuração em memória não mudou");
        assertFalse(controlador.sensoresDisponiveis().containsKey("PRES-02"));
    }

    @Test
    void faixaPropriaDoSensorDecideOsAlertasNoSistemaReal() throws InterruptedException {
        novo(1, 10, 1);
        controlador.adicionarSensor(TipoSensor.TEMPERATURA);
        controlador.alterarFaixaSensor("TEMP-02", 10, 60);
        controlador.aplicarParametrosEpisodios(ParametrosDeEpisodios.padrao().comMediaEntreEpisodios(5));

        controlador.iniciar();
        esperarAte(() -> alertasDo("TEMP-02").size() >= 5 && alertasDo("TEMP-01").size() >= 5,
                "alertas dos dois sensores de temperatura");
        controlador.parar();

        for (var a : alertasDo("TEMP-02")) {
            assertTrue(Set.of("acima do limite 60,0 °C", "abaixo do limite 10,0 °C").contains(a.regraViolada()),
                    "TEMP-02 usa a própria faixa: " + a.regraViolada());
            assertTrue(a.valorLido() > 60 || a.valorLido() < 10, "valor fora de 10–60: " + a.valorLido());
        }
        for (var a : alertasDo("TEMP-01")) {
            assertTrue(Set.of("acima do limite 80,0 °C", "abaixo do limite 0,0 °C").contains(a.regraViolada()),
                    "TEMP-01 continua com a faixa padrão: " + a.regraViolada());
        }
    }

    private List<domain.Alerta> alertasDo(String sensorId) {
        return repositorio.salvos.stream().filter(a -> a.sensorId().equals(sensorId)).toList();
    }

    @Test
    void regrasDosSensoresTrazemAFaixaDeCadaUmERegrasTrazemOPadrao() {
        novo(1, 50, 1);
        controlador.adicionarSensor(TipoSensor.TEMPERATURA);
        controlador.alterarFaixaSensor("TEMP-02", 10, 60);
        controlador.alterarFaixaPadrao(TipoSensor.TEMPERATURA, 5, 70);

        RegraExibida t2 = controlador.regrasDosSensores().get("TEMP-02");
        assertEquals(10.0, t2.minimo());
        assertEquals(60.0, t2.maximo());
        assertEquals(List.of(2.5, 7.5, 15.0), t2.escala().stream().limit(3).map(d -> d.desvioMaximo()).toList());
        assertEquals(80.0, controlador.regrasDosSensores().get("TEMP-01").maximo(), "TEMP-01 não muda com o padrão");
        assertEquals(70.0, controlador.regras().get(0).maximo(), "regras() = faixa padrão do tipo");
    }

    @Test
    void consoleEExtratoMostramAFaixaDeCadaSensor() throws InterruptedException {
        novo(1, 10, 1);
        controlador.adicionarSensor(TipoSensor.TEMPERATURA);
        controlador.alterarFaixaSensor("TEMP-02", 10, 60);
        controlador.iniciar();
        esperarAte(() -> processadasDo("TEMP-02") >= 2, "TEMP-02 produzir");
        ExtratoExecucao extrato = controlador.parar();

        String console = saida.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("Faixas aceitas: "), console);
        assertTrue(console.contains("TEMP-02 10,0 a 60,0 °C (propria)"), console);
        String texto = FormatoMensagens.extrato(extrato);
        assertTrue(texto.contains("FAIXAS ACEITAS"), texto);
        assertTrue(texto.contains("5 sensores"), texto);
    }

    @Test
    void usarSemGravarValeSoParaAProximaExecucaoENaoGrava() {
        novo(1, 50, 1);
        ConfiguracaoSensores gravada = configuracoes.gravada;
        controlador.usarSemGravar(gravada.comQuantidadePorTipo(2).comIntervalo(20));

        assertEquals(8, controlador.sensoresDisponiveis().size());
        assertEquals(gravada, configuracoes.gravada, "repositório intacto");
        assertEquals(4, new ControladorTelemetria(repositorio, configuracoes, "jdbc:teste", raiz, 1000, null,
                Clock.systemDefaultZone()).sensoresDisponiveis().size(), "ao reabrir volta a gravada");
    }

    @Test
    void removerSensorComOSistemaParadoTiraDaListaEGrava() throws InterruptedException {
        novo(1, 10, 1);
        controlador.adicionarSensor(TipoSensor.TEMPERATURA);
        ConfiguracaoSensores nova = controlador.removerSensor("VEL-01");

        assertFalse(controlador.sensoresDisponiveis().containsKey("VEL-01"));
        assertTrue(controlador.regrasDosSensores().containsKey("TEMP-02"));
        assertEquals(nova, configuracoes.gravada, "gravado no repositório");
        assertTrue(saida.toString(StandardCharsets.UTF_8).contains("Sensor VEL-01 removido"));

        controlador.iniciar();                      // a execução não cria o sensor removido
        esperarAte(() -> processadasDo("TEMP-02") >= 2, "TEMP-02 produzir");
        ExtratoExecucao extrato = controlador.parar();
        assertEquals(4, extrato.sensores().sensores().size());
        assertEquals(0, processadasDo("VEL-01"));
        assertThrows(IllegalArgumentException.class, () -> controlador.iniciarSensor("VEL-01"));
    }

    @Test
    void removerEhRecusadoEmExecucaoNoUltimoSensorESemBanco() {
        novo(1, 50, 1);
        controlador.iniciar();
        assertThrows(IllegalStateException.class, () -> controlador.removerSensor("TEMP-01"));
        controlador.parar();

        controlador.removerSensor("VEL-01");
        controlador.removerSensor("PRES-01");
        controlador.removerSensor("UMID-01");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controlador.removerSensor("TEMP-01"));
        assertTrue(e.getMessage().contains("pelo menos um sensor"), e.getMessage());

        controlador.adicionarSensor(TipoSensor.PRESSAO);
        configuracoes.fora.set(true);
        ConfiguracaoSensores antes = controlador.configuracaoSensores();
        assertThrows(FalhaDeOperacao.class, () -> controlador.removerSensor("PRES-01"));
        assertEquals(antes, controlador.configuracaoSensores(), "banco sem gravar: nada mudou");
    }
}
