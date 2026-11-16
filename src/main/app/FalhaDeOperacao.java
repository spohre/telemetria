package app;

import java.util.Objects;

/**
 * Uma operação pedida pela tela não pôde ser concluída por um problema de infraestrutura
 * (ex.: o banco não gravou a configuração). Carrega as duas partes que toda mensagem de erro
 * da interface precisa: <b>o que aconteceu</b> ({@link #getMessage()}) e <b>o que fazer</b>.
 * A causa técnica vai junto para o painel "Detalhes".
 */
public class FalhaDeOperacao extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String oQueFazer;

    public FalhaDeOperacao(String oQueAconteceu, String oQueFazer, Throwable causa) {
        super(Objects.requireNonNull(oQueAconteceu, "oQueAconteceu"), causa);
        this.oQueFazer = Objects.requireNonNull(oQueFazer, "oQueFazer");
    }

    public String oQueAconteceu() {
        return getMessage();
    }

    public String oQueFazer() {
        return oQueFazer;
    }
}
