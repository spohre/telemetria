package domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.SynchronousQueue;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Comportamento do Sensor em thread real, com um publicador falso em memória.
 * (As regras de anomalia são testadas sem threads em AnalisadorDeAnomaliasTest.)
 */
class SensorTest {

    private static final Instant T = Instant.parse("2026-10-20T12:00:00Z");
    private static final Clock RELOGIO_FIXO = Clock.fixed(T, ZoneOffset.UTC);
    private static final long INTERVALO_MS = 5;

    /** Publicador falso: guarda tudo numa lista thread-safe. */
    private final List<LeituraTelemetria> publicadas = new CopyOnWriteArrayList<>();
    private final PublicadorDeLeituras publicadorEmMemoria = publicadas::add;

    private final List<Thread> threads = new ArrayList<>();

    @AfterEach
    void encerrarThreads() throws InterruptedException {
        for (Thread t : threads) {
            t.interrupt();
            t.join(1000);
        }
    }

    private Sensor sensor(PublicadorDeLeituras publicador) {
        GeradorDeValores constante = () -> 42.0;
        return new Sensor("TEMP-01", TipoSensor.TEMPERATURA, INTERVALO_MS, constante, publicador, RELOGIO_FIXO);
    }

    private Thread iniciar(Sensor s) {
        Thread t = new Thread(s, "sensor-" + s.id());
        threads.add(t);
        t.start();
        return t;
    }

    private static void esperarAte(BooleanSupplier condicao, String mensagem) throws InterruptedException {
        long limite = System.nanoTime() + 2_000_000_000L;
        while (!condicao.getAsBoolean()) {
            if (System.nanoTime() > limite) {
                fail("tempo esgotado: " + mensagem);
            }
            Thread.sleep(2);
        }
    }

    @Test
    void sequenciasSaemUmDoisTresSemBuracos() throws InterruptedException {
        Sensor s = sensor(publicadorEmMemoria);
        Thread t = iniciar(s);
        esperarAte(() -> publicadas.size() >= 20, "20 leituras");
        t.interrupt();
        t.join(1000);

        List<LeituraTelemetria> copia = List.copyOf(publicadas);
        for (int i = 0; i < copia.size(); i++) {
            assertEquals(i + 1, copia.get(i).sequencia(), "posição " + i);
        }
        assertEquals(copia.size(), s.leiturasProduzidas(), "contador = leituras entregues");
    }

    @Test
    void leituraCarregaIdTipoValorEMomentoDoRelogio() throws InterruptedException {
        Thread t = iniciar(sensor(publicadorEmMemoria));
        esperarAte(() -> !publicadas.isEmpty(), "primeira leitura");
        t.interrupt();

        LeituraTelemetria l = publicadas.get(0);
        assertEquals("TEMP-01", l.sensorId());
        assertEquals(TipoSensor.TEMPERATURA, l.tipo());
        assertEquals(42.0, l.valor());
        assertEquals(T, l.momento());
    }

    @Test
    void interrupcaoEncerraAThreadEmMenosDeUmSegundo() throws InterruptedException {
        Thread t = iniciar(sensor(publicadorEmMemoria));
        esperarAte(() -> publicadas.size() >= 3, "3 leituras");

        t.interrupt();
        t.join(1000);

        assertFalse(t.isAlive(), "a thread do sensor deveria ter terminado");
    }

    @Test
    void interrupcaoEncerraMesmoComPublicarBloqueado() throws InterruptedException {
        // SynchronousQueue sem ninguém retirando: put() bloqueia para sempre, como uma fila cheia
        SynchronousQueue<LeituraTelemetria> filaSemConsumidor = new SynchronousQueue<>();
        Sensor s = sensor(filaSemConsumidor::put);
        Thread t = iniciar(s);
        esperarAte(() -> t.getState() == Thread.State.WAITING, "sensor bloqueado no publicar");

        t.interrupt();
        t.join(1000);

        assertFalse(t.isAlive());
        assertEquals(0, s.leiturasProduzidas(), "leitura não entregue não conta como produzida");
    }

    @Test
    void pausadoDesdeOInicioNaoPublica() throws InterruptedException {
        Sensor s = sensor(publicadorEmMemoria);
        s.pausar();
        iniciar(s);

        Thread.sleep(100);   // ~20 intervalos

        assertTrue(s.estaPausado());
        assertTrue(publicadas.isEmpty());
        assertEquals(0, s.leiturasProduzidas());
    }

    @Test
    void pausarNoMeioParaDePublicarERetomarVolta() throws InterruptedException {
        Sensor s = sensor(publicadorEmMemoria);
        iniciar(s);
        esperarAte(() -> publicadas.size() >= 5, "5 leituras");

        s.pausar();
        Thread.sleep(50);   // deixa terminar a leitura em curso (sleep no Windows tem resolução ~15 ms)
        int congelado = publicadas.size();
        Thread.sleep(100);
        assertEquals(congelado, publicadas.size(), "pausado não deve publicar");

        s.retomar();
        esperarAte(() -> publicadas.size() >= congelado + 5, "voltar a publicar");
        assertFalse(s.estaPausado());

        // a sequência continua de onde parou, sem buracos
        List<LeituraTelemetria> copia = List.copyOf(publicadas);
        for (int i = 0; i < copia.size(); i++) {
            assertEquals(i + 1, copia.get(i).sequencia());
        }
    }

    @Test
    void interrupcaoDuranteAPausaEncerraAThread() throws InterruptedException {
        Sensor s = sensor(publicadorEmMemoria);
        s.pausar();
        Thread t = iniciar(s);
        esperarAte(() -> t.getState() == Thread.State.WAITING, "sensor esperando na pausa");

        t.interrupt();
        t.join(1000);

        assertFalse(t.isAlive());
    }

    @Test
    void variosSensoresEmThreadsSeparadasTemSequenciasIndependentes() throws InterruptedException {
        String[] ids = {"TEMP-01", "VEL-01", "PRES-01"};
        List<Thread> ts = new ArrayList<>();
        for (String id : ids) {
            ts.add(iniciar(new Sensor(id, TipoSensor.TEMPERATURA, INTERVALO_MS, () -> 10.0,
                    publicadorEmMemoria, RELOGIO_FIXO)));
        }
        esperarAte(() -> publicadas.stream().map(LeituraTelemetria::sensorId).distinct().count() == 3
                && publicadas.size() >= 30, "3 sensores publicando");
        ts.forEach(Thread::interrupt);
        for (Thread t : ts) {
            t.join(1000);
        }

        for (String id : ids) {
            List<Long> seq = publicadas.stream().filter(l -> l.sensorId().equals(id))
                    .map(LeituraTelemetria::sequencia).toList();
            for (int i = 0; i < seq.size(); i++) {
                assertEquals(i + 1, seq.get(i).longValue(), id + " posição " + i);
            }
        }
    }

    @Test
    void rejeitaParametrosInvalidos() {
        GeradorDeValores g = () -> 1.0;
        assertThrows(IllegalArgumentException.class,
                () -> new Sensor(" ", TipoSensor.TEMPERATURA, 10, g, publicadorEmMemoria));
        assertThrows(IllegalArgumentException.class,
                () -> new Sensor("T1", TipoSensor.TEMPERATURA, -1, g, publicadorEmMemoria));
        assertThrows(NullPointerException.class,
                () -> new Sensor("T1", TipoSensor.TEMPERATURA, 10, g, null));
    }
}
