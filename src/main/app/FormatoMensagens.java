package app;

import domain.Alerta;
import domain.LeituraTelemetria;
import domain.ConfiguracaoSensores;
import domain.ParametrosDeEpisodios;
import domain.RegraDeAnomalia;
import domain.SensorConfigurado;
import domain.Severidade;
import domain.TipoSensor;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern PERIODO_EXTRATO =
            Pattern.compile("Período\\s*\\.+\\s*(\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2})");
    private static final Pattern NOME_EXTRATO = Pattern.compile("extrato-(\\d{8}-\\d{6})\\.txt$");

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

    /**
     * Lê de volta uma linha gerada por {@link #linhaLog}. Cabeçalho, linha em branco ou
     * linha malformada devolvem vazio (o log é de auditoria: uma linha ruim não impede o resto).
     */
    public static Optional<LeituraRegistrada> lerLinhaLog(String linha) {
        if (linha == null || linha.isBlank() || linha.startsWith(CABECALHO_LOG.substring(0, 8))) {
            return Optional.empty();
        }
        String[] c = linha.split(";", -1);
        if (c.length < 7) {
            return Optional.empty();
        }
        try {
            Instant momento = OffsetDateTime.parse(c[0]).toInstant();
            TipoSensor tipo = TipoSensor.valueOf(c[2]);
            double valor = Double.parseDouble(c[3]);
            Severidade sev = c[6].isEmpty() ? null : Severidade.valueOf(c[6]);
            return Optional.of(new LeituraRegistrada(new LeituraTelemetria(c[1], tipo, valor, momento), sev));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Início da execução lido da linha "Período" de um extrato gerado por {@link #extrato}. */
    public static Optional<Instant> inicioDoExtrato(String textoExtrato) {
        Matcher m = PERIODO_EXTRATO.matcher(textoExtrato == null ? "" : textoExtrato);
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DATA_HORA.parse(m.group(1), Instant::from));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Fim da execução lido do nome do arquivo gerado por {@link #nomeArquivoExtrato}. */
    public static Optional<Instant> fimDoArquivoExtrato(String nomeArquivo) {
        Matcher m = NOME_EXTRATO.matcher(nomeArquivo == null ? "" : nomeArquivo);
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ARQUIVO.parse(m.group(1), Instant::from));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
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

    /**
     * Linha de console que explica a simulação de valores: tempo normal entre episódios (em
     * segundos, pelo intervalo dos sensores, e em leituras) e a duração de cada fase.
     */
    public static String resumoEpisodios(ParametrosDeEpisodios p, int intervaloMs) {
        double s = intervaloMs / 1000.0;
        return "Simulação por eventos discretos: ~" + decimalCompacto(p.mediaEntreEpisodios() * s) + " s normais ("
                + inteiro(Math.round(p.mediaEntreEpisodios())) + " leituras) em média entre episódios; "
                + "cada episódio aquece " + faixaEmSegundos(p.aquecimentoMinimo(), p.aquecimentoMaximo(), s)
                + ", fica em crise " + faixaEmSegundos(p.duracaoMinima(), p.duracaoMaxima(), s)
                + " (passa do limite em " + inteiro(Math.round(p.probabilidadeDeCrise() * 100)) + " % deles)"
                + " e esfria " + faixaEmSegundos(p.esfriamentoMinimo(), p.esfriamentoMaximo(), s);
    }

    /**
     * Linha de console com a faixa aceita de cada sensor da execução; "(propria)" marca a que é
     * diferente da faixa padrão do tipo. Separadores ASCII ({@code |}): o cmd do Windows troca
     * "·" por "?".
     */
    public static String resumoFaixas(ConfiguracaoSensores cfg) {
        StringBuilder sb = new StringBuilder("Faixas aceitas: ");
        String sep = "";
        for (SensorConfigurado s : cfg.sensores()) {
            sb.append(sep).append(s.id()).append(' ').append(faixa(s.regra()));
            if (!s.regra().equals(cfg.padraoDo(s.tipo()))) {
                sb.append(" (propria)");
            }
            sep = " | ";
        }
        return sb.toString();
    }

    /** {@code 10,0 a 60,0 °C} */
    public static String faixa(RegraDeAnomalia r) {
        return decimal(r.minimo()) + " a " + valorComUnidade(r.maximo(), r.tipo());
    }

    private static String faixaEmSegundos(int minimoLeituras, int maximoLeituras, double segundosPorLeitura) {
        return decimalCompacto(minimoLeituras * segundosPorLeitura) + " a "
                + decimalCompacto(maximoLeituras * segundosPorLeitura) + " s";
    }

    /**
     * Número digitado na tela: aceita vírgula ou ponto decimal ("12,5", "12.5"), sinal e o
     * milhar pt-BR de {@link #decimalCompacto} ("1.050,5"). Vazio se não for um número finito.
     */
    public static java.util.OptionalDouble lerNumero(String texto) {
        if (texto == null || texto.isBlank()) {
            return java.util.OptionalDouble.empty();
        }
        String t = texto.strip().replace(" ", "");
        if (t.contains(",")) {
            t = t.replace(".", "").replace(",", ".");     // pt-BR: ponto = milhar, vírgula = decimal
        }
        if (!t.matches("[+-]?\\d+(\\.\\d+)?")) {
            return java.util.OptionalDouble.empty();
        }
        double v = Double.parseDouble(t);
        return Double.isFinite(v) ? java.util.OptionalDouble.of(v) : java.util.OptionalDouble.empty();
    }

    /**
     * Escala de severidade em texto: até quanto a leitura pode passar do limite em cada
     * severidade, na unidade do sensor. Ex.: "até 4,0 BAIXA · 12,0 MEDIA · 24,0 ALTA · mais CRITICA (°C)"
     * — curto para caber na coluna "Severidade além do limite" da lista da aba Configurações.
     */
    public static String textoEscala(java.util.List<domain.DegrauDeSeveridade> escala, TipoSensor tipo) {
        StringBuilder sb = new StringBuilder("até ");
        String sep = "";
        for (domain.DegrauDeSeveridade d : escala) {
            sb.append(sep).append(d.semLimite() ? "mais" : decimal(d.desvioMaximo())).append(' ')
                    .append(d.severidade().name());
            sep = " · ";
        }
        return sb.append(" (").append(tipo.unidade()).append(')').toString();
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
        ConfiguracaoSensores sc = e.sensores();
        sb.append(linhaPontilhada("Configuração", sc.sensores().size() + " sensores · "
                + c.consumidores() + " consumidor(es) · intervalo " + sc.intervaloMs() + " ms"));

        sb.append("\nFAIXAS ACEITAS (por sensor)\n");
        for (SensorConfigurado s : sc.sensores()) {
            String marca = s.regra().equals(sc.padraoDo(s.tipo())) ? "" : "   (diferente do padrão do tipo)";
            sb.append(String.format("  %-13s %s%s%n", s.id(), faixa(s.regra()), marca));
        }

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
