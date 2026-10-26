package app;

import static org.junit.jupiter.api.Assertions.*;

import domain.Alerta;
import domain.AnalisadorDeAnomalias;
import domain.LeituraTelemetria;
import domain.Severidade;
import domain.TipoSensor;
import infra.FilaDeLeituras;
import infra.PersistenciaException;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Consumidor com repositório, log e ouvinte falsos, chamando {@code processar} direto —
 * sem threads. Os testes com threads ficam em {@code ProcessadorDeLeiturasConcorrenciaTest}.
 */
class ProcessadorDeLeiturasTest {

    private static final Instant T = Instant.parse("2026-10-20T12:00:00Z");

    // ------------------------------------------------------------ cenário

    private final FilaDeLeituras fila = new FilaDeLeituras(100);
    private final Dubles.RepositorioFalso repositorio = new Dubles.RepositorioFalso();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final Dubles.OuvinteFalso ouvinte = new Dubles.OuvinteFalso();
    private final ContadoresExecucao contadores = new ContadoresExecucao();
    private final ProcessadorDeLeituras processador = new ProcessadorDeLeituras(
            fila, new AnalisadorDeAnomalias(), repositorio, log::add, ouvinte, contadores);

    private static LeituraTelemetria leitura(TipoSensor tipo, double valor, long seq) {
        return new LeituraTelemetria(tipo.name().substring(0, 4) + "-01", tipo, valor, T, seq);
    }

    // ------------------------------------------------------------ sem threads

    @Test
    void leituraNormalNaoGeraAlertaMasVaiParaOLog() {
        processador.processar(leitura(TipoSensor.TEMPERATURA, 50.0, 1));

        assertTrue(repositorio.salvos.isEmpty());
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains(";NORMAL;;sensor-TEMP-01"), log.get(0));
        assertEquals(1, ouvinte.processadas.size());
        assertNull(ouvinte.processadas.get(0).alerta());
        assertEquals(1, contadores.processadas());
        assertEquals(0, contadores.alertasGerados());
    }

    @Test
    void leituraAnomalaGeraAlertaSalvaRegistraENotifica() {
        processador.processar(leitura(TipoSensor.TEMPERATURA, 92.5, 1));

        assertEquals(1, repositorio.salvos.size());
        Alerta notificado = ouvinte.processadas.get(0).alerta();
        assertNotNull(notificado);
        assertEquals(1L, notificado.id().longValue(), "o ouvinte recebe o alerta já com o id do banco");
        assertEquals(Severidade.ALTA, notificado.severidade());
        assertTrue(log.get(0).contains(";ANOMALA;ALTA;"), log.get(0));
        assertEquals(1, contadores.alertasSalvos());
        assertEquals(1L, contadores.alertasPorTipo().get(TipoSensor.TEMPERATURA).get(Severidade.ALTA).longValue());
        assertTrue(ouvinte.falhas.isEmpty());
    }

    @Test
    void erroDeBancoNaoParaOConsumidorEAvisaUmaVez() {
        repositorio.fora.set(true);

        processador.processar(leitura(TipoSensor.PRESSAO, 12.0, 1));
        processador.processar(leitura(TipoSensor.PRESSAO, 13.0, 2));
        processador.processar(leitura(TipoSensor.PRESSAO, 5.0, 3));   // normal: não depende do banco

        assertEquals(3, contadores.processadas(), "todas as leituras foram processadas");
        assertEquals(3, log.size(), "todas foram para o log");
        assertEquals(2, contadores.alertasNaoSalvos());
        assertEquals(0, contadores.alertasSalvos());
        assertFalse(contadores.bancoDisponivel());

        assertEquals(1, ouvinte.falhas.size(), "avisa só na transição disponível → indisponível");
        Dubles.OuvinteFalso.Falha f = ouvinte.falhas.get(0);
        assertEquals(ProcessadorDeLeituras.FALHA_BANCO_O_QUE, f.oQue());
        assertTrue(f.fazer().contains("db.properties"));
        assertInstanceOf(PersistenciaException.class, f.causa());

        Alerta naoSalvo = ouvinte.processadas.get(0).alerta();
        assertNotNull(naoSalvo, "o alerta aparece na tela mesmo sem banco");
        assertNull(naoSalvo.id(), "sem id = não salvo");
    }

    @Test
    void bancoVoltaEOsAlertasSeguintesSaoSalvos() {
        repositorio.fora.set(true);
        processador.processar(leitura(TipoSensor.TEMPERATURA, 90.0, 1));
        repositorio.fora.set(false);
        processador.processar(leitura(TipoSensor.TEMPERATURA, 90.0, 2));

        assertTrue(contadores.bancoDisponivel());
        assertEquals(1, contadores.alertasSalvos());
        assertEquals(1, contadores.alertasNaoSalvos());
        assertEquals(2, contadores.alertasGerados());
    }

    @Test
    void erroInesperadoNoOuvinteNaoPerdeALeitura() {
        Dubles.OuvinteFalso quebrado = new Dubles.OuvinteFalso() {
            @Override
            public void aoProcessarLeitura(LeituraTelemetria leitura, Alerta alerta) {
                throw new IllegalStateException("tela fechada");
            }
        };
        ProcessadorDeLeituras p = new ProcessadorDeLeituras(
                fila, new AnalisadorDeAnomalias(), repositorio, log::add, quebrado, contadores);

        p.processar(leitura(TipoSensor.TEMPERATURA, 50.0, 1));

        assertEquals(1, contadores.processadas());
        assertEquals(1, log.size());
        assertEquals(1, quebrado.falhas.size());
    }

    @Test
    void logQueParouDeGravarAvisaUmaVezENaoPerdeLeituras() {
        ProcessadorDeLeituras p = new ProcessadorDeLeituras(fila, new AnalisadorDeAnomalias(), repositorio,
                linha -> {
                    throw new IllegalStateException("disco cheio");
                }, ouvinte, contadores);

        for (int i = 1; i <= 5; i++) {
            p.processar(leitura(TipoSensor.TEMPERATURA, i % 2 == 0 ? 95.0 : 50.0, i));
        }

        assertEquals(5, contadores.processadas(), "todas contam como processadas");
        assertEquals(5, ouvinte.processadas.size(), "todas chegam à tela");
        assertEquals(2, repositorio.salvos.size(), "alertas continuam indo para o banco");
        assertEquals(1, ouvinte.falhas.size(), "um único aviso, não um por leitura");
        assertEquals(ProcessadorDeLeituras.FALHA_LOG_O_QUE, ouvinte.falhas.get(0).oQue());
        assertFalse(contadores.logDisponivel());
    }
}
