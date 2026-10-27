package app;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Leituras de uma execução já encerrada (lidas do log), para a aba Monitoramento mostrar
 * os gráficos de cada sensor antes de o sistema ser iniciado de novo.
 *
 * @param inicio   momento da primeira leitura ({@code null} quando vazio)
 * @param fim      momento da última leitura ({@code null} quando vazio)
 * @param leituras em ordem de momento
 */
public record HistoricoExecucao(Instant inicio, Instant fim, List<LeituraRegistrada> leituras) {

    public static final HistoricoExecucao VAZIO = new HistoricoExecucao(null, null, List.of());

    public HistoricoExecucao {
        leituras = leituras == null ? List.of() : List.copyOf(leituras);
    }

    /** Ordena por momento e calcula início e fim a partir das próprias leituras. */
    public static HistoricoExecucao de(List<LeituraRegistrada> leituras) {
        if (leituras == null || leituras.isEmpty()) {
            return VAZIO;
        }
        List<LeituraRegistrada> ordenadas = leituras.stream()
                .sorted(Comparator.comparing(r -> r.leitura().momento()))
                .toList();
        return new HistoricoExecucao(ordenadas.get(0).leitura().momento(),
                ordenadas.get(ordenadas.size() - 1).leitura().momento(), ordenadas);
    }

    public boolean vazio() {
        return leituras.isEmpty();
    }
}
