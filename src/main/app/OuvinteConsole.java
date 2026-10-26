package app;

import domain.Alerta;
import domain.LeituraTelemetria;
import infra.PersistenciaException;

import java.io.PrintStream;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mensagens de console para humanos acompanharem a execução (formato da skill de design):
 * uma linha ALERTA por alerta, AVISO quando a fila passa de 90 % e ERRO nas falhas.
 * Leituras normais NÃO são impressas (vão só para o log em arquivo).
 *
 * <p>Chamado por várias threads (consumidores e estatísticas); cada {@code println} de
 * {@link PrintStream} é atômico, então as linhas não se misturam.</p>
 */
public class OuvinteConsole implements OuvinteTelemetria {

    static final double LIMITE_AVISO_FILA = 0.90;
    static final double LIMITE_REARME_FILA = 0.50;

    private final PrintStream saida;
    private final Clock relogio;
    private final AtomicBoolean filaEmAviso = new AtomicBoolean(false);

    public OuvinteConsole(PrintStream saida, Clock relogio) {
        this.saida = Objects.requireNonNull(saida, "saida");
        this.relogio = Objects.requireNonNull(relogio, "relogio");
    }

    public OuvinteConsole(PrintStream saida) {
        this(saida, Clock.systemDefaultZone());
    }

    @Override
    public void aoProcessarLeitura(LeituraTelemetria leitura, Alerta alerta) {
        if (alerta != null) {
            saida.println(FormatoMensagens.consoleAlerta(alerta));
        }
    }

    /** AVISO uma vez ao passar de 90 % da capacidade; rearma quando a fila volta abaixo de 50 %. */
    @Override
    public void aoAtualizarEstatisticas(EstatisticasExecucao e) {
        double ocupacao = e.capacidadeFila() == 0 ? 0 : (double) e.tamanhoFila() / e.capacidadeFila();
        if (ocupacao > LIMITE_AVISO_FILA && filaEmAviso.compareAndSet(false, true)) {
            saida.println(FormatoMensagens.console(relogio.instant(), FormatoMensagens.Nivel.AVISO, "fila",
                    "Fila com " + FormatoMensagens.inteiro(e.tamanhoFila()) + "/"
                            + FormatoMensagens.inteiro(e.capacidadeFila()) + " leituras: consumidor não acompanha"));
        } else if (ocupacao < LIMITE_REARME_FILA) {
            filaEmAviso.set(false);
        }
    }

    @Override
    public void aoOcorrerFalha(String oQueAconteceu, String oQueFazer, Throwable causa) {
        String origem = causa instanceof PersistenciaException ? "banco" : "sistema";
        saida.println(FormatoMensagens.console(relogio.instant(), FormatoMensagens.Nivel.ERRO, origem,
                oQueAconteceu + " " + oQueFazer));
    }
}
