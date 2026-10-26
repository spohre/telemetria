package app;

import domain.Alerta;
import domain.LeituraTelemetria;
import domain.Severidade;
import domain.TipoSensor;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * Padrão ÚNICO das mensagens do sistema: console (humano), log de leituras (máquina)
 * e extrato da execução (resumo final). Funções puras → cobertas por JUnit, fora da View.
 */
public final class FormatoMensagens {

    public enum Nivel { INFO, ALERTA, AVISO, ERRO }

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final ZoneId FUSO = ZoneId.systemDefault();
    private static final DateTimeFormatter HORA_CONSOLE = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(FUSO);
    private static final DateTimeFormatter ISO_LOG = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(FUSO);
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(FUSO);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(FUSO);
    private static final DateTimeFormatter ARQUIVO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(FUSO);

    public static final String CABECALHO_LOG = "momento;sensor;tipo;valor;unidade;resultado;severidade;thread";

    private FormatoMensagens() {
    }

    // ------------------------------------------------------------------ console

    /** {@code 19:46:02.431  ALERTA  [TEMP-01]     mensagem} — colunas alinhadas. */
    public static String console(Instant momento, Nivel nivel, String origem, String mensagem) {
        return String.format("%s  %-7s %-13s %s", HORA_CONSOLE.format(momento), nivel, "[" + origem + "]", mensagem);
    }

    /**
     * Linha de console de um alerta: valor, regra, severidade e id salvo no banco.
     * Separadores ASCII ("|", ":") de propósito: "·" e "—" viram "?" em consoles sem UTF-8
     * (ex.: cmd do Windows). Acentos e "°" funcionam no Java 18+ (stdout.encoding).
     */
    public static String consoleAlerta(Alerta a) {
        String salvo = a.id() == null ? "NAO SALVO" : "salvo #" + a.id();
        return console(a.momentoLeitura(), Nivel.ALERTA, a.sensorId(),
                valorComUnidade(a.valorLido(), a.tipo()) + ": " + a.regraViolada() + " | " + a.severidade() + " | " + salvo);
    }

    // ------------------------------------------------------------------ log (arquivo)

    /** Linha do log de leituras: separador ';', ISO-8601, ponto decimal (fácil de importar). */
    public static String linhaLog(LeituraTelemetria l, Severidade severidadeOuNull, String nomeThread) {
        return String.join(";",
                ISO_LOG.format(l.momento()),
                l.sensorId(),
                l.tipo().name(),
                String.format(Locale.ROOT, "%.2f", l.valor()),
                l.tipo().unidade(),
                severidadeOuNull == null ? "NORMAL" : "ANOMALA",
                severidadeOuNull == null ? "" : severidadeOuNull.name(),
                nomeThread);
    }

    // ------------------------------------------------------------------ valores para a tela

    public static String valorComUnidade(double valor, TipoSensor tipo) {
        return decimal(valor) + " " + tipo.unidade();
    }

    public static String decimal(double valor) {
        NumberFormat nf = NumberFormat.getNumberInstance(PT_BR);
        nf.setMinimumFractionDigits(1);
        nf.setMaximumFractionDigits(1);
        return nf.format(valor);
    }

    /** Até 2 casas, sem zeros sobrando: 4 → "4,0", 0,35 → "0,35", 10,5 → "10,5". */
    public static String decimalCompacto(double valor) {
        NumberFormat nf = NumberFormat.getNumberInstance(PT_BR);
        nf.setMinimumFractionDigits(1);
        nf.setMaximumFractionDigits(2);
        return nf.format(valor);
    }

    public static String inteiro(long valor) {
        return NumberFormat.getIntegerInstance(PT_BR).format(valor);
    }

    public static String hora(Instant momento) {
        return HORA.format(momento);
    }

    public static String dataHora(Instant momento) {
        return DATA_HORA.format(momento);
    }

    /** 0:04:12 */
    public static String duracao(long segundos) {
        return String.format("%d:%02d:%02d", segundos / 3600, (segundos % 3600) / 60, segundos % 60);
    }

    public static String nomeArquivoExtrato(Instant fim) {
        return "data/extrato-" + ARQUIVO.format(fim) + ".txt";
    }

    // ------------------------------------------------------------------ extrato

    public static String extrato(ExtratoExecucao e) {
        StringBuilder sb = new StringBuilder();
        long seg = Duration.between(e.inicio(), e.fim()).toSeconds();
        sb.append("EXTRATO DA EXECUÇÃO — Central de Telemetria\n");
        sb.append(linhaPontilhada("Período", DATA_HORA.format(e.inicio()) + " → " + HORA.format(e.fim())
                + " (" + duracao(seg) + ")"));
        ConfiguracaoSimulacao c = e.configuracao();
        sb.append(linhaPontilhada("Configuração", c.sensoresPorTipo() * TipoSensor.values().length + " sensores · "
                + c.consumidores() + " consumidor(es) · intervalo " + c.intervaloMs() + " ms"));

        sb.append("\nLEITURAS\n");
        sb.append("  ").append(linhaPontilhada("Produzidas", inteiro(e.leiturasProduzidas())));
        sb.append("  ").append(linhaPontilhada("Processadas", inteiro(e.leiturasProcessadas())));
        String perdidas = inteiro(e.leiturasPerdidas()) + "   (produzidas − processadas)";
        if (e.leiturasPerdidas() != 0) {
            perdidas += "  <<< VERIFICAR";
        }
        sb.append("  ").append(linhaPontilhada("Perdidas", perdidas));

        sb.append("\nALERTAS POR TIPO / SEVERIDADE\n");
        for (TipoSensor t : TipoSensor.values()) {
            Map<Severidade, Long> porSev = e.alertasPorTipo().getOrDefault(t, Map.of());
            StringBuilder linha = new StringBuilder(String.format("  %-13s", t.name()));
            long total = 0;
            for (Severidade s : Severidade.values()) {
                long n = porSev.getOrDefault(s, 0L);
                total += n;
                linha.append(String.format(" %s %-4s", s.name(), inteiro(n)));
            }
            linha.append(String.format("  total %s%n", inteiro(total)));
            sb.append(linha);
        }
        String totalAlertas = inteiro(e.totalAlertas()) + " alertas  (gravados no banco: " + inteiro(e.alertasGravados()) + ")";
        if (e.alertasGravados() != e.totalAlertas()) {
            totalAlertas += "  <<< VERIFICAR";
        }
        sb.append("  ").append(linhaPontilhada("TOTAL", totalAlertas));

        sb.append("\nARQUIVOS\n");
        sb.append(String.format("  %-16s %s%n", "Log de leituras", e.caminhoLog()));
        sb.append(String.format("  %-16s %s%n", "Banco", e.urlBanco()));
        return sb.toString();
    }

    private static String linhaPontilhada(String rotulo, String valor) {
        int pontos = Math.max(3, 16 - rotulo.length());
        return rotulo + " " + ".".repeat(pontos) + " " + valor + "\n";
    }
}
