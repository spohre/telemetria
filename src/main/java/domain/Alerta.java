package domain;

import java.time.Instant;

public record Alerta(Long id, String sensorId, TipoSensor tipo, double valor,
                     String motivo, Instant instante) {
}