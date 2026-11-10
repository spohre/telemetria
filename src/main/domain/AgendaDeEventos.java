package domain;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Consumer;

/**
 * Agenda (lista de eventos futuros) de uma simulação de eventos discretos.
 *
 * <p>Guarda os eventos numa {@link PriorityQueue} ordenada pelo passo em que acontecem; no
 * mesmo passo, sai primeiro o que foi agendado antes. {@link #processarAte} avança o relógio
 * da agenda e entrega, em ordem, todos os eventos que já chegaram — inclusive os agendados
 * pelo próprio tratador dentro da janela.</p>
 *
 * <p>O relógio só anda para frente e não se agenda nada no passado.</p>
 *
 * <p><b>Concorrência:</b> não é thread-safe e não precisa ser — cada sensor tem a sua agenda,
 * usada só pela thread daquele sensor (confinamento de thread).</p>
 */
public class AgendaDeEventos {

    private static final Comparator<EventoDeSimulacao> ORDEM_DE_EXECUCAO =
            Comparator.comparingLong(EventoDeSimulacao::passo).thenComparingLong(EventoDeSimulacao::ordem);

    private final PriorityQueue<EventoDeSimulacao> eventos = new PriorityQueue<>(ORDEM_DE_EXECUCAO);
    private long proximaOrdem;
    private long agora;

    /**
     * Agenda um evento.
     *
     * @throws IllegalArgumentException se o passo já passou
     */
    public EventoDeSimulacao agendar(long passo, EventoDeSimulacao.Tipo tipo) {
        if (passo < agora) {
            throw new IllegalArgumentException("não dá para agendar no passado: passo " + passo + " < agora " + agora);
        }
        EventoDeSimulacao evento = new EventoDeSimulacao(passo, tipo, proximaOrdem++);
        eventos.add(evento);
        return evento;
    }

    /**
     * Avança o relógio até {@code passo} e entrega ao tratador, em ordem, cada evento com
     * passo menor ou igual a ele.
     *
     * @throws IllegalArgumentException se {@code passo} for anterior ao relógio atual
     */
    public void processarAte(long passo, Consumer<EventoDeSimulacao> tratador) {
        Objects.requireNonNull(tratador, "tratador");
        if (passo < agora) {
            throw new IllegalArgumentException("o relógio não volta: passo " + passo + " < agora " + agora);
        }
        while (!eventos.isEmpty() && eventos.peek().passo() <= passo) {
            EventoDeSimulacao evento = eventos.poll();
            agora = evento.passo();          // o tratador agenda a partir do instante do evento
            tratador.accept(evento);
        }
        agora = passo;
    }

    /** Próximo evento, sem retirá-lo. */
    public Optional<EventoDeSimulacao> proximo() {
        return Optional.ofNullable(eventos.peek());
    }

    /** Quantos eventos ainda não aconteceram. */
    public int pendentes() {
        return eventos.size();
    }

    /** Relógio da agenda: último passo processado. */
    public long agora() {
        return agora;
    }
}
