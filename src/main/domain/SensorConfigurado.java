package domain;

import java.util.Objects;

/**
 * Um sensor da configuração: id (ex.: TEMP-02) e a faixa aceita só dele. O tipo vem da
 * própria faixa, então os dois nunca discordam.
 */
public record SensorConfigurado(String id, RegraDeAnomalia regra) {

    public SensorConfigurado {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(regra, "regra");
        if (id.isBlank()) {
            throw new IllegalArgumentException("O id do sensor não pode ser vazio");
        }
    }

    public TipoSensor tipo() {
        return regra.tipo();
    }

    /** Mesmo sensor com outra faixa (do mesmo tipo). */
    public SensorConfigurado comFaixa(double minimo, double maximo) {
        return new SensorConfigurado(id, regra.com(minimo, maximo));
    }
}
