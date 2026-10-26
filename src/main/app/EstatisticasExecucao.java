package app;

/** Fotografia dos contadores da execução, enviada periodicamente à interface. */
public record EstatisticasExecucao(
        long leiturasProduzidas,
        long leiturasProcessadas,
        int tamanhoFila,
        int capacidadeFila,
        long alertasGerados,
        int threadsAtivas,
        boolean bancoConectado,
        long segundosDecorridos) {

    /** Leituras que saíram dos sensores e ainda não foram (ou nunca serão) processadas. */
    public long leiturasPendentes() {
        return leiturasProduzidas - leiturasProcessadas;
    }

    public static EstatisticasExecucao vazia(int capacidadeFila) {
        return new EstatisticasExecucao(0, 0, 0, capacidadeFila, 0, 0, true, 0);
    }
}
