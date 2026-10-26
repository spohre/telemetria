package app;

import domain.Alerta;
import domain.LeituraTelemetria;

/**
 * Callbacks disparados pelas threads do sistema (consumidor da fila, temporizador de
 * estatísticas). <b>Atenção:</b> NÃO são chamados na EDT — a implementação Swing deve
 * repassar tudo com {@code SwingUtilities.invokeLater}.
 */
public interface OuvinteTelemetria {

    /** Leitura processada pelo consumidor. {@code alerta} é null se a leitura foi normal. */
    void aoProcessarLeitura(LeituraTelemetria leitura, Alerta alerta);

    /** Estatísticas consolidadas, enviadas periodicamente (≈ 1 s). */
    void aoAtualizarEstatisticas(EstatisticasExecucao estatisticas);

    /** Falha não fatal (ex.: banco indisponível) para exibir ao usuário. */
    void aoOcorrerFalha(String oQueAconteceu, String oQueFazer, Throwable causa);
}
