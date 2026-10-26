package app;

import domain.Alerta;

import java.util.List;

/**
 * Tudo o que a interface pode pedir ao sistema. A View só conhece esta interface —
 * nunca threads, fila, analisador, JDBC ou arquivos diretamente.
 *
 * <p>Métodos marcados como "demorado" devem ser chamados pela View dentro de um
 * {@link javax.swing.SwingWorker}, nunca na EDT.</p>
 */
public interface ControladorMonitoramento {

    /** Liga sensores e consumidores. */
    void iniciar();

    /** Suspende a produção de leituras (threads continuam vivas). */
    void pausar();

    /** Retoma após pausa. */
    void retomar();

    /** Demorado: interrompe as threads, drena a fila e devolve o extrato da execução. */
    ExtratoExecucao parar();

    FaseSistema fase();

    /** Registra quem recebe leituras/alertas/estatísticas (chamado de OUTRAS threads). */
    void registrarOuvinte(OuvinteTelemetria ouvinte);

    /** Demorado: consulta alertas persistidos (inclusive de execuções anteriores). */
    List<Alerta> buscarAlertas(FiltroAlertas filtro);

    /** Regras de anomalia em vigor, para exibição. */
    List<RegraExibida> regras();

    ConfiguracaoSimulacao configuracao();

    /** Só permitido com o sistema PARADO. */
    void aplicarConfiguracao(ConfiguracaoSimulacao nova);

    /** Demorado: grava o extrato em arquivo e devolve o caminho. */
    String exportarExtrato(ExtratoExecucao extrato);
}
