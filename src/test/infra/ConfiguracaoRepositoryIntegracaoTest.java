package infra;

import static org.junit.jupiter.api.Assertions.*;

import domain.ConfiguracaoSensores;
import domain.RegraDeAnomalia;
import domain.SensorConfigurado;
import domain.TipoSensor;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Testes de INTEGRAÇÃO do ConfiguracaoRepository (banco H2 real; em memória, exceto a prova
 * de persistência em arquivo).
 */
@Tag("integracao")
class ConfiguracaoRepositoryIntegracaoTest {

    private FabricaDeConexoes fabrica;
    private ConfiguracaoRepository repositorio;

    /** Padrão + TEMP-02 (10–60) + PRES-02, padrão de PRESSAO 2–6, intervalo 300 ms. */
    private static final ConfiguracaoSensores PERSONALIZADA = ConfiguracaoSensores.padrao()
            .comSensorNovo(TipoSensor.TEMPERATURA)
            .comFaixaDoSensor("TEMP-02", 10, 60)
            .comFaixaPadrao(TipoSensor.PRESSAO, 2, 6)
            .comSensorNovo(TipoSensor.PRESSAO)
            .comIntervalo(300);

    @BeforeEach
    void prepararBancoLimpo() {
        fabrica = new FabricaDeConexoes("jdbc:h2:mem:cfg_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new InicializadorBanco(fabrica).criarEstrutura();
        repositorio = new ConfiguracaoRepository(fabrica);
    }

    private int contar(String tabela) throws SQLException {
        try (Connection c = fabrica.abrir(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tabela)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("banco novo (primeira execução) não tem configuração")
    void bancoNovoDevolveVazio() {
        assertTrue(repositorio.carregar().isEmpty());
    }

    @Test
    @DisplayName("salvar e carregar devolve a mesma configuração (sensores extras, faixas e ordem)")
    void salvarECarregar() {
        repositorio.salvar(PERSONALIZADA);

        ConfiguracaoSensores lida = repositorio.carregar().orElseThrow();

        assertEquals(PERSONALIZADA, lida);
        assertEquals(List.of("TEMP-01", "VEL-01", "PRES-01", "UMID-01", "TEMP-02", "PRES-02"),
                lida.sensores().stream().map(SensorConfigurado::id).toList(), "ordem de criação preservada");
        assertEquals(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 10, 60), lida.regraDo("TEMP-02").orElseThrow());
        assertEquals(new RegraDeAnomalia(TipoSensor.PRESSAO, 2, 6), lida.regraDo("PRES-02").orElseThrow());
        assertEquals(new RegraDeAnomalia(TipoSensor.PRESSAO, 1, 8), lida.regraDo("PRES-01").orElseThrow());
        assertEquals(300, lida.intervaloMs());
    }

    @Test
    @DisplayName("o padrão também faz a ida e volta sem perder nada")
    void padraoIdaEVolta() {
        repositorio.salvar(ConfiguracaoSensores.padrao());
        assertEquals(ConfiguracaoSensores.padrao(), repositorio.carregar().orElseThrow());
    }

    @Test
    @DisplayName("salvar de novo substitui a configuração anterior (não acumula linhas)")
    void salvarDeNovoSubstitui() throws SQLException {
        repositorio.salvar(PERSONALIZADA);
        repositorio.salvar(ConfiguracaoSensores.padrao());

        assertEquals(ConfiguracaoSensores.padrao(), repositorio.carregar().orElseThrow());
        assertEquals(4, contar("config_sensor"));
        assertEquals(TipoSensor.values().length, contar("config_tipo"));
        assertEquals(1, contar("config_geral"));
    }

    @Test
    @DisplayName("falha no meio da gravação desfaz tudo: a configuração anterior continua intacta")
    void falhaNoMeioFazRollback() throws SQLException {
        repositorio.salvar(PERSONALIZADA);
        // O domínio aceita qualquer id não vazio; o banco limita sensor_id a 50 caracteres.
        // O INSERT desse sensor falha DEPOIS dos DELETEs e dos outros INSERTs da transação.
        ConfiguracaoSensores comIdLongo = new ConfiguracaoSensores(500, PERSONALIZADA.padroes(),
                List.of(new SensorConfigurado("TEMP-01", RegraDeAnomalia.padraoDo(TipoSensor.TEMPERATURA)),
                        new SensorConfigurado("X".repeat(60), RegraDeAnomalia.padraoDo(TipoSensor.UMIDADE))));

        PersistenciaException e = assertThrows(PersistenciaException.class, () -> repositorio.salvar(comIdLongo));
        assertNotNull(e.getCause(), "a SQLException original vai junto");

        assertEquals(PERSONALIZADA, repositorio.carregar().orElseThrow());
        assertEquals(6, contar("config_sensor"));
    }

    @Test
    @DisplayName("texto malicioso no id é gravado como dado, sem quebrar o SQL")
    void idMaliciosoNaoQuebraOSql() throws SQLException {
        String malicioso = "X'); DROP TABLE alerta; --";
        ConfiguracaoSensores cfg = new ConfiguracaoSensores(200, ConfiguracaoSensores.padrao().padroes(),
                List.of(new SensorConfigurado(malicioso, RegraDeAnomalia.padraoDo(TipoSensor.VELOCIDADE))));

        repositorio.salvar(cfg);

        assertEquals(malicioso, repositorio.carregar().orElseThrow().sensores().get(0).id());
        assertEquals(0, contar("alerta"), "a tabela alerta continua existindo");
    }

    @Test
    @DisplayName("as restrições do banco recusam faixa invertida mesmo sem passar pelo domínio")
    void bancoRecusaFaixaInvertida() throws SQLException {
        try (Connection c = fabrica.abrir(); Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.executeUpdate(
                    "INSERT INTO config_sensor (sensor_id, tipo_sensor, minimo, maximo, ordem) "
                            + "VALUES ('TEMP-09', 'TEMPERATURA', 50, 10, 1)"));
            assertThrows(SQLException.class, () -> st.executeUpdate(
                    "INSERT INTO config_tipo (tipo_sensor, minimo, maximo) VALUES ('LUZ', 0, 1)"));
        }
    }

    @Test
    @DisplayName("dado inválido no banco vira PersistenciaException com mensagem clara")
    void dadoInvalidoNoBanco() throws SQLException {
        repositorio.salvar(ConfiguracaoSensores.padrao());
        try (Connection c = fabrica.abrir(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE config_geral SET valor = 'abc' WHERE chave = 'intervalo_ms'");
        }
        PersistenciaException e = assertThrows(PersistenciaException.class, () -> repositorio.carregar());
        assertTrue(e.getMessage().contains("configuração"), e.getMessage());
    }

    @Test
    @DisplayName("tipo sem faixa padrão gravada (ex.: tipo novo no sistema) usa o padrão do projeto")
    void tipoSemPadraoGravadoUsaOPadraoDoProjeto() throws SQLException {
        repositorio.salvar(PERSONALIZADA);
        try (Connection c = fabrica.abrir(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM config_tipo WHERE tipo_sensor = 'UMIDADE'");
        }
        ConfiguracaoSensores lida = repositorio.carregar().orElseThrow();
        assertEquals(RegraDeAnomalia.padraoDo(TipoSensor.UMIDADE), lida.padraoDo(TipoSensor.UMIDADE));
        assertEquals(new RegraDeAnomalia(TipoSensor.PRESSAO, 2, 6), lida.padraoDo(TipoSensor.PRESSAO));
    }

    @Test
    @DisplayName("prova de persistência: banco em ARQUIVO, outra fábrica e outro repositório recuperam tudo")
    void persisteEmArquivoEntreExecucoes(@TempDir Path pasta) {
        String url = "jdbc:h2:" + pasta.resolve("telemetria").toAbsolutePath();   // sem DB_CLOSE_DELAY: fecha ao fim

        FabricaDeConexoes primeira = new FabricaDeConexoes(url, "sa", "");
        new InicializadorBanco(primeira).criarEstrutura();
        new ConfiguracaoRepository(primeira).salvar(PERSONALIZADA);

        // "reabrir o programa": tudo novo, só o arquivo é o mesmo; o schema roda de novo (idempotente)
        FabricaDeConexoes segunda = new FabricaDeConexoes(url, "sa", "");
        new InicializadorBanco(segunda).criarEstrutura();
        assertEquals(PERSONALIZADA, new ConfiguracaoRepository(segunda).carregar().orElseThrow());
    }
}
