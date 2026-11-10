package domain;

import static org.junit.jupiter.api.Assertions.*;

import domain.EventoDeSimulacao.Tipo;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Agenda de eventos discretos — sem threads, sem relógio de parede. */
class AgendaDeEventosTest {

    private final AgendaDeEventos agenda = new AgendaDeEventos();
    private final List<EventoDeSimulacao> entregues = new ArrayList<>();

    @Test
    void entregaEventosEmOrdemDeTempoMesmoAgendadosForaDeOrdem() {
        agenda.agendar(30, Tipo.FIM_EPISODIO);
        agenda.agendar(10, Tipo.INICIO_EPISODIO);
        agenda.agendar(20, Tipo.FIM_EPISODIO);

        agenda.processarAte(100, entregues::add);

        assertEquals(List.of(10L, 20L, 30L), entregues.stream().map(EventoDeSimulacao::passo).toList());
        assertEquals(0, agenda.pendentes());
    }

    @Test
    void empateNoMesmoPassoRespeitaAOrdemDeAgendamento() {
        agenda.agendar(5, Tipo.FIM_EPISODIO);
        agenda.agendar(5, Tipo.INICIO_EPISODIO);

        agenda.processarAte(5, entregues::add);

        assertEquals(List.of(Tipo.FIM_EPISODIO, Tipo.INICIO_EPISODIO),
                entregues.stream().map(EventoDeSimulacao::tipo).toList());
        assertTrue(entregues.get(0).ordem() < entregues.get(1).ordem());
    }

    @Test
    void processarAteNaoEntregaEventosFuturos() {
        agenda.agendar(3, Tipo.INICIO_EPISODIO);
        agenda.agendar(4, Tipo.FIM_EPISODIO);

        agenda.processarAte(3, entregues::add);

        assertEquals(1, entregues.size());
        assertEquals(1, agenda.pendentes());
        assertEquals(4, agenda.proximo().orElseThrow().passo());
        assertEquals(3, agenda.agora());
    }

    @Test
    void eventoAgendadoPeloTratadorDentroDaJanelaEhEntregueNaMesmaChamada() {
        agenda.agendar(2, Tipo.INICIO_EPISODIO);

        agenda.processarAte(10, e -> {
            entregues.add(e);
            if (e.tipo() == Tipo.INICIO_EPISODIO) {
                agenda.agendar(e.passo() + 3, Tipo.FIM_EPISODIO);   // passo 5 — ainda dentro de 10
            }
        });

        assertEquals(List.of(2L, 5L), entregues.stream().map(EventoDeSimulacao::passo).toList());
    }

    @Test
    void naoAceitaAgendarNoPassado() {
        agenda.processarAte(10, entregues::add);
        assertThrows(IllegalArgumentException.class, () -> agenda.agendar(9, Tipo.INICIO_EPISODIO));
        assertDoesNotThrow(() -> agenda.agendar(10, Tipo.INICIO_EPISODIO));
    }

    @Test
    void relogioDaAgendaNaoVoltaNoTempo() {
        agenda.processarAte(10, entregues::add);
        assertThrows(IllegalArgumentException.class, () -> agenda.processarAte(9, entregues::add));
    }

    @Test
    void agendaVaziaNaoTemProximo() {
        assertTrue(agenda.proximo().isEmpty());
        assertEquals(0, agenda.pendentes());
    }

    @Test
    void eventoRejeitaDadosInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> new EventoDeSimulacao(-1, Tipo.INICIO_EPISODIO, 0));
        assertThrows(IllegalArgumentException.class, () -> new EventoDeSimulacao(0, Tipo.INICIO_EPISODIO, -1));
        assertThrows(NullPointerException.class, () -> new EventoDeSimulacao(0, null, 0));
    }
}
