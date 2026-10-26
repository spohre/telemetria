package app;

import static org.junit.jupiter.api.Assertions.*;

import domain.LeituraTelemetria;
import domain.TipoSensor;
import infra.FilaDeLeituras;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Consumidores rodando em threads reais sobre a fila, encerrados pela pílula de fim.
 * (As regras de anomalia são testadas sem threads em AnalisadorDeAnomaliasTest.)
 */
class ProcessadorDeLeiturasConcorrenciaTest {

    private static final Instant T = Instant.parse("2026-10-20T12:00:00Z");

    private final FilaDeLeituras fila = new FilaDeLeituras(100);
    private final Dubles.RepositorioFalso repositorio = new Dubles.RepositorioFalso();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final Dubles.OuvinteFalso ouvinte = new Dubles.OuvinteFalso();
    private final ContadoresExecucao contadores = new ContadoresExecucao();
    private final List<Thread> consumidores = new ArrayList<>();

    private ProcessadorDeLeituras novoProcessador() {
        return new ProcessadorDeLeituras(fila, repositorio, log::add, ouvinte, contadores);
    }

    private void iniciarConsumidores(int n) {
        for (int c = 1; c <= n; c++) {
            Thread t = new Thread(novoProcessador(), "consumidor-" + c);
            t.start();
            consumidores.add(t);
        }
    }

    private static LeituraTelemetria leitura(TipoSensor tipo, double valor, long seq) {
        return new LeituraTelemetria(tipo.name().substring(0, 4) + "-01", tipo, valor, T, seq);
    }

    @Test
    void consumidorDrenaAFilaETerminaNaPilula() throws InterruptedException {
        for (int i = 1; i <= 300; i++) {
            fila.publicar(leitura(TipoSensor.VELOCIDADE, i % 2 == 0 ? 60.0 : 150.0, i));
            if (i == 50) {
                iniciarConsumidores(1);   // começa a consumir com a fila ainda recebendo
            }
        }
        fila.sinalizarFim(1);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> consumidores.get(0).join());
        assertEquals(300, contadores.processadas());
        assertEquals(150, contadores.alertasSalvos());
        assertEquals(300, log.size());
        assertEquals(fila.publicadas(), fila.retiradas());
    }

    @Test
    void doisConsumidoresComDuasPilulasProcessamTudoExatamenteUmaVez() throws InterruptedException {
        iniciarConsumidores(2);
        for (int i = 1; i <= 1000; i++) {
            fila.publicar(leitura(TipoSensor.UMIDADE, i % 10 == 0 ? 95.0 : 50.0, i));
        }
        fila.sinalizarFim(2);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (Thread t : consumidores) {
                t.join();
            }
        });
        assertEquals(1000, contadores.processadas());
        assertEquals(100, repositorio.salvos.size());
        long distintas = ouvinte.processadas.stream().map(p -> p.leitura().sequencia()).distinct().count();
        assertEquals(1000, distintas, "nenhuma leitura processada duas vezes");
    }

    @Test
    void interrupcaoAbortaOConsumidorParadoNaFilaVazia() throws InterruptedException {
        iniciarConsumidores(1);
        Thread t = consumidores.get(0);
        long limite = System.nanoTime() + 2_000_000_000L;
        while (t.getState() != Thread.State.WAITING && System.nanoTime() < limite) {
            Thread.sleep(2);
        }

        t.interrupt();
        t.join(1000);

        assertFalse(t.isAlive());
    }
}
