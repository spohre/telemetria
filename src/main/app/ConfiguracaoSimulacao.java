package app;

/** Parâmetros da simulação editáveis na aba Regras e sensores. */
public record ConfiguracaoSimulacao(int sensoresPorTipo, int intervaloMs, int consumidores) {

    public ConfiguracaoSimulacao {
        if (sensoresPorTipo < 1 || sensoresPorTipo > 10) {
            throw new IllegalArgumentException("Sensores por tipo deve ficar entre 1 e 10");
        }
        if (intervaloMs < 10 || intervaloMs > 5000) {
            throw new IllegalArgumentException("Intervalo deve ficar entre 10 e 5000 ms");
        }
        if (consumidores < 1 || consumidores > 4) {
            throw new IllegalArgumentException("Consumidores deve ficar entre 1 e 4");
        }
    }
}
