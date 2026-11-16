package app;

import domain.Alerta;
import domain.AnalisadorDeAnomalias;
import domain.ConfiguracaoSensores;
import domain.DegrauDeSeveridade;
import domain.RegraDeAnomalia;
import domain.TipoSensor;

import java.util.List;
import java.util.Map;

/**
 * Tudo o que a interface pode pedir ao sistema. A View só conhece esta interface —
 * nunca threads, fila, analisador, JDBC ou arquivos diretamente.
 *
 * <p>Métodos marcados como "demorado" devem ser chamados pela View dentro de um
 * {@link javax.swing.SwingWorker}, nunca na EDT.</p>
 */
public interface ControladorMonitoramento {

    /**
     * Liga todos os sensores. Parado: sobe fila, consumidores e log e liga todos. Em execução
     * (ex.: começou por um sensor só): liga os que estão desligados.
     */
    void iniciar();

    /** Suspende a produção de leituras (threads continuam vivas). */
    void pausar();

    /** Retoma após pausa. */
    void retomar();

    /** Demorado: interrompe as threads, drena a fila e devolve o extrato da execução. */
    ExtratoExecucao parar();

    FaseSistema fase();

    // ---- controle de UM sensor (id inexistente → IllegalArgumentException). Iniciar vale com o
    //      sistema parado; pausar/retomar/desligar exigem o sensor ligado e o sistema não pausado.

    /**
     * Sensores disponíveis (id → tipo, em ordem de id): os da execução atual ou, com o sistema
     * parado, os que a configuração atual cria. Rápido e sem bloqueio: pode ser chamado na EDT.
     */
    Map<String, TipoSensor> sensoresDisponiveis();

    /**
     * Liga a thread de um sensor desligado (a sequência de leituras continua de onde parou).
     * Com o sistema PARADO, sobe a execução só com este sensor ligado.
     */
    void iniciarSensor(String sensorId);

    /** Suspende só este sensor; a thread continua viva. */
    void pausarSensor(String sensorId);

    /** Retoma um sensor pausado. */
    void retomarSensor(String sensorId);

    /**
     * Demorado: interrompe a thread do sensor e espera ela terminar. Pode ser ligado de novo depois.
     * Se era o ÚLTIMO sensor ligado (pausado conta como ligado), equivale a {@link #parar()}:
     * encerra o sistema e devolve o extrato; senão, devolve vazio.
     */
    java.util.Optional<ExtratoExecucao> desligarSensor(String sensorId);

    /**
     * Estado de cada sensor disponível (todos DESLIGADO com o sistema parado), em ordem de id. Rápido e sem bloqueio: pode ser chamado na EDT.
     */
    Map<String, EstadoSensor> estadosSensores();

    /** Registra quem recebe leituras/alertas/estatísticas (chamado de OUTRAS threads). */
    void registrarOuvinte(OuvinteTelemetria ouvinte);

    /** Demorado: consulta alertas persistidos (inclusive de execuções anteriores). */
    List<Alerta> buscarAlertas(FiltroAlertas filtro);

    /** Faixa PADRÃO de cada tipo (a que os sensores novos recebem), para exibição. */
    List<RegraExibida> regras();

    /**
     * Faixa de cada sensor disponível (id → regra), para os cartões e gráficos. Rápido e sem
     * bloqueio: pode ser chamado na EDT.
     */
    default Map<String, RegraExibida> regrasDosSensores() {
        Map<String, RegraExibida> r = new java.util.LinkedHashMap<>();
        configuracaoSensores().sensores().forEach(s -> r.put(s.id(), RegraExibida.de(s.regra())));
        return r;
    }

    /** Escala de severidade de uma faixa (quanto pode passar do limite em cada severidade), para exibição. */
    default List<DegrauDeSeveridade> escalaDe(RegraDeAnomalia faixa) {
        return AnalisadorDeAnomalias.escalaPara(faixa);
    }

    ConfiguracaoSimulacao configuracao();

    /** Só permitido com o sistema PARADO. */
    void aplicarConfiguracao(ConfiguracaoSimulacao nova);

    // ---- configuração dos sensores (aba Configurações). Todos: só com o sistema PARADO
    //      (senão IllegalStateException); valor inválido → IllegalArgumentException com a
    //      mensagem para a tela; banco sem gravar → FalhaDeOperacao, e nada muda.
    //      Demorados (gravam no banco): chamar em SwingWorker.

    /** Configuração atual: sensores, faixa de cada um, faixa padrão por tipo e intervalo. Rápido. */
    ConfiguracaoSensores configuracaoSensores();

    /** Cria um sensor do tipo, com o próximo id livre e a faixa padrão do tipo. Devolve a configuração nova. */
    ConfiguracaoSensores adicionarSensor(TipoSensor tipo);

    /**
     * Tira um sensor da configuração (a faixa dele some; os alertas já gravados continuam no
     * histórico). Não remove o último sensor da configuração.
     */
    ConfiguracaoSensores removerSensor(String sensorId);

    /** Muda a faixa aceita de UM sensor. */
    ConfiguracaoSensores alterarFaixaSensor(String sensorId, double minimo, double maximo);

    /** Muda a faixa padrão de um tipo (só vale para sensores criados depois). */
    ConfiguracaoSensores alterarFaixaPadrao(TipoSensor tipo, double minimo, double maximo);

    /** Muda o intervalo entre leituras (o mesmo para todos os sensores), em ms. */
    ConfiguracaoSensores alterarIntervalo(int intervaloMs);

    /** Demorado: grava o extrato em arquivo e devolve o caminho. */
    String exportarExtrato(ExtratoExecucao extrato);

    /**
     * Demorado: leituras da última execução encerrada (lidas do log), para os gráficos da
     * aba Monitoramento ao abrir o programa. Vazio quando não há execução registrada.
     */
    default HistoricoExecucao ultimaExecucao() {
        return HistoricoExecucao.VAZIO;
    }
}
