package domain;

import java.util.Objects;

/**
 * Um evento da simulação de eventos discretos: algo que acontece num instante do relógio
 * simulado e muda o estado do sensor simulado.
 *
 * @param passo instante simulado em que o evento acontece (1 passo = 1 intervalo do sensor)
 * @param tipo  o que acontece
 * @param ordem número de agendamento — desempata eventos no mesmo passo (o agendado antes vem antes)
 */
public record EventoDeSimulacao(long passo, Tipo tipo, long ordem) {

    /** Eventos que mudam o estado do sensor simulado, na ordem em que acontecem num episódio. */
    public enum Tipo {
        /** O sensor começa a aquecer: o valor sobe em direção ao limite. */
        INICIO_AQUECIMENTO,
        /** Começa a crise: as leituras passam do limite (se o episódio passa do limite). */
        INICIO_EPISODIO,
        /** A crise termina: o sensor começa a esfriar. */
        FIM_EPISODIO,
        /** O sensor voltou ao normal. */
        FIM_ESFRIAMENTO
    }

    public EventoDeSimulacao {
        Objects.requireNonNull(tipo, "tipo");
        if (passo < 0) {
            throw new IllegalArgumentException("passo não pode ser negativo");
        }
        if (ordem < 0) {
            throw new IllegalArgumentException("ordem não pode ser negativa");
        }
    }
}
