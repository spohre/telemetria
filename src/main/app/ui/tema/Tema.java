package app.ui.tema;

import domain.Severidade;

import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * ÚNICA fonte de cores, fontes e medidas da interface (regra 2 da skill designer-swing).
 * Visual claro de painel técnico: fundo branco, dados em monoespaçada, cor só para estado.
 */
public final class Tema {

    private Tema() {
    }

    // ------------------------------------------------------------------ cores
    public static final class Cor {
        private Cor() {
        }

        // Tema claro: fundo branco, cartões brancos separados por contorno, cor só para estado.
        public static final Color FUNDO = hex(0xFFFFFF);
        public static final Color SUPERFICIE = hex(0xFFFFFF);
        public static final Color SUPERFICIE_ELEVADA = hex(0xF2F4F7);
        public static final Color BORDA = hex(0xDDE2E8);

        public static final Color TEXTO = hex(0x17202B);
        public static final Color TEXTO_SECUNDARIO = hex(0x5B6876);
        public static final Color TEXTO_DESATIVADO = hex(0xA4AEB9);

        public static final Color PRIMARIA = hex(0xDE0202);          // cor de destaque (vermelho)
        public static final Color PRIMARIA_HOVER = hex(0xB80202);
        public static final Color SOBRE_PRIMARIA = hex(0xFFFFFF);

        // Tons escurecidos para manter contraste (≥ 4,5:1) sobre o branco.
        public static final Color NORMAL = hex(0x1A7F37);
        public static final Color SEV_BAIXA = hex(0x0969DA);
        public static final Color SEV_MEDIA = hex(0x9A6700);
        public static final Color SEV_ALTA = hex(0xBC4C00);
        public static final Color SEV_CRITICA = hex(0xCF222E);
        public static final Color PERIGO = SEV_CRITICA;

        /**
         * Linha do gráfico de histórico de cada sensor. Neutra (grafite) de propósito: o destaque
         * é vermelho, igual aos limites e aos alertas, e a leitura precisa se distinguir deles.
         */
        public static final Color GRAFICO_LINHA = hex(0x334155);
        /** Linhas de grade (marcação de valores e de tempo) do gráfico. */
        public static final Color GRAFICO_GRADE = hex(0xEDF0F3);
        /** Linhas pontilhadas de mínimo/máximo aceitos antes do alerta. */
        public static final Color GRAFICO_LIMITE = SEV_CRITICA;

        private static Color hex(int rgb) {
            return new Color(rgb);
        }
    }

    /** Único mapeamento severidade → cor do sistema. */
    public static Color corDa(Severidade severidade) {
        if (severidade == null) {
            return Cor.NORMAL;
        }
        return switch (severidade) {
            case BAIXA -> Cor.SEV_BAIXA;
            case MEDIA -> Cor.SEV_MEDIA;
            case ALTA -> Cor.SEV_ALTA;
            case CRITICA -> Cor.SEV_CRITICA;
        };
    }

    /** Mesma cor com transparência — para fundos de badge e realces suaves. */
    public static Color translucida(Color base, int alfa) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), alfa);
    }

    // ------------------------------------------------------------------ fontes
    public static final class Fonte {
        private Fonte() {
        }

        /** Fontes que vão dentro do jar (src/resources/fontes, licença OFL): mesma aparência em qualquer PC. */
        private static final String[] EMBUTIDAS = {"/fontes/DMSans-Regular.ttf", "/fontes/DMSans-Bold.ttf"};

        static {
            registrarEmbutidas();      // antes de escolher a família: assim "DM Sans" já aparece como instalada
        }

        private static final String UI = primeiraDisponivel("DM Sans", "Segoe UI", "SF Pro Text", "Helvetica Neue", Font.SANS_SERIF);
        private static final String MONO = primeiraDisponivel("JetBrains Mono", "Cascadia Mono", "Consolas", "DejaVu Sans Mono", Font.MONOSPACED);

        public static final Font TITULO = new Font(UI, Font.BOLD, 18);
        public static final Font SECAO = new Font(UI, Font.BOLD, 13);
        public static final Font CORPO = new Font(UI, Font.PLAIN, 13);
        public static final Font CORPO_NEGRITO = new Font(UI, Font.BOLD, 13);
        public static final Font ROTULO = new Font(UI, Font.PLAIN, 11);
        public static final Font ROTULO_NEGRITO = new Font(UI, Font.BOLD, 11);
        public static final Font VALOR = new Font(MONO, Font.BOLD, 26);
        public static final Font DADO = new Font(MONO, Font.PLAIN, 12);
        public static final Font DADO_NEGRITO = new Font(MONO, Font.BOLD, 12);
        /** Rótulos dos eixos do gráfico de sensor. */
        public static final Font EIXO = new Font(MONO, Font.PLAIN, 10);

        private static void registrarEmbutidas() {
            GraphicsEnvironment ambiente = GraphicsEnvironment.getLocalGraphicsEnvironment();
            for (String caminho : EMBUTIDAS) {
                try (InputStream in = Tema.class.getResourceAsStream(caminho)) {
                    if (in != null) {
                        ambiente.registerFont(Font.createFont(Font.TRUETYPE_FONT, in));
                    }
                } catch (FontFormatException | IOException e) {
                    // fonte embutida ilegível: segue com a próxima família da lista
                }
            }
        }

        private static String primeiraDisponivel(String... familias) {
            Set<String> instaladas = new HashSet<>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (String f : familias) {
                if (instaladas.contains(f)) {
                    return f;
                }
            }
            return familias[familias.length - 1];
        }
    }

    // ------------------------------------------------------------------ medidas (grade de 4 px)
    public static final class Medida {
        private Medida() {
        }

        public static final int ESP_1 = 4;
        public static final int ESP_2 = 8;
        public static final int ESP_3 = 12;
        public static final int ESP_4 = 16;
        public static final int ESP_6 = 24;
        public static final int RAIO = 8;
        public static final int ALTURA_BOTAO = 34;
        /** Botão só de ícone dentro de linhas compactas (ex.: expandir o gráfico do sensor). */
        public static final int ALTURA_BOTAO_PEQUENO = 24;
        public static final int ALTURA_LINHA_TABELA = 28;
        public static final int ICONE_PEQ = 16;
        public static final int ICONE = 20;
        public static final int ICONE_GRANDE = 28;
        public static final int LARGURA_JANELA = 1280;
        public static final int ALTURA_JANELA = 800;
        public static final int LARGURA_MINIMA = 1024;
        public static final int ALTURA_MINIMA = 680;
        /** Linhas mantidas na tabela de leituras ao vivo. */
        public static final int MAX_LINHAS_AO_VIVO = 200;
        /** Altura da linha de um sensor (gráfico compacto) na aba Monitoramento. */
        public static final int ALTURA_SENSOR = 100 + 2 * ESP_3;   // gráfico compacto de 100 px + margens
        /** Altura mínima da área das tabelas (leituras ao vivo e alertas) ao arrastar o divisor. */
        public static final int ALTURA_MINIMA_TABELAS = 176;
        /**
         * Linha expandida: ocupa a área visível da lista de sensores, entre estes limites
         * (arrastar o divisor para cima dá ainda mais altura ao gráfico).
         */
        public static final int ALTURA_SENSOR_EXPANDIDO = 360;
        public static final int ALTURA_SENSOR_EXPANDIDO_MAX = 720;
        /** Largura da coluna de informações à direita do gráfico. */
        public static final int LARGURA_INFO_SENSOR = 268;
        /** Pontos guardados por sensor antes de o gráfico reduzir a resolução (alertas nunca são descartados). */
        public static final int MAX_PONTOS_GRAFICO = 20_000;
        /** Traço da linha de leitura e tracejado das linhas de limite (px). */
        public static final float TRACO_GRAFICO = 1.6f;
        public static final float TRACO_LIMITE = 1.2f;
        public static final float[] TRACEJADO_LIMITE = {5f, 4f};

        /** Intervalo de agrupamento de repaint da tela ao vivo (ms). */
        public static final int INTERVALO_ATUALIZACAO_MS = 250;
    }

    /**
     * Aplica o tema aos componentes padrão do Swing (combos, spinners, tooltips, scroll,
     * diálogos). Chamar UMA vez, na EDT, antes de criar a primeira janela.
     */
    public static void instalar() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignorada) {
            // Metal sempre existe; se falhar, seguimos com o LAF atual.
        }
        Object[] cores = {
                "Panel.background", Cor.FUNDO,
                "OptionPane.background", Cor.SUPERFICIE,
                "OptionPane.messageForeground", Cor.TEXTO,
                "Label.foreground", Cor.TEXTO,
                "ComboBox.background", Cor.SUPERFICIE_ELEVADA,
                "ComboBox.foreground", Cor.TEXTO,
                "ComboBox.selectionBackground", Cor.PRIMARIA,
                "ComboBox.selectionForeground", Cor.SOBRE_PRIMARIA,
                "ComboBox.buttonBackground", Cor.SUPERFICIE_ELEVADA,
                "ComboBox.buttonDarkShadow", Cor.TEXTO_SECUNDARIO,
                "TextField.background", Cor.SUPERFICIE_ELEVADA,
                "TextField.foreground", Cor.TEXTO,
                "TextField.caretForeground", Cor.PRIMARIA,
                "FormattedTextField.background", Cor.SUPERFICIE_ELEVADA,
                "FormattedTextField.foreground", Cor.TEXTO,
                "TextArea.background", Cor.SUPERFICIE_ELEVADA,
                "TextArea.foreground", Cor.TEXTO,
                "Spinner.background", Cor.SUPERFICIE_ELEVADA,
                "List.background", Cor.SUPERFICIE,
                "List.foreground", Cor.TEXTO,
                "List.selectionBackground", Cor.PRIMARIA,
                "List.selectionForeground", Cor.SOBRE_PRIMARIA,
                "ScrollPane.background", Cor.SUPERFICIE,
                "Viewport.background", Cor.SUPERFICIE,
                "ScrollBar.background", Cor.SUPERFICIE,
                "ScrollBar.thumb", Cor.BORDA,
                "ScrollBar.track", Cor.SUPERFICIE,
                "ToolTip.background", Cor.SUPERFICIE_ELEVADA,
                "ToolTip.foreground", Cor.TEXTO,
                "Table.background", Cor.SUPERFICIE,
                "Table.foreground", Cor.TEXTO,
                "Table.gridColor", Cor.BORDA,
                "Table.selectionBackground", translucida(Cor.PRIMARIA, 60),
                "Table.selectionForeground", Cor.TEXTO,
                "TableHeader.background", Cor.SUPERFICIE_ELEVADA,
                "TableHeader.foreground", Cor.TEXTO_SECUNDARIO,
                "CheckBox.background", Cor.SUPERFICIE,
                "CheckBox.foreground", Cor.TEXTO,
        };
        for (int i = 0; i < cores.length; i += 2) {
            UIManager.put(cores[i], new ColorUIResource((Color) cores[i + 1]));
        }
        for (String chave : new String[]{"Label.font", "Button.font", "ComboBox.font", "TextField.font",
                "Spinner.font", "FormattedTextField.font", "OptionPane.messageFont", "ToolTip.font",
                "List.font", "CheckBox.font", "TableHeader.font"}) {
            UIManager.put(chave, new FontUIResource(Fonte.CORPO));
        }
        UIManager.put("Table.font", new FontUIResource(Fonte.DADO));
    }
}
