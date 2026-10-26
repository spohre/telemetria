package domain;

import java.util.Objects;

/**
 * Faixa de valores aceita para um tipo de sensor. Os limites são inclusivos:
 * um valor exatamente no mínimo ou no máximo é normal.
 */
public record RegraDeAnomalia(TipoSensor tipo, double minimo, double maximo) {

    public RegraDeAnomalia {
        Objects.requireNonNull(tipo, "tipo");
        if (!(minimo < maximo)) {
            throw new IllegalArgumentException("mínimo deve ser menor que máximo");
        }
    }

    /** Largura da faixa aceita (base para calcular a severidade do desvio). */
    public double largura() {
        return maximo - minimo;
    }
}
