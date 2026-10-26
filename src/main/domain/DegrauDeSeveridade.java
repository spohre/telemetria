package domain;

import java.util.Objects;

/**
 * Um degrau da escala de severidade de um tipo de sensor: até quanto a leitura pode passar
 * do limite da faixa (para cima ou para baixo, na unidade do sensor) e ainda ter esta
 * severidade. O último degrau (CRITICA) não tem teto: {@code desvioMaximo} é infinito.
 */
public record DegrauDeSeveridade(Severidade severidade, double desvioMaximo) {

    public DegrauDeSeveridade {
        Objects.requireNonNull(severidade, "severidade");
        if (!(desvioMaximo > 0)) {
            throw new IllegalArgumentException("desvioMaximo deve ser positivo");
        }
    }

    /** {@code true} para o degrau sem teto (qualquer desvio maior que o degrau anterior). */
    public boolean semLimite() {
        return Double.isInfinite(desvioMaximo);
    }
}
