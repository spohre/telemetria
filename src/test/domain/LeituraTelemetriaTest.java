package domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LeituraTelemetriaTest {

    private static final Instant T = Instant.parse("2026-10-20T12:00:00Z");

    @Test
    void rejeitaSensorIdVazio() {
        assertThrows(IllegalArgumentException.class,
                () -> new LeituraTelemetria("  ", TipoSensor.TEMPERATURA, 10, T, 1L));
    }

    @Test
    void rejeitaValorNaNOuInfinito() {
        assertThrows(IllegalArgumentException.class,
                () -> new LeituraTelemetria("T1", TipoSensor.TEMPERATURA, Double.NaN, T, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new LeituraTelemetria("T1", TipoSensor.TEMPERATURA, Double.POSITIVE_INFINITY, T, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new LeituraTelemetria("T1", TipoSensor.TEMPERATURA, Double.NEGATIVE_INFINITY, T, 1L));
    }

    @Test
    void rejeitaSequenciaNegativa() {
        assertThrows(IllegalArgumentException.class,
                () -> new LeituraTelemetria("T1", TipoSensor.TEMPERATURA, 10, T, -1L));
    }

    @Test
    void construtorSemSequenciaUsaZero() {
        LeituraTelemetria l = new LeituraTelemetria("T1", TipoSensor.TEMPERATURA, 10, T);
        assertEquals(0L, l.sequencia());
        assertEquals(new LeituraTelemetria("T1", TipoSensor.TEMPERATURA, 10, T, 0L), l);
    }
}
