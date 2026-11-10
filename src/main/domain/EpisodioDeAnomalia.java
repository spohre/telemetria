package domain;

import java.util.Objects;

/**
 * Um episódio de anomalia de um sensor, com as datas (em passos do relógio simulado) das três
 * fases:
 *
 * <pre>
 *   [inicioAquecimento, inicio)  aquecendo — o valor sobe até logo antes do limite
 *   [inicio, fim)                crise     — passa do limite (se passaDoLimite), chega ao pico e volta
 *   [fim, fimEsfriamento)        esfriando — desce até o nível normal
 * </pre>
 *
 * <p>A severidade é só o <i>alvo</i> (o pico) da simulação: quem decide se a leitura é um
 * alerta, e com qual severidade, continua sendo o {@link AnalisadorDeAnomalias}. Um episódio
 * que não passa do limite é um quase-incidente: aquece, fica logo antes do limite e esfria.</p>
 */
public record EpisodioDeAnomalia(long inicioAquecimento, long inicio, long fim, long fimEsfriamento,
                                 Severidade severidadeAlvo, Direcao direcao, boolean passaDoLimite) {

    /** Para que lado da faixa o episódio vai. */
    public enum Direcao {
        ACIMA, ABAIXO
    }

    public EpisodioDeAnomalia {
        Objects.requireNonNull(severidadeAlvo, "severidadeAlvo");
        Objects.requireNonNull(direcao, "direcao");
        if (inicioAquecimento < 0) {
            throw new IllegalArgumentException("inicioAquecimento não pode ser negativo");
        }
        if (inicio < inicioAquecimento) {
            throw new IllegalArgumentException("a crise não pode começar antes do aquecimento");
        }
        if (fim <= inicio) {
            throw new IllegalArgumentException("fim da crise deve ser depois do inicio");
        }
        if (fimEsfriamento < fim) {
            throw new IllegalArgumentException("o esfriamento não pode terminar antes da crise");
        }
    }

    /** Episódio sem aquecimento nem esfriamento, que passa do limite. */
    public EpisodioDeAnomalia(long inicio, long fim, Severidade severidadeAlvo, Direcao direcao) {
        this(inicio, inicio, fim, fim, severidadeAlvo, direcao, true);
    }

    /** Quantos passos (leituras) a crise dura. */
    public long duracao() {
        return fim - inicio;
    }

    /** Quantos passos o aquecimento dura. */
    public long aquecimento() {
        return inicio - inicioAquecimento;
    }

    /** Quantos passos o esfriamento dura. */
    public long esfriamento() {
        return fimEsfriamento - fim;
    }

    /** {@code true} se o passo está dentro da crise ({@code inicio <= passo < fim}). */
    public boolean contem(long passo) {
        return passo >= inicio && passo < fim;
    }
}
