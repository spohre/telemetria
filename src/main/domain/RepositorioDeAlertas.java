package domain;

import java.time.Instant;
import java.util.List;

/**
 * Porta de persistência de alertas. O domínio e a aplicação só conhecem esta interface;
 * a implementação com SQL fica em {@code infra.AlertaRepository}.
 *
 * <p>Implementações lançam {@link RuntimeException} quando o armazenamento está indisponível.</p>
 */
public interface RepositorioDeAlertas {

    /** Persiste o alerta e devolve a mesma informação já com o id gerado. */
    Alerta salvar(Alerta alerta);

    /**
     * Alertas persistidos (inclusive de execuções anteriores), mais recentes primeiro.
     * Parâmetros {@code null} significam "sem filtro" naquele campo.
     *
     * @param desde só alertas cuja leitura aconteceu a partir deste instante
     */
    List<Alerta> buscar(String sensorId, TipoSensor tipo, Severidade severidade, Instant desde);

    /**
     * Ids de todos os sensores que já têm alerta gravado (inclusive de execuções anteriores),
     * em ordem alfabética — para o filtro "Sensor" do histórico. Implementações com banco
     * devem sobrescrever com uma consulta própria (esta versão padrão só vê o que {@link #buscar} devolve).
     */
    default List<String> sensoresComAlertas() {
        return buscar(null, null, null, null).stream().map(Alerta::sensorId).distinct().sorted().toList();
    }
}
