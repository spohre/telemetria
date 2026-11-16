package domain;

import java.util.Optional;

/**
 * Porta de persistência da configuração dos sensores. O domínio e a aplicação só conhecem
 * esta interface; a implementação com SQL fica em {@code infra.ConfiguracaoRepository}.
 *
 * <p>Implementações lançam {@link RuntimeException} quando o armazenamento está indisponível.</p>
 */
public interface RepositorioDeConfiguracao {

    /**
     * Configuração gravada, ou vazio se nunca foi gravada (primeira execução) — quem chama
     * usa {@link ConfiguracaoSensores#padrao()} e grava.
     */
    Optional<ConfiguracaoSensores> carregar();

    /** Grava a configuração inteira, substituindo a anterior. Ou grava tudo, ou nada. */
    void salvar(ConfiguracaoSensores configuracao);
}
