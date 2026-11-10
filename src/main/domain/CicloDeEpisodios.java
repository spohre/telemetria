package domain;

import domain.EpisodioDeAnomalia.Direcao;
import domain.EventoDeSimulacao.Tipo;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Simulação de eventos discretos que decide <b>quando</b> um sensor está em cada fase de um
 * episódio de anomalia. Não calcula valores — isso é do gerador que usa este ciclo.
 *
 * <pre>
 *   NORMAL ─INICIO_AQUECIMENTO─► AQUECENDO ─INICIO_EPISODIO─► CRISE
 *          ◄─FIM_ESFRIAMENTO── ESFRIANDO ◄──FIM_EPISODIO──────┘
 * </pre>
 *
 * <ul>
 *   <li><b>Relógio simulado:</b> {@link #avancar()} anda 1 passo (= 1 leitura do sensor) e
 *       processa na {@link AgendaDeEventos} todos os eventos que chegaram.</li>
 *   <li><b>INICIO_AQUECIMENTO:</b> planeja o episódio inteiro — severidade-alvo (pelos pesos),
 *       direção (pelo tipo), se passa do limite e a duração de cada fase (uniformes) — e agenda o
 *       INICIO_EPISODIO. Os eventos seguintes agendam o próximo da sequência.</li>
 *   <li><b>FIM_ESFRIAMENTO:</b> volta ao normal e agenda o próximo INICIO_AQUECIMENTO depois de
 *       um intervalo exponencial (processo de Poisson) de pelo menos 1 passo — episódios nunca
 *       se sobrepõem e há sempre leitura normal entre dois deles.</li>
 * </ul>
 *
 * <p>Com o mesmo {@link Random} (mesma semente) produz sempre os mesmos episódios.
 * Não é thread-safe: cada sensor tem o seu ciclo, usado só pela thread dele.</p>
 */
public class CicloDeEpisodios {

    /** Estado do sensor simulado. */
    public enum Fase {
        NORMAL, AQUECENDO, CRISE, ESFRIANDO
    }

    private final TipoSensor tipo;
    private final ParametrosDeEpisodios parametros;
    private final Random random;
    private final AgendaDeEventos agenda = new AgendaDeEventos();

    private long passo;
    private Fase fase = Fase.NORMAL;
    private EpisodioDeAnomalia emAndamento;    // null = NORMAL
    private long episodiosIniciados;

    public CicloDeEpisodios(TipoSensor tipo, ParametrosDeEpisodios parametros, Random random) {
        this.tipo = Objects.requireNonNull(tipo, "tipo");
        this.parametros = Objects.requireNonNull(parametros, "parametros");
        this.random = Objects.requireNonNull(random, "random");
        agenda.agendar(sortearIntervalo(), Tipo.INICIO_AQUECIMENTO);
    }

    /**
     * Avança o relógio simulado em 1 passo e trata os eventos que chegaram.
     *
     * @return o episódio se o sensor está em CRISE neste passo; vazio nas outras fases
     */
    public Optional<EpisodioDeAnomalia> avancar() {
        passo++;
        agenda.processarAte(passo, this::tratar);
        return episodioAtual();
    }

    private void tratar(EventoDeSimulacao evento) {
        long t = evento.passo();
        switch (evento.tipo()) {
            case INICIO_AQUECIMENTO -> {
                if (fase != Fase.NORMAL) {
                    throw new IllegalStateException("INICIO_AQUECIMENTO na fase " + fase);
                }
                long inicio = t + sortear(parametros.aquecimentoMinimo(), parametros.aquecimentoMaximo());
                long fim = inicio + sortear(parametros.duracaoMinima(), parametros.duracaoMaxima());
                long fimEsfriamento = fim + sortear(parametros.esfriamentoMinimo(), parametros.esfriamentoMaximo());
                boolean passaDoLimite = random.nextDouble() < parametros.probabilidadeDeCrise();
                emAndamento = new EpisodioDeAnomalia(t, inicio, fim, fimEsfriamento, sortearSeveridade(),
                        sortearDirecao(), passaDoLimite);
                episodiosIniciados++;
                fase = Fase.AQUECENDO;
                agenda.agendar(inicio, Tipo.INICIO_EPISODIO);
            }
            case INICIO_EPISODIO -> {
                fase = Fase.CRISE;
                agenda.agendar(emAndamento.fim(), Tipo.FIM_EPISODIO);
            }
            case FIM_EPISODIO -> {
                fase = Fase.ESFRIANDO;
                agenda.agendar(emAndamento.fimEsfriamento(), Tipo.FIM_ESFRIAMENTO);
            }
            case FIM_ESFRIAMENTO -> {
                fase = Fase.NORMAL;
                emAndamento = null;
                agenda.agendar(t + sortearIntervalo(), Tipo.INICIO_AQUECIMENTO);
            }
        }
    }

    /** Tempo normal até o próximo episódio: exponencial com a média configurada, arredondado, ≥ 1. */
    private long sortearIntervalo() {
        double exponencial = -Math.log(1 - random.nextDouble()) * parametros.mediaEntreEpisodios();
        return Math.max(1, Math.round(exponencial));
    }

    private long sortear(int minimo, int maximo) {
        return minimo + random.nextInt(maximo - minimo + 1);
    }

    private Severidade sortearSeveridade() {
        Map<Severidade, Double> pesos = parametros.pesosSeveridade();
        double total = pesos.values().stream().mapToDouble(Double::doubleValue).sum();
        double sorteio = random.nextDouble() * total;
        Severidade ultimaComPeso = null;
        for (Map.Entry<Severidade, Double> e : pesos.entrySet()) {    // EnumMap: ordem BAIXA → CRITICA
            if (e.getValue() <= 0) {
                continue;
            }
            ultimaComPeso = e.getKey();
            sorteio -= e.getValue();
            if (sorteio < 0) {
                return e.getKey();
            }
        }
        return ultimaComPeso;   // só por arredondamento de double
    }

    private Direcao sortearDirecao() {
        return random.nextDouble() < parametros.probabilidadeAcima().get(tipo) ? Direcao.ACIMA : Direcao.ABAIXO;
    }

    /** Fase do sensor no passo atual. */
    public Fase fase() {
        return fase;
    }

    /** O episódio, se o sensor está em CRISE no passo atual; vazio nas outras fases. */
    public Optional<EpisodioDeAnomalia> episodioAtual() {
        return fase == Fase.CRISE ? Optional.of(emAndamento) : Optional.empty();
    }

    /** O episódio em qualquer fase (aquecendo, em crise ou esfriando); vazio no estado NORMAL. */
    public Optional<EpisodioDeAnomalia> episodioEmAndamento() {
        return Optional.ofNullable(emAndamento);
    }

    /** Relógio simulado: quantos passos já foram dados. */
    public long passo() {
        return passo;
    }

    /** Quantos episódios já começaram. */
    public long episodiosIniciados() {
        return episodiosIniciados;
    }

    public TipoSensor tipo() {
        return tipo;
    }

    public ParametrosDeEpisodios parametros() {
        return parametros;
    }
}
