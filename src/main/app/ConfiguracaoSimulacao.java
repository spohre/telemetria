package app;

/**
 * Parâmetros da simulação editáveis na seção "Simulação". Quantos sensores existem, a faixa
 * de cada um e o intervalo entre leituras ficam na {@link domain.ConfiguracaoSensores}
 * (aba Configurações), gravada no banco.
 */
public record ConfiguracaoSimulacao(int consumidores) {

    public ConfiguracaoSimulacao {
        if (consumidores < 1 || consumidores > 4) {
            throw new IllegalArgumentException("Consumidores deve ficar entre 1 e 4");
        }
    }
}
