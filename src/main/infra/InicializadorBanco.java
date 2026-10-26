package infra;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Executa o script {@code schema.sql} na inicialização da aplicação.
 * O script é idempotente (CREATE ... IF NOT EXISTS), então pode rodar sempre
 * sem apagar alertas de execuções anteriores.
 */
public final class InicializadorBanco {

    private static final String SCRIPT = "/schema.sql";

    private final FabricaDeConexoes fabrica;

    public InicializadorBanco(FabricaDeConexoes fabrica) {
        this.fabrica = fabrica;
    }

    public void criarEstrutura() {
        List<String> comandos = separarComandos(lerScript());

        try (Connection conn = fabrica.abrir()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String sql : comandos) {
                    st.execute(sql);
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenciaException("Falha ao criar a estrutura do banco", e);
        }
    }

    private String lerScript() {
        try (InputStream in = InicializadorBanco.class.getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new IllegalStateException("Script " + SCRIPT + " não encontrado no classpath");
            }
            try (BufferedReader leitor = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                // Remove comentários de linha antes de separar por ';'
                return leitor.lines()
                        .filter(linha -> !linha.strip().startsWith("--"))
                        .collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler " + SCRIPT, e);
        }
    }

    private static List<String> separarComandos(String script) {
        List<String> comandos = new ArrayList<>();
        for (String parte : script.split(";")) {
            if (!parte.isBlank()) {
                comandos.add(parte.strip());
            }
        }
        return comandos;
    }
}
