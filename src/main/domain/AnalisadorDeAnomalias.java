package domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Regras de negócio de anomalia — regra pura: recebe uma leitura e devolve um alerta
 * (ou vazio), sem efeitos colaterais, sem estado mutável, sem threads, banco ou arquivo.
 * Por isso a mesma instância pode ser usada por vários consumidores ao mesmo tempo.
 *
 * <p><b>Faixas aceitas</b> (limites inclusivos — valor no limite é normal):</p>
 * <pre>
 *   TEMPERATURA   0 – 80  °C
 *   VELOCIDADE    0 – 120 km/h
 *   PRESSAO       1 – 8   bar
 *   UMIDADE      20 – 90  %
 * </pre>
 *
 * <p><b>Severidade</b> = quanto o valor passou do limite, em % da largura da faixa:
 * até 5 % BAIXA · até 15 % MEDIA · até 30 % ALTA · acima disso CRITICA.
 * Ex.: temperatura 92,5 °C passou 12,5 de uma faixa de 80 → 15,6 % → ALTA.</p>
 */
public class AnalisadorDeAnomalias {

    /** Faixas aceitas por tipo — único lugar onde esses números existem. */
    private static final Map<TipoSensor, RegraDeAnomalia> REGRAS = new EnumMap<>(TipoSensor.class);

    static {
        registrar(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 0.0, 80.0));
        registrar(new RegraDeAnomalia(TipoSensor.VELOCIDADE, 0.0, 120.0));
        registrar(new RegraDeAnomalia(TipoSensor.PRESSAO, 1.0, 8.0));
        registrar(new RegraDeAnomalia(TipoSensor.UMIDADE, 20.0, 90.0));
    }

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

    private static void registrar(RegraDeAnomalia regra) {
        REGRAS.put(regra.tipo(), regra);
    }

    /**
     * Decide se a leitura é anômala.
     *
     * @return o alerta (ainda sem id — não persistido) ou vazio se a leitura está na faixa
     */
    public Optional<Alerta> analisar(LeituraTelemetria leitura) {
        RegraDeAnomalia regra = regraDo(leitura.tipo());
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

    /** Regras em vigor, uma por tipo de sensor, na ordem do enum. Lista imutável. */
    public List<RegraDeAnomalia> regras() {
        return List.copyOf(REGRAS.values());
    }

    /** Regra de um tipo específico. */
    public RegraDeAnomalia regraDo(TipoSensor tipo) {
        RegraDeAnomalia regra = REGRAS.get(tipo);
        if (regra == null) {
            throw new IllegalStateException("Sem regra de anomalia para o tipo " + tipo);
        }
        return regra;
    }

    /**
     * Escala de severidade de um tipo, em unidades do sensor: até quanto a leitura pode
     * passar do limite (acima do máximo ou abaixo do mínimo) em cada severidade.
     * Ex.: temperatura (faixa de largura 80) → BAIXA até 4,0 · MEDIA até 12,0 ·
     * ALTA até 24,0 · CRITICA além disso.
     */
    public List<DegrauDeSeveridade> escala(TipoSensor tipo) {
        double largura = regraDo(tipo).largura();
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
