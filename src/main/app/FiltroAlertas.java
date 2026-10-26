package app;

import domain.Severidade;
import domain.TipoSensor;

import java.time.Duration;

/** Critérios da aba Histórico. Campos null significam "todos". */
public record FiltroAlertas(String sensorId, TipoSensor tipo, Severidade severidade, Duration periodo) {

    public static FiltroAlertas todos() {
        return new FiltroAlertas(null, null, null, null);
    }
}
