package domain;

/**
 * Porta de saída do {@link Sensor}: para onde vão as leituras geradas.
 *
 * <p>O domínio só conhece esta interface; quem a implementa é a infraestrutura
 * ({@code infra.FilaDeLeituras}). Assim o Sensor não sabe nada de fila, banco ou log.</p>
 *
 * <p>Implementações devem ser thread-safe (vários sensores publicam ao mesmo tempo) e
 * <b>não podem descartar leituras</b>: se não houver espaço, {@code publicar} bloqueia
 * até haver — e responde à interrupção lançando {@link InterruptedException}.</p>
 */
@FunctionalInterface
public interface PublicadorDeLeituras {

    void publicar(LeituraTelemetria leitura) throws InterruptedException;
}
