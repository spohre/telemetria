package infra;

/**
 * Exceção não-verificada que encapsula {@link java.sql.SQLException}.
 * Evita que detalhes do JDBC "vazem" para as camadas de aplicação e domínio.
 */
public class PersistenciaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PersistenciaException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
