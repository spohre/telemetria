package infra;

/**
 * Destino das linhas do log de leituras (uma linha por leitura, já formatada).
 * A implementação em arquivo é o {@code LogArquivo} (Prompt 5); nos testes, uma lista.
 *
 * <p>Implementações precisam aceitar chamadas de vários consumidores ao mesmo tempo.</p>
 */
@FunctionalInterface
public interface RegistroDeLeituras {

    void registrar(String linha);
}
