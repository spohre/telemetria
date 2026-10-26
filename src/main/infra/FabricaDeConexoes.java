package infra;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Ponto único de criação de conexões JDBC.
 *
 * <p><b>Decisão de concorrência:</b> a fábrica guarda apenas a configuração (imutável)
 * e entrega uma conexão NOVA a cada chamada. {@link Connection} não é thread-safe,
 * então nunca compartilhamos uma mesma conexão entre threads consumidoras —
 * cada operação abre, usa e fecha a sua (try-with-resources no repositório).
 * Assim não existe lock compartilhado aqui, portanto não há como gerar deadlock.</p>
 */
public final class FabricaDeConexoes {

    private static final String ARQUIVO_CONFIG = "/db.properties";

    private final String url;
    private final String usuario;
    private final String senha;

    public FabricaDeConexoes(String url, String usuario, String senha) {
        this.url = url;
        this.usuario = usuario;
        this.senha = senha == null ? "" : senha;
    }

    /**
     * Lê {@code db.properties} do classpath. Cada chave pode ser sobrescrita por
     * propriedade de sistema (ex.: {@code -Ddb.url=jdbc:h2:mem:teste}).
     */
    public static FabricaDeConexoes doArquivoDeConfiguracao() {
        Properties props = new Properties();
        try (InputStream in = FabricaDeConexoes.class.getResourceAsStream(ARQUIVO_CONFIG)) {
            if (in == null) {
                throw new IllegalStateException("Arquivo " + ARQUIVO_CONFIG + " não encontrado no classpath");
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler " + ARQUIVO_CONFIG, e);
        }
        return new FabricaDeConexoes(
                System.getProperty("db.url", props.getProperty("db.url")),
                System.getProperty("db.usuario", props.getProperty("db.usuario")),
                System.getProperty("db.senha", props.getProperty("db.senha")));
    }

    /** Abre uma nova conexão. Quem chama é responsável por fechá-la (try-with-resources). */
    public Connection abrir() throws SQLException {
        return DriverManager.getConnection(url, usuario, senha);
    }

    public String url() {
        return url;
    }
}
