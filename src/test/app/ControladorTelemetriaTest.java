package app;

import static org.junit.jupiter.api.Assertions.*;

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

    private ControladorTelemetria novo(ConfiguracaoSimulacao cfg) {
        controlador = new ControladorTelemetria(repositorio, "jdbc:teste", raiz, 1000,
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
        ConfiguracaoSimulacao p = ControladorTelemetria.CONFIGURACAO_PADRAO;
        assertTrue(p.sensoresPorTipo() * TipoSensor.values().length >= 3);
    }

    @Test
    void cicloCompletoSemPerdaComLogEExtrato() throws Exception {
        novo(new ConfiguracaoSimulacao(1, 10, 2));

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
        novo(new ConfiguracaoSimulacao(2, 20, 2));
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
        novo(new ConfiguracaoSimulacao(1, 10, 1));
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
        novo(new ConfiguracaoSimulacao(1, 10, 1));
        controlador.iniciar();
        esperarAte(() -> controlador.estatisticas().leiturasProduzidas() >= 20, "20 leituras");
        controlador.pausar();

        ExtratoExecucao e = controlador.parar();

        assertEquals(0, e.leiturasPerdidas());
    }

    @Test
    void estatisticasChegamAoOuvinteACadaSegundo() throws Exception {
        List<EstatisticasExecucao> recebidas = new java.util.concurrent.CopyOnWriteArrayList<>();
        novo(new ConfiguracaoSimulacao(1, 50, 1));
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
        novo(new ConfiguracaoSimulacao(1, 50, 1));
        controlador.iniciar();

        assertThrows(IllegalStateException.class, () -> controlador.iniciar());
        assertThrows(IllegalStateException.class,
                () -> controlador.aplicarConfiguracao(new ConfiguracaoSimulacao(2, 50, 1)));

        controlador.parar();
        controlador.aplicarConfiguracao(new ConfiguracaoSimulacao(2, 50, 1));
        assertEquals(2, controlador.configuracao().sensoresPorTipo());
        assertThrows(IllegalStateException.class, () -> controlador.parar(), "já parado");
    }

    @Test
    void regrasExibidasTrazemFaixaEEscalaDeSeveridade() {
        novo(ControladorTelemetria.CONFIGURACAO_PADRAO);
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
        novo(new ConfiguracaoSimulacao(1, 10, 1));
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
        novo(new ConfiguracaoSimulacao(1, 10, 1));
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
        novo(new ConfiguracaoSimulacao(1, 50, 1));
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
        novo(new ConfiguracaoSimulacao(1, 10, 1));
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
        novo(new ConfiguracaoSimulacao(1, 200, 1));
        controlador.iniciar();
        controlador.parar();
        String console = saida.toString(StandardCharsets.UTF_8);
        assertTrue(console.contains("Simulação por eventos discretos: ~10,0 s normais (50 leituras) em média entre episódios; "
                + "cada episódio aquece 1,2 a 2,4 s"), console);
    }

    @Test
    void consoleMostraInicioAlertasEEncerramentoMasNaoLeiturasNormais() throws Exception {
        novo(new ConfiguracaoSimulacao(1, 10, 1));
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
        novo(new ConfiguracaoSimulacao(1, 20, 1));
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
        novo(new ConfiguracaoSimulacao(1, 10, 1));
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
        novo(new ConfiguracaoSimulacao(1, 10, 1));
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
        novo(new ConfiguracaoSimulacao(1, 10, 1));
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
        novo(new ConfiguracaoSimulacao(2, 50, 1));
        assertEquals(List.of("PRES-01", "PRES-02", "TEMP-01", "TEMP-02", "UMID-01", "UMID-02", "VEL-01", "VEL-02"),
                List.copyOf(controlador.sensoresDisponiveis().keySet()), "um por tipo × 2, em ordem de id");
        assertEquals(TipoSensor.VELOCIDADE, controlador.sensoresDisponiveis().get("VEL-02"));
        assertTrue(controlador.estadosSensores().values().stream().allMatch(e -> e == EstadoSensor.DESLIGADO));
        assertThrows(IllegalArgumentException.class, () -> controlador.iniciarSensor("XYZ-99"));
        assertEquals(FaseSistema.PARADO, controlador.fase(), "id inválido não inicia nada");
    }

    @Test
    void ligarUmSensorComTudoParadoIniciaOSistemaSoComEle() throws Exception {
        novo(new ConfiguracaoSimulacao(1, 10, 1));

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
        novo(new ConfiguracaoSimulacao(1, 50, 1));
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
        novo(new ConfiguracaoSimulacao(1, 10, 2));
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
        novo(new ConfiguracaoSimulacao(1, 50, 1));
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
        novo(new ConfiguracaoSimulacao(1, 200, 1));
        assertTrue(controlador.ultimaExecucao().vazio());
    }

    @Test
    void ultimaExecucaoTrazSoAsLeiturasDaExecucaoMaisRecente() throws Exception {
        Instant antiga = Instant.now().minus(Duration.ofHours(2)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        escreverLog(antiga, antiga.plusSeconds(1));         // execução anterior, já no log

        novo(new ConfiguracaoSimulacao(1, 10, 1));
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

        novo(new ConfiguracaoSimulacao(1, 200, 1));
        HistoricoExecucao h = controlador.ultimaExecucao();

        assertEquals(3, h.leituras().size());
        assertEquals(recente, h.inicio());
        assertEquals(recente.plusSeconds(2), h.fim());
    }
}
