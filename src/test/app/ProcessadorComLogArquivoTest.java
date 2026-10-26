package app;

import static org.junit.jupiter.api.Assertions.*;

import domain.LeituraTelemetria;
import domain.TipoSensor;
import infra.FilaDeLeituras;
import infra.LogArquivo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** O consumidor ligado ao log em arquivo de verdade: formato da skill de design. */
class ProcessadorComLogArquivoTest {

    private static final Instant T = Instant.parse("2026-10-15T22:46:02.431Z");

    @TempDir
    Path pasta;

    @Test
    void todasAsLeiturasVaoParaOArquivoNoFormatoDoLog() throws Exception {
        Path arquivo = pasta.resolve("leituras.log");
        LogArquivo log = new LogArquivo(arquivo, FormatoMensagens.CABECALHO_LOG);
        ContadoresExecucao contadores = new ContadoresExecucao();
        ProcessadorDeLeituras processador = new ProcessadorDeLeituras(new FilaDeLeituras(10),
                new Dubles.RepositorioFalso(), log, new Dubles.OuvinteFalso(), contadores);

        processador.processar(new LeituraTelemetria("TEMP-01", TipoSensor.TEMPERATURA, 92.5, T, 1));
        processador.processar(new LeituraTelemetria("VEL-01", TipoSensor.VELOCIDADE, 64.0, T, 1));
        processador.processar(new LeituraTelemetria("PRES-01", TipoSensor.PRESSAO, 0.5, T, 1));
        log.fechar();

        List<String> linhas = Files.readAllLines(arquivo, StandardCharsets.UTF_8);
        assertEquals(4, linhas.size(), "cabeçalho + 3 leituras (normais e anômalas)");
        assertEquals("momento;sensor;tipo;valor;unidade;resultado;severidade;thread", linhas.get(0));
        assertTrue(linhas.get(1).endsWith(";TEMP-01;TEMPERATURA;92.50;°C;ANOMALA;ALTA;sensor-TEMP-01"), linhas.get(1));
        assertTrue(linhas.get(2).endsWith(";VEL-01;VELOCIDADE;64.00;km/h;NORMAL;;sensor-VEL-01"), linhas.get(2));
        assertTrue(linhas.get(3).contains(";PRES-01;PRESSAO;0.50;bar;ANOMALA;"), linhas.get(3));
        assertEquals(contadores.processadas(), log.linhasEscritas());
    }
}
