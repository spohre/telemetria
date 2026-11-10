package domain;

import domain.CicloDeEpisodios.Fase;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Simulação de medições por <b>eventos discretos</b>: o sensor alterna entre períodos normais
 * e episódios de anomalia — aquece, entra em crise, esfria — e os alertas aparecem em
 * intervalos, com subida e descida graduais em vez de picos soltos.
 *
 * <ul>
 *   <li><b>Quando:</b> um {@link CicloDeEpisodios} (relógio simulado + agenda de eventos) diz,
 *       a cada leitura, em que fase o sensor está. 1 leitura = 1 passo do relógio simulado.</li>
 *   <li><b>NORMAL:</b> passeio aleatório dentro da faixa ({@link GeradorPasseioAleatorio} sem
 *       picos). O passeio anda em toda leitura, inclusive durante o episódio.</li>
 *   <li><b>AQUECENDO:</b> o valor vai do nível normal até um ponto logo antes do limite (até 5 %
 *       da largura da faixa), em linha reta ao longo das leituras do aquecimento — sem alerta.</li>
 *   <li><b>CRISE:</b> se o episódio passa do limite, o valor cruza o limite e cresce até o pico
 *       (no miolo, 20 %–80 %, do degrau da severidade-alvo — a margem garante que o
 *       arredondamento a 1 casa não mude a severidade; CRITICA usa de 1× a 1,5× o teto de ALTA),
 *       fica nele e desce de volta até perto do limite. Subida e descida levam cada uma ~1/3 da
 *       crise, em degraus iguais. Toda leitura da crise fica fora da faixa. Se o episódio
 *       não passa do limite (quase-incidente), as leituras ficam logo antes dele, sem alerta.</li>
 *   <li><b>ESFRIANDO:</b> o valor vai de logo antes do limite de volta ao nível normal do passeio.</li>
 * </ul>
 *
 * <p>Faixas e degraus vêm do {@link AnalisadorDeAnomalias} ({@link #para}) — nenhum limite é
 * repetido aqui. O gerador só <i>mira</i> uma severidade; quem decide se é alerta continua
 * sendo o analisador.</p>
 *
 * <p><b>Concorrência:</b> não é thread-safe e não precisa ser. Cada sensor tem o seu gerador,
 * chamado só pela thread daquele sensor (confinamento de thread). Ao religar um sensor, o
 * {@code join()} da thread antiga acontece antes do {@code start()} da nova, então a nova
 * enxerga o estado deixado (passo, episódio, agenda) sem precisar de lock.</p>
 *
 * <p>Com o mesmo {@link Random} (mesma semente) gera sempre a mesma série.</p>
 */
public class GeradorPorEventosDiscretos implements GeradorDeValores {

    private static final double MIOLO_INICIO = 0.2;
    private static final double MIOLO_LARGURA = 0.6;
    private static final double TETO_CRITICA_EM_ALTAS = 1.5;
    /** "Logo antes do limite": até 5 % da largura da faixa... */
    private static final double PERTO_DO_LIMITE = 0.05;
    /** ...e pelo menos 10 % disso (não encosta sempre exatamente no limite). */
    private static final double PERTO_MINIMO = 0.1;
    /** Menor passo da escala de valores (1 casa decimal). */
    private static final double RESOLUCAO = 0.1;

    private final RegraDeAnomalia faixa;
    private final List<DegrauDeSeveridade> escala;
    private final Random random;
    private final CicloDeEpisodios ciclo;
    private final GeradorPasseioAleatorio passeio;

    // perfil do episódio em andamento (sorteado uma vez por episódio)
    private EpisodioDeAnomalia perfilDe;
    private double folga;   // quão antes do limite termina o aquecimento e começa o esfriamento
    private double pico;    // desvio além do limite no pico da crise

    /**
     * @param faixa  faixa aceita do tipo do sensor
     * @param escala degraus BAIXA, MEDIA, ALTA e CRITICA, nessa ordem (como {@link AnalisadorDeAnomalias#escala})
     */
    public GeradorPorEventosDiscretos(RegraDeAnomalia faixa, List<DegrauDeSeveridade> escala,
                                      ParametrosDeEpisodios parametros, Random random) {
        this.faixa = Objects.requireNonNull(faixa, "faixa");
        this.escala = validar(escala);
        this.random = Objects.requireNonNull(random, "random");
        this.ciclo = new CicloDeEpisodios(faixa.tipo(), Objects.requireNonNull(parametros, "parametros"), random);
        this.passeio = new GeradorPasseioAleatorio(faixa, random, 0.0);
    }

    /** Gerador para um tipo de sensor, com faixa e degraus lidos do analisador. */
    public static GeradorPorEventosDiscretos para(AnalisadorDeAnomalias analisador, TipoSensor tipo,
                                                 ParametrosDeEpisodios parametros, Random random) {
        return new GeradorPorEventosDiscretos(analisador.regraDo(tipo), analisador.escala(tipo), parametros, random);
    }

    @Override
    public double proximoValor() {
        double normal = passeio.proximoValor();
        ciclo.avancar();
        Optional<EpisodioDeAnomalia> andamento = ciclo.episodioEmAndamento();
        if (andamento.isEmpty()) {
            return normal;
        }
        EpisodioDeAnomalia e = andamento.get();
        if (!e.equals(perfilDe)) {             // episódio novo: sorteia o perfil dele
            perfilDe = e;
            folga = sortearFolga();
            pico = sortearDesvio(e.severidadeAlvo());
        }
        double sinal = e.direcao() == EpisodioDeAnomalia.Direcao.ACIMA ? 1 : -1;
        double limite = sinal > 0 ? faixa.maximo() : faixa.minimo();
        double pertoDoLimite = limite - sinal * folga;
        long passo = ciclo.passo();

        return switch (ciclo.fase()) {
            case AQUECENDO -> {
                double f = (passo - e.inicioAquecimento() + 1.0) / e.aquecimento();   // chega a 1 na última leitura
                yield dentroDaFaixa(normal + f * (pertoDoLimite - normal));
            }
            case CRISE -> {
                if (!e.passaDoLimite()) {
                    yield dentroDaFaixa(limite - sinal * sortearFolga());
                }
                double fracao = fracaoDoPico(passo - e.inicio(), e.duracao());
                double desvio = fracao >= 1 ? sortearDesvio(e.severidadeAlvo()) : fracao * pico;
                yield foraDaFaixa(limite + sinal * desvio, limite, sinal);
            }
            case ESFRIANDO -> {
                double f = (passo - e.fim()) / (double) e.esfriamento();   // começa logo antes do limite
                yield dentroDaFaixa(pertoDoLimite + f * (normal - pertoDoLimite));
            }
            case NORMAL -> normal;
        };
    }

    /**
     * Fração do pico na leitura {@code k} (0 = primeira) de uma crise de {@code duracao} leituras:
     * sobe em ~1/3 da crise, fica no pico (pelo menos 1 leitura), desce em ~1/3 da crise, em
     * degraus iguais. Ex.: 6 leituras → 1/3, 2/3, 1, 1, 2/3, 1/3. Sempre &gt; 0.
     */
    static double fracaoDoPico(long k, long duracao) {
        long rampa = Math.min((duracao + 2) / 3, (duracao - 1) / 2);   // teto de 1/3, deixando 1 no pico
        if (k < rampa) {
            return (k + 1.0) / (rampa + 1);
        }
        if (k >= duracao - rampa) {
            return (duracao - k) / (rampa + 1.0);
        }
        return 1;
    }

    /** Folga até o limite, de 10 % a 100 % de "logo antes do limite" (5 % da largura). */
    private double sortearFolga() {
        return (PERTO_MINIMO + random.nextDouble() * (1 - PERTO_MINIMO)) * PERTO_DO_LIMITE * faixa.largura();
    }

    /** Desvio além do limite, no miolo do degrau da severidade (na unidade do sensor). */
    private double sortearDesvio(Severidade alvo) {
        int i = alvo.ordinal();
        double piso = i == 0 ? 0 : escala.get(i - 1).desvioMaximo();
        double teto = escala.get(i).semLimite() ? piso * TETO_CRITICA_EM_ALTAS : escala.get(i).desvioMaximo();
        return piso + (MIOLO_INICIO + random.nextDouble() * MIOLO_LARGURA) * (teto - piso);
    }

    /** Arredonda e garante que o valor fique dentro da faixa (limites inclusivos). */
    private double dentroDaFaixa(double v) {
        return Math.max(faixa.minimo(), Math.min(faixa.maximo(), arredondar(v)));
    }

    /** Arredonda e garante que o valor fique fora da faixa, do lado do episódio. */
    private static double foraDaFaixa(double v, double limite, double sinal) {
        double r = arredondar(v);
        return sinal * (r - limite) > 0 ? r : arredondar(limite + sinal * RESOLUCAO);
    }

    private static List<DegrauDeSeveridade> validar(List<DegrauDeSeveridade> escala) {
        Objects.requireNonNull(escala, "escala");
        Severidade[] ordem = Severidade.values();
        if (escala.size() != ordem.length) {
            throw new IllegalArgumentException("a escala deve ter um degrau por severidade");
        }
        for (int i = 0; i < ordem.length; i++) {
            if (escala.get(i).severidade() != ordem[i]) {
                throw new IllegalArgumentException("a escala deve vir na ordem BAIXA, MEDIA, ALTA, CRITICA");
            }
        }
        if (!escala.get(ordem.length - 1).semLimite()) {
            throw new IllegalArgumentException("o último degrau (CRITICA) não tem teto");
        }
        return List.copyOf(escala);
    }

    private static double arredondar(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Fase do sensor na última leitura. */
    public Fase fase() {
        return ciclo.fase();
    }

    /** Episódio em CRISE na última leitura, ou vazio. */
    public Optional<EpisodioDeAnomalia> episodioAtual() {
        return ciclo.episodioAtual();
    }

    /** Episódio em qualquer fase (aquecendo, em crise ou esfriando) na última leitura, ou vazio. */
    public Optional<EpisodioDeAnomalia> episodioEmAndamento() {
        return ciclo.episodioEmAndamento();
    }

    /** Relógio simulado: quantas leituras já foram geradas. */
    public long passo() {
        return ciclo.passo();
    }

    /** Quantos episódios já começaram. */
    public long episodiosIniciados() {
        return ciclo.episodiosIniciados();
    }

    public RegraDeAnomalia faixa() {
        return faixa;
    }
}
