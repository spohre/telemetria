package infra;

import static org.junit.jupiter.api.Assertions.*;

import domain.LeituraTelemetria;
import domain.TipoSensor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class FilaDeLeiturasTest {

    private static final Instant T = Instant.parse("2026-10-20T12:00:00Z");

    private static LeituraTelemetria leitura(String sensor, long seq) {
        return new LeituraTelemetria(sensor, TipoSensor.TEMPERATURA, 20.0, T, seq);
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
    void capacidadePadraoEConfiguravel() {
        assertEquals(1000, new FilaDeLeituras().capacidade());
        assertEquals(50, new FilaDeLeituras(50).capacidade());
        assertThrows(IllegalArgumentException.class, () -> new FilaDeLeituras(0));
    }

    @Test
    void retiraNaOrdemEmQueFoiPublicado() throws InterruptedException {
        FilaDeLeituras fila = new FilaDeLeituras(10);
        fila.publicar(leitura("A", 1));
        fila.publicar(leitura("A", 2));
        fila.publicar(leitura("B", 1));

        assertEquals(leitura("A", 1), fila.retirar().orElseThrow());
        assertEquals(leitura("A", 2), fila.retirar().orElseThrow());
        assertEquals(leitura("B", 1), fila.retirar().orElseThrow());
        assertEquals(0, fila.tamanho());
    }

    @Test
    void variosProdutoresEUmConsumidorSemPerdaNemDuplicacao() throws InterruptedException {
        int produtores = 8;
        int porProdutor = 2_000;
        FilaDeLeituras fila = new FilaDeLeituras(16);   // pequena de propósito: força bloqueio
        Set<String> recebidas = new HashSet<>();        // só a thread consumidora mexe aqui

        Thread consumidor = new Thread(() -> {
            try {
                for (Optional<LeituraTelemetria> l = fila.retirar(); l.isPresent(); l = fila.retirar()) {
                    recebidas.add(l.get().sensorId() + "#" + l.get().sequencia());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumidor.start();

        List<Thread> ps = new ArrayList<>();
        for (int p = 0; p < produtores; p++) {
            String id = "S" + p;
            Thread t = new Thread(() -> {
                try {
                    for (int i = 1; i <= porProdutor; i++) {
                        fila.publicar(leitura(id, i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            ps.add(t);
            t.start();
        }

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (Thread t : ps) {
                t.join();
            }
            fila.sinalizarFim(1);
            consumidor.join();
        }, "deadlock ou espera infinita entre produtores e consumidor");

        int total = produtores * porProdutor;
        assertEquals(total, fila.publicadas());
        assertEquals(total, fila.retiradas());
        assertEquals(total, recebidas.size(), "cada (sensor, sequência) exatamente uma vez");
        assertEquals(0, fila.tamanho());
    }

    @Test
    void filaCheiaBloqueiaOProdutorEmVezDeDescartar() throws InterruptedException {
        FilaDeLeituras fila = new FilaDeLeituras(2);
        fila.publicar(leitura("A", 1));
        fila.publicar(leitura("A", 2));

        Thread produtor = new Thread(() -> {
            try {
                fila.publicar(leitura("A", 3));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        produtor.start();
        esperarAte(() -> produtor.getState() == Thread.State.WAITING, "produtor bloqueado");

        assertEquals(2, fila.tamanho());
        assertEquals(2, fila.publicadas(), "a 3ª ainda não entrou — mas também não foi descartada");

        assertEquals(1, fila.retirar().orElseThrow().sequencia());   // abre espaço
        produtor.join(1000);
        assertFalse(produtor.isAlive());

        assertEquals(2, fila.retirar().orElseThrow().sequencia());
        assertEquals(3, fila.retirar().orElseThrow().sequencia());
        assertEquals(3, fila.publicadas());
        assertEquals(3, fila.retiradas());
    }

    @Test
    void produtorBloqueadoPodeSerInterrompido() throws InterruptedException {
        FilaDeLeituras fila = new FilaDeLeituras(1);
        fila.publicar(leitura("A", 1));
        AtomicReference<Throwable> erro = new AtomicReference<>();
        Thread produtor = new Thread(() -> {
            try {
                fila.publicar(leitura("A", 2));
            } catch (Throwable t) {
                erro.set(t);
            }
        });
        produtor.start();
        esperarAte(() -> produtor.getState() == Thread.State.WAITING, "produtor bloqueado");

        produtor.interrupt();
        produtor.join(1000);

        assertInstanceOf(InterruptedException.class, erro.get());
        assertEquals(1, fila.publicadas());
    }

    @Test
    void pilulasSaemDepoisDasLeiturasENaoContamComoRetiradas() throws InterruptedException {
        FilaDeLeituras fila = new FilaDeLeituras(10);
        fila.publicar(leitura("A", 1));
        fila.publicar(leitura("A", 2));
        fila.sinalizarFim(2);

        assertTrue(fila.retirar().isPresent());
        assertTrue(fila.retirar().isPresent());
        assertTrue(fila.retirar().isEmpty(), "1ª pílula");
        assertTrue(fila.retirar().isEmpty(), "2ª pílula");
        assertEquals(2, fila.publicadas());
        assertEquals(2, fila.retiradas());
    }

    @Test
    void rejeitaLeituraNula() {
        assertThrows(IllegalArgumentException.class, () -> new FilaDeLeituras().publicar(null));
    }
}
