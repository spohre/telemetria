package domain;

import java.time.Instant;
import java.util.Objects;

public record LeituraTelemetria(String sensorId, TipoSensor tipo, double valor,
                                Instant instante, long sequencia) {

    public LeituraTelemetria {
        if (sensorId == null || sensorId.isBlank()) {
            throw new IllegalArgumentException("sensorId não pode ser vazio");
        }
        if (!Double.isFinite(valor)) {
            throw new IllegalArgumentException("valor deve ser finito");
        }
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(instante, "instante");
    }
}