package app.ui.tema;

import domain.Severidade;

import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * ÚNICA fonte de cores, fontes e medidas da interface (regra 2 da skill designer-swing).
 * Visual "sala de controle": grafite escuro, dados em monoespaçada, cor só para estado.
 */
public final class Tema {

    private Tema() {
    }

    // ------------------------------------------------------------------ cores
    public static final class Cor {
        private Cor() {
        }

        public static final Color FUNDO = hex(0x0E141B);
        public static final Color SUPERFICIE = hex(0x151E28);
        public static final Color SUPERFICIE_ELEVADA = hex(0x1C2733);
        public static final Color BORDA = hex(0x2A3846);

        public static final Color TEXTO = hex(0xE6EDF3);
        public static final Color TEXTO_SECUNDARIO = hex(0x8B9AAB);
        public static final Color TEXTO_DESATIVADO = hex(0x56636F);

        public static final Color PRIMARIA = hex(0x2BC4D8);
        public static final Color PRIMARIA_HOVER = hex(0x5AD5E5);
        public static final Color SOBRE_PRIMARIA = hex(0x06232A);

        public static final Color NORMAL = hex(0x3FB950);
        public static final Color SEV_BAIXA = hex(0x58A6FF);
        public static final Color SEV_MEDIA = hex(0xE3B341);
        public static final Color SEV_ALTA = hex(0xF0883E);
        public static final Color SEV_CRITICA = hex(0xF85149);
        public static final Color PERIGO = SEV_CRITICA;

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

        private static final String UI = primeiraDisponivel("Inter", "Segoe UI", "SF Pro Text", "Helvetica Neue", Font.SANS_SERIF);
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
