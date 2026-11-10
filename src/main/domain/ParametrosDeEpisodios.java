package domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Parâmetros da simulação de episódios de anomalia — único lugar onde esses números existem.
 * Tempos medidos em <b>passos</b> do relógio simulado (1 passo = 1 leitura = 1 intervalo do
 * sensor); em segundos, multiplique pelo intervalo.
 *
 * <p>Um episódio tem três fases: <b>aquecimento</b> (o valor sobe até perto do limite),
 * <b>crise</b> (passa do limite, chega à severidade-alvo e volta) e <b>esfriamento</b> (desce
 * até o nível normal). Cada duração é sorteada de forma uniforme entre o mínimo e o máximo.</p>
 *
 * @param mediaEntreEpisodios  média do tempo normal entre o fim de um episódio (fim do
 *                             esfriamento) e o início do próximo (exponencial), em passos
 * @param aquecimentoMinimo    duração mínima do aquecimento (0 = sem rampa de subida)
 * @param aquecimentoMaximo    duração máxima do aquecimento
 * @param duracaoMinima        duração mínima da crise (≥ 2: ela sobe e desce)
 * @param duracaoMaxima        duração máxima da crise
 * @param esfriamentoMinimo    duração mínima do esfriamento (0 = sem rampa de descida)
 * @param esfriamentoMaximo    duração máxima do esfriamento
 * @param probabilidadeDeCrise chance de o episódio passar do limite (0 &lt; p ≤ 1); nos outros o
 *                             sensor aquece até logo antes do limite e esfria sem alerta
 * @param pesosSeveridade      peso relativo de cada severidade-alvo (todas presentes, ≥ 0, soma &gt; 0)
 * @param probabilidadeAcima   por tipo, chance de o episódio sair ACIMA da faixa (o resto é ABAIXO)
 */
public record ParametrosDeEpisodios(double mediaEntreEpisodios,
                                    int aquecimentoMinimo, int aquecimentoMaximo,
                                    int duracaoMinima, int duracaoMaxima,
                                    int esfriamentoMinimo, int esfriamentoMaximo,
                                    double probabilidadeDeCrise, Map<Severidade, Double> pesosSeveridade,
                                    Map<TipoSensor, Double> probabilidadeAcima) {

    public ParametrosDeEpisodios {
        if (!(mediaEntreEpisodios > 0) || Double.isInfinite(mediaEntreEpisodios)) {
            throw new IllegalArgumentException("média entre episódios deve ser positiva e finita");
        }
        validarFaixa("aquecimento", aquecimentoMinimo, aquecimentoMaximo, 0);
        validarFaixa("crise", duracaoMinima, duracaoMaxima, 2);
        validarFaixa("esfriamento", esfriamentoMinimo, esfriamentoMaximo, 0);
        if (!(probabilidadeDeCrise > 0 && probabilidadeDeCrise <= 1)) {
            throw new IllegalArgumentException("probabilidade de crise deve ficar em (0, 1]");
        }
        pesosSeveridade = copiaValidada(pesosSeveridade, Severidade.class, "peso da severidade");
        if (pesosSeveridade.values().stream().anyMatch(p -> p < 0)) {
            throw new IllegalArgumentException("pesos de severidade não podem ser negativos");
        }
        if (pesosSeveridade.values().stream().mapToDouble(Double::doubleValue).sum() <= 0) {
            throw new IllegalArgumentException("pelo menos um peso de severidade deve ser positivo");
        }
        probabilidadeAcima = copiaValidada(probabilidadeAcima, TipoSensor.class, "probabilidade acima");
        if (probabilidadeAcima.values().stream().anyMatch(p -> p < 0 || p > 1)) {
            throw new IllegalArgumentException("probabilidade acima deve ficar entre 0 e 1");
        }
        if (probabilidadeAcima.get(TipoSensor.VELOCIDADE) != 1.0) {
            throw new IllegalArgumentException("velocidade só pode ter desvio acima (abaixo de 0 km/h não existe)");
        }
    }

    /**
     * Valores padrão: um episódio a cada ~78 leituras (50 normais + ~9 aquecendo + ~10 em crise
     * + ~9 esfriando); 80 % deles passam do limite → ~10 % das leituras viram alerta.
     */
    public static ParametrosDeEpisodios padrao() {
        return new ParametrosDeEpisodios(50, 6, 12, 6, 14, 6, 12, 0.8,
                Map.of(Severidade.BAIXA, 40.0, Severidade.MEDIA, 30.0, Severidade.ALTA, 20.0, Severidade.CRITICA, 10.0),
                Map.of(TipoSensor.TEMPERATURA, 0.8,    // superaquecimento é o caso típico
                        TipoSensor.VELOCIDADE, 1.0,    // abaixo de 0 km/h não existe
                        TipoSensor.PRESSAO, 0.5,       // sobrepressão ou vazamento
                        TipoSensor.UMIDADE, 0.5));     // ar saturado ou seco
    }

    /** Cópia com outra média entre episódios (o resto igual). */
    public ParametrosDeEpisodios comMediaEntreEpisodios(double novaMedia) {
        return new ParametrosDeEpisodios(novaMedia, aquecimentoMinimo, aquecimentoMaximo, duracaoMinima,
                duracaoMaxima, esfriamentoMinimo, esfriamentoMaximo, probabilidadeDeCrise, pesosSeveridade,
                probabilidadeAcima);
    }

    /** Cópia com outra probabilidade de crise (o resto igual). */
    public ParametrosDeEpisodios comProbabilidadeDeCrise(double novaProbabilidade) {
        return new ParametrosDeEpisodios(mediaEntreEpisodios, aquecimentoMinimo, aquecimentoMaximo, duracaoMinima,
                duracaoMaxima, esfriamentoMinimo, esfriamentoMaximo, novaProbabilidade, pesosSeveridade,
                probabilidadeAcima);
    }

    private static void validarFaixa(String fase, int minimo, int maximo, int menorPermitido) {
        if (minimo < menorPermitido) {
            throw new IllegalArgumentException("duração mínima do " + fase + " deve ser de pelo menos "
                    + menorPermitido + " passo(s)");
        }
        if (maximo < minimo) {
            throw new IllegalArgumentException("duração máxima do " + fase + " não pode ser menor que a mínima");
        }
    }

    /** Cópia imutável que exige uma entrada finita para cada constante do enum. */
    private static <E extends Enum<E>> Map<E, Double> copiaValidada(Map<E, Double> origem, Class<E> tipo, String nome) {
        Objects.requireNonNull(origem, nome);
        Map<E, Double> copia = new EnumMap<>(tipo);
        for (E chave : tipo.getEnumConstants()) {
            Double valor = origem.get(chave);
            if (valor == null || !Double.isFinite(valor)) {
                throw new IllegalArgumentException(nome + " ausente ou inválido para " + chave);
            }
            copia.put(chave, valor);
        }
        return java.util.Collections.unmodifiableMap(copia);
    }
}
