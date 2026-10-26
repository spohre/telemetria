package domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Alerta gerado quando uma leitura viola uma regra de anomalia.
 *
 * <p>O {@code id} é {@code null} enquanto o alerta não foi persistido; o repositório
 * devolve uma nova instância com o id gerado pelo banco ({@link #comId(long)}).
 * O domínio não conhece SQL — apenas carrega os dados.</p>
 */
public record Alerta(
        Long id,
        String sensorId,
        TipoSensor tipo,
        double valorLido,
        String regraViolada,
        Severidade severidade,
        Instant momentoLeitura) {

    public Alerta {
        Objects.requireNonNull(sensorId, "sensorId");
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(regraViolada, "regraViolada");
        Objects.requireNonNull(severidade, "severidade");
        Objects.requireNonNull(momentoLeitura, "momentoLeitura");
    }

    /** Cria um alerta (ainda não persistido) a partir da leitura que violou a regra. */
    public static Alerta de(LeituraTelemetria leitura, String regraViolada, Severidade severidade) {
        return new Alerta(null, leitura.sensorId(), leitura.tipo(), leitura.valor(),
                regraViolada, severidade, leitura.momento());
    }

    /** Cópia com o identificador atribuído pelo banco. */
    public Alerta comId(long novoId) {
        return new Alerta(novoId, sensorId, tipo, valorLido, regraViolada, severidade, momentoLeitura);
    }
}
