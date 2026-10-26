package domain;

import java.util.Objects;
import java.util.Random;

/**
 * Simulação de medições: um passeio aleatório que fica dentro da faixa normal do tipo,
 * com picos ocasionais para fora dela (para que o sistema gere alertas).
 *
 * <ul>
 *   <li>Começa no meio da faixa; a cada leitura anda um passo aleatório (gaussiano, desvio
 *       de 3 % da largura da faixa) e é mantido dentro dos limites.</li>
 *   <li>Com a probabilidade de pico (padrão 5 %), a leitura sai da faixa — acima ou abaixo —
 *       entre 2 % e 40 % da largura além do limite. O pico não muda a posição do passeio.</li>
 *   <li>Valores arredondados a 1 casa decimal.</li>
 * </ul>
 *
 * <p>Com o mesmo {@link Random} (mesma semente) gera sempre a mesma sequência — útil em testes.
 * Não é thread-safe: cada sensor deve ter o seu gerador.</p>
 */
public class GeradorPasseioAleatorio implements GeradorDeValores {

    public static final double PROBABILIDADE_PICO_PADRAO = 0.05;

    private static final double PASSO = 0.03;
    private static final double PICO_MINIMO = 0.02;
    private static final double PICO_MAXIMO = 0.40;

    private final RegraDeAnomalia faixa;
    private final Random random;
    private final double probabilidadePico;
    private double atual;

    public GeradorPasseioAleatorio(RegraDeAnomalia faixa, Random random, double probabilidadePico) {
        this.faixa = Objects.requireNonNull(faixa, "faixa");
        this.random = Objects.requireNonNull(random, "random");
        if (probabilidadePico < 0 || probabilidadePico > 1) {
            throw new IllegalArgumentException("probabilidadePico deve ficar entre 0 e 1");
        }
        this.probabilidadePico = probabilidadePico;
        this.atual = (faixa.minimo() + faixa.maximo()) / 2;
    }

    public GeradorPasseioAleatorio(RegraDeAnomalia faixa, Random random) {
        this(faixa, random, PROBABILIDADE_PICO_PADRAO);
    }

    @Override
    public double proximoValor() {
        double largura = faixa.largura();
        if (random.nextDouble() < probabilidadePico) {
            double excesso = largura * (PICO_MINIMO + random.nextDouble() * (PICO_MAXIMO - PICO_MINIMO));
            return arredondar(random.nextBoolean() ? faixa.maximo() + excesso : faixa.minimo() - excesso);
        }
        atual += random.nextGaussian() * largura * PASSO;
        atual = Math.max(faixa.minimo(), Math.min(faixa.maximo(), atual));
        return arredondar(atual);
    }

    private static double arredondar(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
