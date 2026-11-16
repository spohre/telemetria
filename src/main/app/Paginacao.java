package app;

/**
 * Divisão de uma lista em páginas (os cartões de sensor da aba Monitoramento, 4 por página).
 * Lógica pura, sem tela: páginas contadas a partir de 0; {@link #descricao} mostra a partir de 1.
 *
 * @param total     quantos itens existem (≥ 0)
 * @param porPagina quantos cabem numa página (≥ 1)
 */
public record Paginacao(int total, int porPagina) {

    public Paginacao {
        if (total < 0) {
            throw new IllegalArgumentException("total não pode ser negativo");
        }
        if (porPagina < 1) {
            throw new IllegalArgumentException("porPagina deve ser pelo menos 1");
        }
    }

    /** Número de páginas (pelo menos 1, mesmo sem itens). */
    public int paginas() {
        return Math.max(1, (total + porPagina - 1) / porPagina);
    }

    /** Só vale a pena mostrar a paginação com mais itens do que cabem numa página. */
    public boolean necessaria() {
        return total > porPagina;
    }

    /** Página válida mais próxima (ex.: a lista encolheu e a página atual deixou de existir). */
    public int ajustar(int pagina) {
        return Math.max(0, Math.min(pagina, paginas() - 1));
    }

    /** Índice do primeiro item da página (inclusivo). */
    public int inicio(int pagina) {
        return Math.min(total, ajustar(pagina) * porPagina);
    }

    /** Índice depois do último item da página (exclusivo). */
    public int fim(int pagina) {
        return Math.min(total, inicio(pagina) + porPagina);
    }

    /** "Página 2 de 3 · sensores 5–8 de 10" (ou "sensor 5 de 5" quando a página tem um só). */
    public String descricao(int pagina) {
        int p = ajustar(pagina);
        int de = inicio(p) + 1;
        int ate = fim(p);
        String itens = de == ate ? "sensor " + de : "sensores " + de + "–" + ate;
        return "Página " + (p + 1) + " de " + paginas() + " · " + itens + " de " + total;
    }
}
