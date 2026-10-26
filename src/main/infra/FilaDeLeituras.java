package infra;

import domain.LeituraTelemetria;
import domain.PublicadorDeLeituras;
import domain.TipoSensor;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fila central de leituras — o ÚNICO ponto de contato entre os sensores (produtores) e os
 * consumidores ({@code app.ProcessadorDeLeituras}).
 *
 * <h2>Por que é thread-safe</h2>
 * <p>Envolve um {@link ArrayBlockingQueue} da própria linguagem: um único lock interno
 * protege a fila e duas condições ("não cheia" / "não vazia") fazem produtores e
 * consumidores esperarem sem gastar CPU. Nenhum outro lock é usado aqui, e o lock da
 * fila só é segurado durante a inserção/remoção — nunca durante I/O ou SQL, que acontecem
 * depois que o consumidor já retirou a leitura. Sem locks aninhados, não há como formar
 * o ciclo de espera que causa deadlock.</p>
 *
 * <h2>Sem perda e sem duplicação</h2>
 * <ul>
 *   <li>{@link #publicar} usa {@code put()}: com a fila cheia o sensor <b>espera</b> por espaço
 *       (contrapressão) em vez de descartar a leitura. {@link #esperasPorEspaco()} conta (de forma
 *       aproximada, só para estatística) quantas vezes um sensor chegou com a fila cheia.</li>
 *   <li>{@link #retirar} usa {@code take()}: cada elemento sai da fila exatamente uma vez,
 *       mesmo com vários consumidores.</li>
 *   <li>Os contadores {@link #publicadas()} e {@link #retiradas()} ({@link AtomicLong}) permitem
 *       provar ao final que publicadas == retiradas.</li>
 * </ul>
 *
 * <h2>Encerramento (ordem obrigatória)</h2>
 * <ol>
 *   <li>Interromper todas as threads de sensor e esperar cada uma com {@code join()} —
 *       a partir daqui ninguém mais publica.</li>
 *   <li>Chamar {@link #sinalizarFim(int)} com o número de consumidores: coloca uma
 *       "pílula de veneno" por consumidor <b>depois</b> de todas as leituras (a fila é FIFO).</li>
 *   <li>Cada consumidor continua retirando e processando; ao receber a sua pílula
 *       ({@link #retirar()} devolve vazio) ele termina. Como as pílulas estão atrás de
 *       todas as leituras, a fila é drenada por completo antes de o último consumidor sair.</li>
 *   <li>Esperar os consumidores com {@code join()}.</li>
 * </ol>
 * <p>Interromper um consumidor em vez de usar a pílula abandonaria as leituras que ainda
 * estão na fila — por isso a interrupção fica reservada para abortar.</p>
 */
public class FilaDeLeituras implements PublicadorDeLeituras {

    public static final int CAPACIDADE_PADRAO = 1000;

    /** Marcador de fim. Comparado por identidade (==), nunca por equals. */
    private static final LeituraTelemetria PILULA =
            new LeituraTelemetria("__FIM__", TipoSensor.TEMPERATURA, 0, Instant.EPOCH);

    private final BlockingQueue<LeituraTelemetria> fila;
    private final int capacidade;
    private final AtomicLong publicadas = new AtomicLong();
    private final AtomicLong retiradas = new AtomicLong();
    private final AtomicLong esperasPorEspaco = new AtomicLong();

    public FilaDeLeituras(int capacidade) {
        if (capacidade <= 0) {
            throw new IllegalArgumentException("capacidade deve ser positiva");
        }
        this.capacidade = capacidade;
        this.fila = new ArrayBlockingQueue<>(capacidade);
    }

    public FilaDeLeituras() {
        this(CAPACIDADE_PADRAO);
    }

    /** Coloca a leitura no fim da fila; se estiver cheia, espera por espaço (nunca descarta). */
    @Override
    public void publicar(LeituraTelemetria leitura) throws InterruptedException {
        if (leitura == null) {
            throw new IllegalArgumentException("leitura não pode ser null");
        }
        if (fila.remainingCapacity() == 0) {   // só estatística: quem chega com a fila cheia vai esperar
            esperasPorEspaco.incrementAndGet();
        }
        fila.put(leitura);                      // bloqueia até haver espaço — nunca descarta
        publicadas.incrementAndGet();
    }

    /**
     * Retira a próxima leitura; se a fila estiver vazia, espera até chegar uma.
     *
     * @return a leitura, ou vazio quando o consumidor recebeu a sua pílula de fim
     */
    public Optional<LeituraTelemetria> retirar() throws InterruptedException {
        LeituraTelemetria item = fila.take();
        if (item == PILULA) {
            return Optional.empty();
        }
        retiradas.incrementAndGet();
        return Optional.of(item);
    }

    /**
     * Passo 2 do encerramento: uma pílula por consumidor, atrás de todas as leituras.
     * Só deve ser chamado depois que todos os sensores pararam.
     */
    public void sinalizarFim(int consumidores) throws InterruptedException {
        for (int i = 0; i < consumidores; i++) {
            fila.put(PILULA);
        }
    }

    /** Elementos na fila agora (leituras aguardando processamento). */
    public int tamanho() {
        return fila.size();
    }

    public int capacidade() {
        return capacidade;
    }

    /** Leituras aceitas pela fila desde a criação. */
    public long publicadas() {
        return publicadas.get();
    }

    /**
     * Quantas vezes um produtor chegou com a fila cheia e teve de esperar (contrapressão).
     * Aproximado (a fila pode esvaziar entre a consulta e o put) — serve para mostrar, no
     * teste de estresse, que a fila encheu e mesmo assim nada foi descartado.
     */
    public long esperasPorEspaco() {
        return esperasPorEspaco.get();
    }

    /** Leituras entregues a consumidores desde a criação (pílulas não contam). */
    public long retiradas() {
        return retiradas.get();
    }
}
