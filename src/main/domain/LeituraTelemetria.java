package domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Medição emitida por um sensor. Imutável (record) — pode trafegar entre threads
 * sem sincronização adicional.
 *
 * @param sequencia número da leitura dentro do seu sensor (1, 2, 3...). Permite provar,
 *                  no teste de estresse, que nenhuma leitura foi perdida nem duplicada.
 *                  Vale 0 quando a leitura não vem de um {@code Sensor} (demos e testes).
 */
public record LeituraTelemetria(String sensorId, TipoSensor tipo, double valor, Instant momento, long sequencia) {

    public LeituraTelemetria {
        Objects.requireNonNull(sensorId, "sensorId");
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(momento, "momento");
        if (sensorId.isBlank()) {
            throw new IllegalArgumentException("sensorId não pode ser vazio");
        }
        if (!Double.isFinite(valor)) {
            throw new IllegalArgumentException("valor deve ser finito (recebido: " + valor + ")");
        }
        if (sequencia < 0) {
            throw new IllegalArgumentException("sequencia não pode ser negativa");
        }
    }

    /** Leitura sem número de sequência (demos, testes e telas). */
    public LeituraTelemetria(String sensorId, TipoSensor tipo, double valor, Instant momento) {
        this(sensorId, tipo, valor, momento, 0L);
    }
}
