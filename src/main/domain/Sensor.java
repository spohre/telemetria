package domain;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sensor simulado — cada instância roda na sua própria thread e publica leituras
 * periodicamente no {@link PublicadorDeLeituras}. Não sabe nada de fila, banco ou log.
 *
 * <p>O momento da leitura é registrado em milissegundos (o mesmo que o log e o banco mostram).</p>
 *
 * <p><b>Concorrência</b></p>
 * <ul>
 *   <li><b>Encerramento:</b> o laço roda enquanto a thread não for interrompida. Uma
 *       interrupção durante a espera (sleep), a pausa ou um {@code publicar} bloqueado
 *       (fila cheia) gera {@link InterruptedException}; o flag é restaurado e o sensor sai.</li>
 *   <li><b>Sem perda nem duplicação:</b> a {@code sequencia} só avança depois que o
 *       {@code publicar} retorna. Se a interrupção chegar no meio de um publicar, aquela
 *       leitura não foi entregue e não conta — a sequência entregue fica 1, 2, 3... sem buracos.</li>
 *   <li><b>Pausa:</b> um único monitor próprio ({@code monitorPausa}); a thread do sensor
 *       espera com {@code wait()} dentro de {@code while} (protege contra despertar falso) e
 *       {@link #retomar()} acorda com {@code notifyAll()}. O monitor nunca é segurado
 *       durante o {@code publicar}, então a pausa não pode travar a fila (sem deadlock).</li>
 *   <li>{@code sequencia} só é tocada pela thread do sensor; o contador de produzidas é
 *       {@link AtomicLong} porque é lido por outras threads (estatísticas).</li>
 * </ul>
 */
public class Sensor implements Runnable {

    private final String id;
    private final TipoSensor tipo;
    private final long intervaloMs;
    private final GeradorDeValores gerador;
    private final PublicadorDeLeituras publicador;
    private final Clock relogio;

    private final AtomicLong produzidas = new AtomicLong();
    private long sequencia;

    private final Object monitorPausa = new Object();
    private boolean pausado;   // protegido por monitorPausa

    public Sensor(String id, TipoSensor tipo, long intervaloMs, GeradorDeValores gerador,
                  PublicadorDeLeituras publicador, Clock relogio) {
        this.id = Objects.requireNonNull(id, "id");
        this.tipo = Objects.requireNonNull(tipo, "tipo");
        this.gerador = Objects.requireNonNull(gerador, "gerador");
        this.publicador = Objects.requireNonNull(publicador, "publicador");
        this.relogio = Objects.requireNonNull(relogio, "relogio");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id do sensor não pode ser vazio");
        }
        if (intervaloMs < 0) {
            throw new IllegalArgumentException("intervaloMs não pode ser negativo");
        }
        this.intervaloMs = intervaloMs;
    }

    public Sensor(String id, TipoSensor tipo, long intervaloMs, GeradorDeValores gerador,
                  PublicadorDeLeituras publicador) {
        this(id, tipo, intervaloMs, gerador, publicador, Clock.systemDefaultZone());
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                aguardarEnquantoPausado();
                LeituraTelemetria leitura = new LeituraTelemetria(
                        id, tipo, gerador.proximoValor(), relogio.instant().truncatedTo(ChronoUnit.MILLIS), sequencia + 1);
                publicador.publicar(leitura);   // pode bloquear (fila cheia) — fora de qualquer lock
                sequencia++;
                produzidas.incrementAndGet();
                Thread.sleep(intervaloMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // restaura o flag para quem nos chamou
        }
    }

    private void aguardarEnquantoPausado() throws InterruptedException {
        synchronized (monitorPausa) {
            while (pausado) {
                monitorPausa.wait();
            }
        }
    }

    /** Suspende a produção a partir da próxima leitura. A thread continua viva. */
    public void pausar() {
        synchronized (monitorPausa) {
            pausado = true;
        }
    }

    /** Retoma a produção após {@link #pausar()}. */
    public void retomar() {
        synchronized (monitorPausa) {
            pausado = false;
            monitorPausa.notifyAll();
        }
    }

    public boolean estaPausado() {
        synchronized (monitorPausa) {
            return pausado;
        }
    }

    /** Leituras entregues ao publicador até agora (seguro para ler de qualquer thread). */
    public long leiturasProduzidas() {
        return produzidas.get();
    }

    public String id() {
        return id;
    }

    public TipoSensor tipo() {
        return tipo;
    }

    public long intervaloMs() {
        return intervaloMs;
    }

    @Override
    public String toString() {
        return "Sensor[" + id + ", " + tipo + ", " + intervaloMs + " ms]";
    }
}
