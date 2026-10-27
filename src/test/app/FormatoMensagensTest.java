package app;

import domain.Alerta;
import domain.LeituraTelemetria;
import domain.Severidade;
import domain.TipoSensor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Padrão de console, log e extrato — testado sem Swing, sem threads e sem banco. */
class FormatoMensagensTest {

    private static final Instant T = Instant.parse("2026-10-15T22:46:02.431Z");

    @Test
    @DisplayName("linha de log tem 8 campos, ponto decimal e marca anomalia")
    void linhaLogAnomala() {
        var l = new LeituraTelemetria("TEMP-01", TipoSensor.TEMPERATURA, 92.5, T);
        String[] campos = FormatoMensagens.linhaLog(l, Severidade.ALTA, "sensor-TEMP-01").split(";", -1);

        assertEquals(8, campos.length);
        assertEquals(FormatoMensagens.CABECALHO_LOG.split(";").length, campos.length);
        assertEquals("92.50", campos[3]);
        assertEquals("ANOMALA", campos[5]);
        assertEquals("ALTA", campos[6]);
    }

    @Test
    @DisplayName("leitura normal deixa a severidade vazia no log")
    void linhaLogNormal() {
        var l = new LeituraTelemetria("VEL-01", TipoSensor.VELOCIDADE, 64, T);
        String[] campos = FormatoMensagens.linhaLog(l, null, "t").split(";", -1);
        assertEquals("NORMAL", campos[5]);
        assertEquals("", campos[6]);
    }

    @Test
    @DisplayName("console de alerta mostra nível, sensor, valor com unidade e id salvo")
    void consoleAlerta() {
        var a = Alerta.de(new LeituraTelemetria("TEMP-01", TipoSensor.TEMPERATURA, 92.5, T),
                "acima do limite 80,0 °C", Severidade.ALTA).comId(128);
        String linha = FormatoMensagens.consoleAlerta(a);
        assertTrue(linha.contains("ALERTA"));
        assertTrue(linha.contains("[TEMP-01]"));
        assertTrue(linha.contains("92,5 °C"));
        assertTrue(linha.endsWith("salvo #128"));
    }

    @Test
    @DisplayName("extrato destaca leituras perdidas e divergência com o banco")
    void extratoDestacaProblemas() {
        var cfg = new ConfiguracaoSimulacao(1, 200, 1);
        var e = new ExtratoExecucao(T, T.plusSeconds(28), cfg, 1204, 1200,
                Map.of(TipoSensor.TEMPERATURA, Map.of(Severidade.ALTA, 9L)), 8, "data/leituras.log", "jdbc:h2:x");
        String texto = FormatoMensagens.extrato(e);
        assertEquals(4, e.leiturasPerdidas());
        assertTrue(texto.contains("Perdidas"));
        assertEquals(2, texto.split("<<< VERIFICAR", -1).length - 1, "perdidas e banco divergente");
        assertTrue(texto.contains("1.204"), "separador de milhar pt-BR");
    }

    @Test
    @DisplayName("extrato sem problemas não tem destaque")
    void extratoLimpo() {
        var e = new ExtratoExecucao(T, T.plusSeconds(5), new ConfiguracaoSimulacao(1, 200, 1), 10, 10,
                Map.of(), 0, "log", "banco");
        assertFalse(FormatoMensagens.extrato(e).contains("VERIFICAR"));
    }

    @Test
    @DisplayName("duração no formato h:mm:ss")
    void duracao() {
        assertEquals("0:04:12", FormatoMensagens.duracao(252));
        assertEquals("1:00:00", FormatoMensagens.duracao(3600));
    }

    @Test
    @DisplayName("decimal compacto: até 2 casas, no mínimo 1")
    void decimalCompacto() {
        assertEquals("4,0", FormatoMensagens.decimalCompacto(4));
        assertEquals("0,35", FormatoMensagens.decimalCompacto(0.35));
        assertEquals("10,5", FormatoMensagens.decimalCompacto(10.5));
        assertEquals("1.050,0", FormatoMensagens.decimalCompacto(1050));
    }

    @Test
    @DisplayName("linha do log é lida de volta com sensor, valor, momento e severidade")
    void lerLinhaLogIdaEVolta() {
        var anomala = new LeituraTelemetria("TEMP-01", TipoSensor.TEMPERATURA, 92.5, T);
        LeituraRegistrada r = FormatoMensagens.lerLinhaLog(
                FormatoMensagens.linhaLog(anomala, Severidade.ALTA, "sensor-TEMP-01")).orElseThrow();
        assertEquals("TEMP-01", r.leitura().sensorId());
        assertEquals(TipoSensor.TEMPERATURA, r.leitura().tipo());
        assertEquals(92.5, r.leitura().valor(), 1e-9);
        assertEquals(T, r.leitura().momento());
        assertEquals(Severidade.ALTA, r.severidade());

        var normal = new LeituraTelemetria("VEL-01", TipoSensor.VELOCIDADE, 64, T);
        assertNull(FormatoMensagens.lerLinhaLog(FormatoMensagens.linhaLog(normal, null, "t")).orElseThrow().severidade());
    }

    @Test
    @DisplayName("cabeçalho, linha vazia e linha malformada do log são ignorados")
    void lerLinhaLogIgnoraOQueNaoELeitura() {
        assertTrue(FormatoMensagens.lerLinhaLog(FormatoMensagens.CABECALHO_LOG).isEmpty());
        assertTrue(FormatoMensagens.lerLinhaLog("").isEmpty());
        assertTrue(FormatoMensagens.lerLinhaLog("lixo;1;2").isEmpty());
        assertTrue(FormatoMensagens.lerLinhaLog("2026-10-15T19:46:02-03:00;X-01;RADAR;1.0;u;NORMAL;;t").isEmpty());
    }

    @Test
    @DisplayName("início e fim da execução são lidos de volta do extrato e do nome do arquivo")
    void periodoDoExtratoIdaEVolta() {
        var e = new ExtratoExecucao(T, T.plusSeconds(28), new ConfiguracaoSimulacao(1, 200, 1), 10, 10,
                Map.of(), 0, "log", "banco");
        assertEquals(T.truncatedTo(ChronoUnit.SECONDS), FormatoMensagens.inicioDoExtrato(FormatoMensagens.extrato(e)).orElseThrow());
        assertEquals(e.fim().truncatedTo(ChronoUnit.SECONDS),
                FormatoMensagens.fimDoArquivoExtrato(FormatoMensagens.nomeArquivoExtrato(e.fim())).orElseThrow());
        assertTrue(FormatoMensagens.fimDoArquivoExtrato("data/leituras.log").isEmpty());
    }
}
