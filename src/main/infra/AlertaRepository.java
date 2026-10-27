package infra;

import domain.Alerta;
import domain.RepositorioDeAlertas;
import domain.Severidade;
import domain.TipoSensor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repositório (DAO) de alertas — é o ÚNICO lugar do sistema que contém SQL de alertas.
 *
 * <p><b>Regras seguidas:</b></p>
 * <ul>
 *   <li>Somente {@link PreparedStatement} (nada de concatenar valores no SQL → sem SQL injection).</li>
 *   <li>Todo {@link Connection}, {@link PreparedStatement} e {@link ResultSet} é fechado por
 *       try-with-resources → sem vazamento de conexões, mesmo com exceção.</li>
 *   <li>Thread-safe: a classe não guarda estado mutável; cada chamada usa sua própria conexão.
 *       Vários consumidores da fila podem chamar {@link #salvar(Alerta)} ao mesmo tempo.</li>
 * </ul>
 */
public class AlertaRepository implements RepositorioDeAlertas {

    private static final String SQL_INSERIR = """
            INSERT INTO alerta (sensor_id, tipo_sensor, valor_lido, regra_violada, severidade, momento_leitura)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String SQL_COLUNAS =
            "SELECT id, sensor_id, tipo_sensor, valor_lido, regra_violada, severidade, momento_leitura FROM alerta ";

    private static final String SQL_LISTAR_TODOS = SQL_COLUNAS + "ORDER BY momento_leitura, id";
    private static final String SQL_POR_SENSOR = SQL_COLUNAS + "WHERE sensor_id = ? ORDER BY momento_leitura, id";
    private static final String SQL_POR_ID = SQL_COLUNAS + "WHERE id = ?";
    private static final String SQL_CONTAR = "SELECT COUNT(*) FROM alerta";
    private static final String SQL_SENSORES = "SELECT DISTINCT sensor_id FROM alerta ORDER BY sensor_id";

    /** Máximo de linhas devolvidas por {@link #buscar}, para a tela não travar com o histórico todo. */
    public static final int LIMITE_BUSCA = 1000;
    private static final String SQL_CONTAR_POR_TIPO =
            "SELECT tipo_sensor, COUNT(*) AS qtd FROM alerta GROUP BY tipo_sensor";

    private final FabricaDeConexoes fabrica;

    public AlertaRepository(FabricaDeConexoes fabrica) {
        this.fabrica = fabrica;
    }

    /** Persiste um alerta e devolve a mesma informação já com o id gerado pelo banco. */
    @Override
    public Alerta salvar(Alerta alerta) {
        if (alerta.id() != null) {
            throw new IllegalArgumentException("Alerta já persistido (id=" + alerta.id() + ")");
        }
        try (Connection conn = fabrica.abrir();
             PreparedStatement ps = conn.prepareStatement(SQL_INSERIR, Statement.RETURN_GENERATED_KEYS)) {

            preencherParametros(ps, alerta);
            ps.executeUpdate();

            try (ResultSet chaves = ps.getGeneratedKeys()) {
                if (!chaves.next()) {
                    throw new PersistenciaException("Banco não retornou o id gerado", null);
                }
                return alerta.comId(chaves.getLong(1));
            }
        } catch (SQLException e) {
            throw new PersistenciaException("Falha ao salvar alerta do sensor " + alerta.sensorId(), e);
        }
    }

    /**
     * Persiste vários alertas em uma única transação (batch).
     * Útil sob estresse (Semana 5): o consumidor acumula alertas e grava em lote.
     * Ou grava todos, ou nenhum (rollback).
     */
    public int salvarEmLote(List<Alerta> alertas) {
        if (alertas.isEmpty()) {
            return 0;
        }
        try (Connection conn = fabrica.abrir()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(SQL_INSERIR)) {
                for (Alerta alerta : alertas) {
                    preencherParametros(ps, alerta);
                    ps.addBatch();
                }
                int total = 0;
                for (int afetadas : ps.executeBatch()) {
                    total += Math.max(afetadas, 0);
                }
                conn.commit();
                return total;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenciaException("Falha ao salvar lote de " + alertas.size() + " alertas", e);
        }
    }

    public List<Alerta> listarTodos() {
        try (Connection conn = fabrica.abrir();
             PreparedStatement ps = conn.prepareStatement(SQL_LISTAR_TODOS);
             ResultSet rs = ps.executeQuery()) {
            return mapearLista(rs);
        } catch (SQLException e) {
            throw new PersistenciaException("Falha ao listar alertas", e);
        }
    }

    public List<Alerta> buscarPorSensor(String sensorId) {
        try (Connection conn = fabrica.abrir();
             PreparedStatement ps = conn.prepareStatement(SQL_POR_SENSOR)) {
            ps.setString(1, sensorId);
            try (ResultSet rs = ps.executeQuery()) {
                return mapearLista(rs);
            }
        } catch (SQLException e) {
            throw new PersistenciaException("Falha ao buscar alertas do sensor " + sensorId, e);
        }
    }

    /**
     * Busca filtrada para a aba Histórico. Cada parâmetro {@code null} (ou sensor em branco)
     * significa "sem filtro" naquele campo. Mais recentes primeiro, no máximo
     * {@link #LIMITE_BUSCA} linhas.
     *
     * <p>O WHERE é montado só com <b>placeholders</b> ({@code ?}); os valores entram por
     * {@link PreparedStatement}, nunca concatenados no SQL.</p>
     *
     * @param desde só alertas com momento da leitura a partir deste instante
     */
    @Override
    public List<Alerta> buscar(String sensorId, TipoSensor tipo, Severidade severidade, Instant desde) {
        return buscar(sensorId, tipo, severidade, desde, LIMITE_BUSCA);
    }

    List<Alerta> buscar(String sensorId, TipoSensor tipo, Severidade severidade, Instant desde, int limite) {
        StringBuilder sql = new StringBuilder(SQL_COLUNAS).append("WHERE 1 = 1");
        List<Object> valores = new ArrayList<>();
        if (sensorId != null && !sensorId.isBlank()) {
            sql.append(" AND sensor_id = ?");
            valores.add(sensorId.trim());
        }
        if (tipo != null) {
            sql.append(" AND tipo_sensor = ?");
            valores.add(tipo.name());
        }
        if (severidade != null) {
            sql.append(" AND severidade = ?");
            valores.add(severidade.name());
        }
        if (desde != null) {
            sql.append(" AND momento_leitura >= ?");
            valores.add(Timestamp.from(desde));
        }
        sql.append(" ORDER BY momento_leitura DESC, id DESC FETCH FIRST ? ROWS ONLY");
        valores.add(limite);

        try (Connection conn = fabrica.abrir();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < valores.size(); i++) {
                ps.setObject(i + 1, valores.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return mapearLista(rs);
            }
        } catch (SQLException e) {
            throw new PersistenciaException("Falha ao buscar alertas no histórico", e);
        }
    }

    public Optional<Alerta> buscarPorId(long id) {
        try (Connection conn = fabrica.abrir();
             PreparedStatement ps = conn.prepareStatement(SQL_POR_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapear(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenciaException("Falha ao buscar alerta id=" + id, e);
        }
    }

    @Override
    public List<String> sensoresComAlertas() {
        try (Connection conn = fabrica.abrir();
             PreparedStatement ps = conn.prepareStatement(SQL_SENSORES);
             ResultSet rs = ps.executeQuery()) {
            List<String> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
            return ids;
        } catch (SQLException e) {
            throw new PersistenciaException("Falha ao listar os sensores com alertas", e);
        }
    }

    public long contar() {
        try (Connection conn = fabrica.abrir();
             PreparedStatement ps = conn.prepareStatement(SQL_CONTAR);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new PersistenciaException("Falha ao contar alertas", e);
        }
    }

    public Map<TipoSensor, Long> contarPorTipo() {
        Map<TipoSensor, Long> resultado = new EnumMap<>(TipoSensor.class);
        try (Connection conn = fabrica.abrir();
             PreparedStatement ps = conn.prepareStatement(SQL_CONTAR_POR_TIPO);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                resultado.put(TipoSensor.valueOf(rs.getString("tipo_sensor")), rs.getLong("qtd"));
            }
            return resultado;
        } catch (SQLException e) {
            throw new PersistenciaException("Falha ao contar alertas por tipo", e);
        }
    }

    // ------------------------------------------------------------------ mapeamento

    private static void preencherParametros(PreparedStatement ps, Alerta a) throws SQLException {
        ps.setString(1, a.sensorId());
        ps.setString(2, a.tipo().name());
        ps.setDouble(3, a.valorLido());
        ps.setString(4, a.regraViolada());
        ps.setString(5, a.severidade().name());
        ps.setTimestamp(6, Timestamp.from(a.momentoLeitura()));
    }

    private static List<Alerta> mapearLista(ResultSet rs) throws SQLException {
        List<Alerta> lista = new ArrayList<>();
        while (rs.next()) {
            lista.add(mapear(rs));
        }
        return lista;
    }

    private static Alerta mapear(ResultSet rs) throws SQLException {
        return new Alerta(
                rs.getLong("id"),
                rs.getString("sensor_id"),
                TipoSensor.valueOf(rs.getString("tipo_sensor")),
                rs.getDouble("valor_lido"),
                rs.getString("regra_violada"),
                Severidade.valueOf(rs.getString("severidade")),
                rs.getTimestamp("momento_leitura").toInstant());
    }
}
