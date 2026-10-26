package app;

import domain.DegrauDeSeveridade;
import domain.Severidade;
import domain.TipoSensor;

import java.util.List;
import java.util.Objects;

/**
 * Regra de anomalia em formato de exibição (a regra real vive no AnalisadorDeAnomalias).
 *
 * @param severidade severidade mínima de uma violação (ou a única, quando não há escala)
 * @param escala     até quanto a leitura pode passar do limite em cada severidade; vazia
 *                   quando a regra tem severidade fixa (ex.: ControladorSimulado)
 */
public record RegraExibida(TipoSensor tipo, double minimo, double maximo, Severidade severidade,
                           List<DegrauDeSeveridade> escala) {

    public RegraExibida {
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(severidade, "severidade");
        escala = escala == null ? List.of() : List.copyOf(escala);
    }

    /** Regra com severidade fixa, sem escala. */
    public RegraExibida(TipoSensor tipo, double minimo, double maximo, Severidade severidade) {
        this(tipo, minimo, maximo, severidade, List.of());
    }
}
