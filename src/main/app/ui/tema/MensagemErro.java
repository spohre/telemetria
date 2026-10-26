package app.ui.tema;

import java.util.Objects;

/**
 * Mensagem de erro no padrão da interface (regra 8 da skill):
 * todo erro diz O QUE ACONTECEU e O QUE FAZER. O detalhe técnico fica recolhido.
 *
 * <pre>
 * new MensagemErro("Não foi possível consultar o histórico",
 *         "O banco de dados não respondeu.",
 *         "Confira se o arquivo data/telemetria.mv.db não está aberto em outro programa e clique em Filtrar de novo.",
 *         excecao);
 * </pre>
 */
public record MensagemErro(String titulo, String oQueAconteceu, String oQueFazer, Throwable causa) {

    public MensagemErro {
        Objects.requireNonNull(titulo, "titulo");
        Objects.requireNonNull(oQueAconteceu, "oQueAconteceu");
        Objects.requireNonNull(oQueFazer, "oQueFazer");
        if (oQueFazer.isBlank()) {
            throw new IllegalArgumentException("Toda mensagem de erro precisa dizer o que fazer");
        }
    }

    /** Texto técnico para o painel recolhido "Detalhes". */
    public String detalheTecnico() {
        if (causa == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Throwable t = causa; t != null; t = t.getCause()) {
            if (!sb.isEmpty()) {
                sb.append("\ncausado por: ");
            }
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return sb.toString();
    }
}
