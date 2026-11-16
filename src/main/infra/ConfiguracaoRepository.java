package infra;

import domain.ConfiguracaoSensores;
import domain.RegraDeAnomalia;
import domain.RepositorioDeConfiguracao;
import domain.SensorConfigurado;
import domain.TipoSensor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repositório da configuração dos sensores — ÚNICO lugar do sistema com SQL das tabelas
 * {@code config_geral}, {@code config_tipo} e {@code config_sensor}.
 *
 * <ul>
 *   <li>{@link #salvar} grava a configuração <b>inteira numa única transação</b>: apaga as três
 *       tabelas e regrava tudo; qualquer erro faz rollback, então o banco nunca fica com uma
 *       configuração pela metade.</li>
 *   <li>Somente {@link PreparedStatement}; Connection/Statement/ResultSet em try-with-resources.</li>
 *   <li>Sem estado mutável: cada chamada usa a sua própria conexão.</li>
 *   <li>{@link SQLException} (e dado inválido lido do banco) vira {@link PersistenciaException}.</li>
 * </ul>
 */
public class ConfiguracaoRepository implements RepositorioDeConfiguracao {

    static final String CHAVE_INTERVALO = "intervalo_ms";

    private static final String SQL_APAGAR_SENSORES = "DELETE FROM config_sensor";
    private static final String SQL_APAGAR_TIPOS = "DELETE FROM config_tipo";
    private static final String SQL_APAGAR_GERAL = "DELETE FROM config_geral";

    private static final String SQL_INSERIR_GERAL = "INSERT INTO config_geral (chave, valor) VALUES (?, ?)";
    private static final String SQL_INSERIR_TIPO =
            "INSERT INTO config_tipo (tipo_sensor, minimo, maximo) VALUES (?, ?, ?)";
    private static final String SQL_INSERIR_SENSOR =
            "INSERT INTO config_sensor (sensor_id, tipo_sensor, minimo, maximo, ordem) VALUES (?, ?, ?, ?, ?)";

    private static final String SQL_LER_GERAL = "SELECT valor FROM config_geral WHERE chave = ?";
    private static final String SQL_LER_TIPOS = "SELECT tipo_sensor, minimo, maximo FROM config_tipo";
    private static final String SQL_LER_SENSORES =
            "SELECT sensor_id, tipo_sensor, minimo, maximo FROM config_sensor ORDER BY ordem";

    private final FabricaDeConexoes fabrica;

    public ConfiguracaoRepository(FabricaDeConexoes fabrica) {
        this.fabrica = fabrica;
    }

    @Override
    public void salvar(ConfiguracaoSensores cfg) {
        try (Connection conn = fabrica.abrir()) {
            conn.setAutoCommit(false);
            try {
                executar(conn, SQL_APAGAR_SENSORES);
                executar(conn, SQL_APAGAR_TIPOS);
                executar(conn, SQL_APAGAR_GERAL);

                try (PreparedStatement ps = conn.prepareStatement(SQL_INSERIR_GERAL)) {
                    ps.setString(1, CHAVE_INTERVALO);
                    ps.setString(2, Integer.toString(cfg.intervaloMs()));
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(SQL_INSERIR_TIPO)) {
                    for (RegraDeAnomalia r : cfg.padroes().values()) {
                        ps.setString(1, r.tipo().name());
                        ps.setDouble(2, r.minimo());
                        ps.setDouble(3, r.maximo());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = conn.prepareStatement(SQL_INSERIR_SENSOR)) {
                    int ordem = 1;
                    for (SensorConfigurado s : cfg.sensores()) {
                        ps.setString(1, s.id());
                        ps.setString(2, s.tipo().name());
                        ps.setDouble(3, s.regra().minimo());
                        ps.setDouble(4, s.regra().maximo());
                        ps.setInt(5, ordem++);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback();
                } catch (SQLException falhaRollback) {
                    e.addSuppressed(falhaRollback);       // o erro que importa é o original
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenciaException("Falha ao salvar a configuração dos sensores", e);
        }
    }

    @Override
    public Optional<ConfiguracaoSensores> carregar() {
        try (Connection conn = fabrica.abrir()) {
            List<SensorConfigurado> sensores = lerSensores(conn);
            if (sensores.isEmpty()) {
                return Optional.empty();                 // primeira execução: nada gravado ainda
            }
            Map<TipoSensor, RegraDeAnomalia> padroes = lerPadroes(conn);
            int intervalo = lerIntervalo(conn);
            return Optional.of(new ConfiguracaoSensores(intervalo, padroes, sensores));
        } catch (SQLException e) {
            throw new PersistenciaException("Falha ao ler a configuração dos sensores", e);
        } catch (IllegalArgumentException e) {           // tipo desconhecido, número inválido, faixa inválida…
            throw new PersistenciaException("A configuração gravada no banco é inválida: " + e.getMessage(), e);
        }
    }

    private static List<SensorConfigurado> lerSensores(Connection conn) throws SQLException {
        List<SensorConfigurado> sensores = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_LER_SENSORES);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TipoSensor tipo = TipoSensor.valueOf(rs.getString("tipo_sensor"));
                sensores.add(new SensorConfigurado(rs.getString("sensor_id"),
                        new RegraDeAnomalia(tipo, rs.getDouble("minimo"), rs.getDouble("maximo"))));
            }
        }
        return sensores;
    }

    /** Faixas padrão gravadas; tipo sem linha (ex.: tipo novo no sistema) fica com o padrão do projeto. */
    private static Map<TipoSensor, RegraDeAnomalia> lerPadroes(Connection conn) throws SQLException {
        Map<TipoSensor, RegraDeAnomalia> padroes = new EnumMap<>(RegraDeAnomalia.padroes());
        try (PreparedStatement ps = conn.prepareStatement(SQL_LER_TIPOS);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TipoSensor tipo = TipoSensor.valueOf(rs.getString("tipo_sensor"));
                padroes.put(tipo, new RegraDeAnomalia(tipo, rs.getDouble("minimo"), rs.getDouble("maximo")));
            }
        }
        return padroes;
    }

    /** Intervalo gravado; sem linha, o padrão (200 ms). */
    private static int lerIntervalo(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_LER_GERAL)) {
            ps.setString(1, CHAVE_INTERVALO);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Integer.parseInt(rs.getString(1).strip()) : ConfiguracaoSensores.INTERVALO_PADRAO_MS;
            }
        }
    }

    private static void executar(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
