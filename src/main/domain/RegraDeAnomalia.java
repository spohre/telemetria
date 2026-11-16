package domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Faixa de valores aceita para um sensor (ou a faixa padrão de um tipo). Os limites são
 * inclusivos: um valor exatamente no mínimo ou no máximo é normal.
 *
 * <p><b>Faixas padrão do projeto</b> — único lugar onde esses números existem:</p>
 * <pre>
 *   TEMPERATURA   0 – 80  °C
 *   VELOCIDADE    0 – 120 km/h
 *   PRESSAO       1 – 8   bar
 *   UMIDADE      20 – 90  %
 * </pre>
 * São o ponto de partida da {@link ConfiguracaoSensores#padrao()}; o usuário pode mudar a
 * faixa padrão de cada tipo e a faixa de cada sensor.
 */
public record RegraDeAnomalia(TipoSensor tipo, double minimo, double maximo) {

    private static final Map<TipoSensor, RegraDeAnomalia> PADROES = new EnumMap<>(TipoSensor.class);

    static {
        registrar(new RegraDeAnomalia(TipoSensor.TEMPERATURA, 0.0, 80.0));
        registrar(new RegraDeAnomalia(TipoSensor.VELOCIDADE, 0.0, 120.0));
        registrar(new RegraDeAnomalia(TipoSensor.PRESSAO, 1.0, 8.0));
        registrar(new RegraDeAnomalia(TipoSensor.UMIDADE, 20.0, 90.0));
    }

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    public RegraDeAnomalia {
        Objects.requireNonNull(tipo, "tipo");
        if (!Double.isFinite(minimo) || !Double.isFinite(maximo)) {
            throw new IllegalArgumentException("Faixa inválida: mínimo e máximo precisam ser números");
        }
        if (!(minimo < maximo)) {
            throw new IllegalArgumentException(String.format(PT_BR,
                    "Faixa inválida: o mínimo (%.1f) deve ser menor que o máximo (%.1f)", minimo, maximo));
        }
    }

    private static void registrar(RegraDeAnomalia regra) {
        PADROES.put(regra.tipo(), regra);
    }

    /** Faixa padrão do projeto para um tipo de sensor. */
    public static RegraDeAnomalia padraoDo(TipoSensor tipo) {
        RegraDeAnomalia regra = PADROES.get(Objects.requireNonNull(tipo, "tipo"));
        if (regra == null) {
            throw new IllegalStateException("Sem faixa padrão para o tipo " + tipo);
        }
        return regra;
    }

    /** Faixas padrão do projeto, uma por tipo, na ordem do enum. Mapa imutável. */
    public static Map<TipoSensor, RegraDeAnomalia> padroes() {
        return Collections.unmodifiableMap(new EnumMap<>(PADROES));
    }

    /** Mesma faixa com outros limites (mesmo tipo). */
    public RegraDeAnomalia com(double minimo, double maximo) {
        return new RegraDeAnomalia(tipo, minimo, maximo);
    }

    /** Largura da faixa aceita (base para calcular a severidade do desvio). */
    public double largura() {
        return maximo - minimo;
    }
}
