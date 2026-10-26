package app;

import domain.Severidade;
import domain.TipoSensor;

import java.time.Instant;
import java.util.Map;

/** Resumo final de uma execução — exibido no DialogoExtrato e gravado em arquivo. */
public record ExtratoExecucao(
        Instant inicio,
        Instant fim,
        ConfiguracaoSimulacao configuracao,
        long leiturasProduzidas,
        long leiturasProcessadas,
        Map<TipoSensor, Map<Severidade, Long>> alertasPorTipo,
        long alertasGravados,
        String caminhoLog,
        String urlBanco) {

    public long leiturasPerdidas() {
        return leiturasProduzidas - leiturasProcessadas;
    }

    public long totalAlertas() {
        return alertasPorTipo.values().stream()
                .flatMap(m -> m.values().stream())
                .mapToLong(Long::longValue).sum();
    }
}
