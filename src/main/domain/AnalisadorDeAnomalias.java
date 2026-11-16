package domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Regras de negócio de anomalia — regra pura: recebe uma leitura e devolve um alerta
 * (ou vazio), sem efeitos colaterais, sem estado mutável, sem threads, banco ou arquivo.
 * Por isso a mesma instância pode ser usada por vários consumidores ao mesmo tempo.
 *
 * <p><b>Faixa aceita por sensor</b> (limites inclusivos — valor no limite é normal): cada
 * sensor da {@link ConfiguracaoSensores} tem a sua faixa; a regra é escolhida pelo
 * {@code sensorId} da leitura. Sensor que não está na configuração (ou cujo tipo não bate
 * com o da leitura) usa a faixa padrão do tipo. Sem configuração, valem as faixas padrão do
 * projeto ({@link RegraDeAnomalia#padraoDo}).</p>
 *
 * <p><b>Severidade</b> = quanto o valor passou do limite, em % da largura da faixa
 * <i>do sensor</i>: até 5 % BAIXA · até 15 % MEDIA · até 30 % ALTA · acima disso CRITICA.
 * Ex.: temperatura 0–80, valor 92,5 → passou 12,5 de 80 → 15,6 % → ALTA.</p>
 *
 * <p><b>Concorrência:</b> a configuração é copiada no construtor e nunca muda. Para usar
 * outras faixas, cria-se um analisador novo (o controlador faz isso ao iniciar).</p>
 */
public class AnalisadorDeAnomalias {

    /**
     * Teto de cada severidade, como fração da largura da faixa — único lugar onde esses
     * números existem. Em ordem; acima do último é CRITICA.
     */
    private static final Map<Severidade, Double> TETO_DO_DESVIO = new EnumMap<>(Map.of(
            Severidade.BAIXA, 0.05,
            Severidade.MEDIA, 0.15,
            Severidade.ALTA, 0.30));

    /**
     * Tolerância para erros de arredondamento de double (ex.: 8,35 − 8 = 0,34999...),
     * para que um desvio exatamente na fronteira caia na severidade mais branda.
     */
    private static final double TOLERANCIA = 1e-9;

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    /** Faixa padrão de cada tipo (para sensores fora da configuração). */
    private final Map<TipoSensor, RegraDeAnomalia> padroes;
    /** Faixa de cada sensor configurado, por id. */
    private final Map<String, RegraDeAnomalia> porSensor;

    /** Analisador com as faixas padrão do projeto ({@link ConfiguracaoSensores#padrao()}). */
    public AnalisadorDeAnomalias() {
        this(ConfiguracaoSensores.padrao());
    }

    /** Analisador com a faixa de cada sensor da configuração. */
    public AnalisadorDeAnomalias(ConfiguracaoSensores configuracao) {
        Objects.requireNonNull(configuracao, "configuracao");
        this.padroes = configuracao.padroes();                    // já imutável, ordem do enum
        Map<String, RegraDeAnomalia> mapa = new HashMap<>();
        configuracao.sensores().forEach(s -> mapa.put(s.id(), s.regra()));
        this.porSensor = Map.copyOf(mapa);
    }

    /**
     * Decide se a leitura é anômala, pela faixa do sensor que a produziu.
     *
     * @return o alerta (ainda sem id — não persistido) ou vazio se a leitura está na faixa
     */
    public Optional<Alerta> analisar(LeituraTelemetria leitura) {
        RegraDeAnomalia regra = regraDo(leitura.sensorId(), leitura.tipo());
        double valor = leitura.valor();

        String direcao;
        double limite;
        if (valor > regra.maximo()) {
            direcao = "acima";
            limite = regra.maximo();
        } else if (valor < regra.minimo()) {
            direcao = "abaixo";
            limite = regra.minimo();
        } else {
            return Optional.empty();
        }

        double desvio = Math.abs(valor - limite) / regra.largura();
        String regraViolada = String.format(PT_BR, "%s do limite %.1f %s",
                direcao, limite, leitura.tipo().unidade());
        return Optional.of(Alerta.de(leitura, regraViolada, severidadePara(desvio)));
    }

    /** Faixas padrão em vigor, uma por tipo de sensor, na ordem do enum. Lista imutável. */
    public List<RegraDeAnomalia> regras() {
        return List.copyOf(padroes.values());
    }

    /** Faixa padrão de um tipo. */
    public RegraDeAnomalia regraDo(TipoSensor tipo) {
        RegraDeAnomalia regra = padroes.get(tipo);
        if (regra == null) {
            throw new IllegalStateException("Sem regra de anomalia para o tipo " + tipo);
        }
        return regra;
    }

    /**
     * Faixa de um sensor. Se o sensor não está na configuração, ou está com outro tipo,
     * devolve a faixa padrão de {@code tipo}.
     */
    public RegraDeAnomalia regraDo(String sensorId, TipoSensor tipo) {
        RegraDeAnomalia regra = porSensor.get(sensorId);
        return regra != null && regra.tipo() == tipo ? regra : regraDo(tipo);
    }

    /** Escala de severidade da faixa padrão de um tipo (ver {@link #escalaPara}). */
    public List<DegrauDeSeveridade> escala(TipoSensor tipo) {
        return escalaPara(regraDo(tipo));
    }

    /** Escala de severidade da faixa de um sensor (ver {@link #escalaPara}). */
    public List<DegrauDeSeveridade> escala(String sensorId, TipoSensor tipo) {
        return escalaPara(regraDo(sensorId, tipo));
    }

    /**
     * Escala de severidade de uma faixa, em unidades do sensor: até quanto a leitura pode
     * passar do limite (acima do máximo ou abaixo do mínimo) em cada severidade.
     * Ex.: faixa de largura 80 → BAIXA até 4,0 · MEDIA até 12,0 · ALTA até 24,0 ·
     * CRITICA além disso.
     */
    public static List<DegrauDeSeveridade> escalaPara(RegraDeAnomalia regra) {
        double largura = regra.largura();
        List<DegrauDeSeveridade> degraus = new ArrayList<>();
        TETO_DO_DESVIO.forEach((sev, fracao) -> degraus.add(new DegrauDeSeveridade(sev, largura * fracao)));
        degraus.add(new DegrauDeSeveridade(Severidade.CRITICA, Double.POSITIVE_INFINITY));
        return List.copyOf(degraus);
    }

    /** Converte o desvio (fração da largura da faixa) em severidade. */
    static Severidade severidadePara(double desvio) {
        for (Map.Entry<Severidade, Double> teto : TETO_DO_DESVIO.entrySet()) {   // EnumMap: ordem BAIXA→ALTA
            if (desvio <= teto.getValue() + TOLERANCIA) {
                return teto.getKey();
            }
        }
        return Severidade.CRITICA;
    }
}
