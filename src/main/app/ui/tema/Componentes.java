package app.ui.tema;

import app.ui.tema.Icones.Icone;
import domain.Severidade;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.RoundRectangle2D;

import static app.ui.tema.Tema.Cor;
import static app.ui.tema.Tema.Fonte;
import static app.ui.tema.Tema.Medida;

/**
 * Fábrica dos componentes visuais padronizados. As telas montam a interface SÓ com
 * o que sai daqui (botões, painéis, rótulos, badges, tabelas, diálogo de erro).
 */
public final class Componentes {

    private Componentes() {
    }

    // ================================================================== botões

    public enum Estilo { PRIMARIO, SECUNDARIO, PERIGO, FANTASMA }

    public static JButton botao(String texto, Icone icone, Estilo estilo) {
        return botao(texto, icone, estilo, Medida.ALTURA_BOTAO);
    }

    private static JButton botao(String texto, Icone icone, Estilo estilo, int altura) {
        boolean soIcone = texto == null || texto.isEmpty();
        JButton b = new JButton(texto) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getPreferredSize() {   // largura acompanha o texto; altura é sempre a do tema
                Dimension d = super.getPreferredSize();
                return new Dimension(soIcone ? altura : d.width, altura);
            }

            @Override
            public Dimension getMaximumSize() {     // BoxLayout usa o máximo: não deixar achatar/esticar
                return getPreferredSize();
            }
        };
        b.setUI(new BotaoUI(estilo));
        b.setFont(Fonte.CORPO_NEGRITO);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setIconTextGap(Medida.ESP_2);
        int lado = !soIcone ? Medida.ESP_4 : (altura >= Medida.ALTURA_BOTAO ? Medida.ESP_2 : (altura - Medida.ICONE_PEQ) / 2);
        b.setBorder(BorderFactory.createEmptyBorder(0, lado, 0, lado));
        if (icone != null) {
            // O ícone segue a cor do texto: muda junto no hover/desabilitado.
            b.setIcon(Icones.queSegueOTexto(icone, soIcone && altura >= Medida.ALTURA_BOTAO ? Medida.ICONE : Medida.ICONE_PEQ));
        }
        // Cor do texto atualizada por EVENTO de estado — nunca dentro de paint().
        Runnable atualizar = () -> b.setForeground(corTexto(estilo, b.isEnabled(), b.getModel().isRollover()));
        b.getModel().addChangeListener(e -> atualizar.run());
        b.addPropertyChangeListener("enabled", e -> atualizar.run());
        atualizar.run();
        return b;
    }

    /** Botão só com ícone (barra de ferramentas). Sempre com tooltip explicando a ação. */
    public static JButton botaoIcone(Icone icone, String dica) {
        JButton b = botao("", icone, Estilo.FANTASMA);
        b.setToolTipText(dica);
        return b;
    }

    /** Botão só com ícone, menor ({@link Medida#ALTURA_BOTAO_PEQUENO}), para linhas compactas. */
    public static JButton botaoIconePequeno(Icone icone, String dica) {
        JButton b = botao("", icone, Estilo.FANTASMA, Medida.ALTURA_BOTAO_PEQUENO);
        b.setToolTipText(dica);
        return b;
    }

    private static Color corTexto(Estilo e, boolean ativo, boolean hover) {
        if (!ativo) {
            return Cor.TEXTO_DESATIVADO;
        }
        return switch (e) {
            case PRIMARIO -> Cor.SOBRE_PRIMARIA;
            case SECUNDARIO -> Cor.TEXTO;
            case PERIGO -> hover ? Cor.FUNDO : Cor.PERIGO;
            case FANTASMA -> hover ? Cor.TEXTO : Cor.TEXTO_SECUNDARIO;
        };
    }

    /** UI própria: botão plano, arredondado, com estados hover/pressionado/foco/desabilitado. Só desenha. */
    private static final class BotaoUI extends BasicButtonUI {
        private final Estilo estilo;

        BotaoUI(Estilo estilo) {
            this.estilo = estilo;
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            ButtonModel m = b.getModel();
            boolean ativo = b.isEnabled();
            boolean hover = ativo && m.isRollover();
            boolean apertado = ativo && m.isArmed() && m.isPressed();

            Color fundo = switch (estilo) {
                case PRIMARIO -> !ativo ? Cor.SUPERFICIE_ELEVADA : (hover ? Cor.PRIMARIA_HOVER : Cor.PRIMARIA);
                case PERIGO -> hover ? Cor.PERIGO : null;
                default -> apertado ? Cor.BORDA : (hover ? Cor.SUPERFICIE_ELEVADA : null);
            };
            Color borda = switch (estilo) {
                case SECUNDARIO -> ativo ? Cor.BORDA : Tema.translucida(Cor.BORDA, 120);
                case PERIGO -> ativo ? Tema.translucida(Cor.PERIGO, 160) : Tema.translucida(Cor.BORDA, 120);
                default -> null;
            };

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            var forma = new RoundRectangle2D.Double(0.5, 0.5, c.getWidth() - 1, c.getHeight() - 1,
                    Medida.RAIO * 2, Medida.RAIO * 2);
            if (fundo != null) {
                g2.setColor(fundo);
                g2.fill(forma);
            }
            if (borda != null) {
                g2.setColor(borda);
                g2.draw(forma);
            }
            if (b.isFocusOwner()) {
                g2.setColor(Cor.PRIMARIA);
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Double(1, 1, c.getWidth() - 2, c.getHeight() - 2,
                        Medida.RAIO * 2, Medida.RAIO * 2));
            }
            g2.dispose();
            super.paint(g, c);
        }

        @Override
        protected void paintText(Graphics g, AbstractButton b, java.awt.Rectangle r, String texto) {
            // Desabilitado: texto plano na cor desativada (o Basic desenharia em relevo).
            g.setColor(b.getForeground());
            g.setFont(b.getFont());
            java.awt.FontMetrics fm = g.getFontMetrics();
            ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawString(texto, r.x, r.y + fm.getAscent());
        }
    }

    // ================================================================== painéis

    /** Painel-cartão: fundo SUPERFICIE, borda arredondada, margem interna padrão. */
    public static JPanel cartao() {
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Cor.SUPERFICIE);
                g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), Medida.RAIO * 2, Medida.RAIO * 2));
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setBorder(new BordaArredondada(Cor.BORDA, Medida.ESP_4));
        return p;
    }

    /** Seção com título (ícone + texto) e conteúdo, já dentro de um cartão. */
    public static JPanel secao(String titulo, Icone icone, JComponent conteudo, JComponent... acoes) {
        JPanel card = cartao();
        card.setLayout(new BorderLayout(0, Medida.ESP_3));

        JPanel topo = transparente(new BorderLayout());
        JLabel lbl = new JLabel(titulo, icone == null ? null : Icones.de(icone, Medida.ICONE_PEQ, Cor.TEXTO_SECUNDARIO),
                SwingConstants.LEFT);
        lbl.setFont(Fonte.SECAO);
        lbl.setForeground(Cor.TEXTO);
        lbl.setIconTextGap(Medida.ESP_2);
        topo.add(lbl, BorderLayout.WEST);
        if (acoes.length > 0) {
            JPanel pa = transparente(new FlowLayout(FlowLayout.RIGHT, Medida.ESP_2, 0));
            for (JComponent a : acoes) {
                pa.add(a);
            }
            topo.add(pa, BorderLayout.EAST);
        }
        card.add(topo, BorderLayout.NORTH);
        card.add(conteudo, BorderLayout.CENTER);
        return card;
    }

    public static JPanel transparente(java.awt.LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setOpaque(false);
        return p;
    }

    // ================================================================== textos

    public static JLabel titulo(String texto) {
        return rotulo(texto, Fonte.TITULO, Cor.TEXTO);
    }

    public static JLabel rotulo(String texto) {
        return rotulo(texto, Fonte.ROTULO, Cor.TEXTO_SECUNDARIO);
    }

    public static JLabel dado(String texto) {
        return rotulo(texto, Fonte.DADO, Cor.TEXTO);
    }

    public static JLabel rotulo(String texto, java.awt.Font fonte, Color cor) {
        JLabel l = new JLabel(texto);
        l.setFont(fonte);
        l.setForeground(cor);
        return l;
    }

    /** Ponto colorido + texto, para estado (fase do sistema, conexão com banco). */
    public static JLabel indicador(String texto, Color cor) {
        JLabel l = rotulo(texto, Fonte.CORPO_NEGRITO, cor);
        l.setIcon(new Ponto(cor));
        l.setIconTextGap(Medida.ESP_2);
        return l;
    }

    public static void atualizarIndicador(JLabel indicador, String texto, Color cor) {
        indicador.setText(texto);
        indicador.setForeground(cor);
        indicador.setIcon(new Ponto(cor));
    }

    private record Ponto(Color cor) implements Icon {
        public int getIconWidth() {
            return 10;
        }

        public int getIconHeight() {
            return 10;
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Tema.translucida(cor, 60));
            g2.fillOval(x, y, 10, 10);
            g2.setColor(cor);
            g2.fillOval(x + 2, y + 2, 6, 6);
            g2.dispose();
        }
    }

    // ================================================================== badge de severidade

    /** Etiqueta com fundo translúcido da cor da severidade (texto sempre presente). */
    public static JLabel badge(Severidade severidade) {
        String texto = severidade == null ? "NORMAL" : severidade.name();
        Color cor = Tema.corDa(severidade);
        JLabel l = new JLabel(texto, SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Tema.translucida(cor, 40));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setFont(Fonte.ROTULO_NEGRITO);
        l.setForeground(cor);
        l.setBorder(BorderFactory.createEmptyBorder(2, Medida.ESP_2, 2, Medida.ESP_2));
        return l;
    }

    // ================================================================== tabela

    /** Aplica o visual padrão: linhas altas, sem grade vertical, cabeçalho discreto, rolagem temada. */
    public static JScrollPane tabela(JTable t) {
        t.setRowHeight(Medida.ALTURA_LINHA_TABELA);
        t.setShowVerticalLines(false);
        t.setShowHorizontalLines(true);
        t.setGridColor(Cor.BORDA);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setFillsViewportHeight(true);
        t.setBackground(Cor.SUPERFICIE);
        t.setForeground(Cor.TEXTO);
        t.setFont(Fonte.DADO);
        t.setSelectionBackground(Tema.translucida(Cor.PRIMARIA, 60));
        t.setSelectionForeground(Cor.TEXTO);
        t.setDefaultRenderer(Object.class, new CelulaPadrao());

        JTableHeader h = t.getTableHeader();
        h.setReorderingAllowed(false);
        h.setDefaultRenderer(new CabecalhoPadrao());
        h.setPreferredSize(new Dimension(0, Medida.ALTURA_LINHA_TABELA));

        JScrollPane sp = rolagem(t);
        sp.getViewport().setOpaque(true);
        sp.getViewport().setBackground(Cor.SUPERFICIE);
        return sp;
    }

    /** Célula padrão com margem lateral. */
    public static class CelulaPadrao extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int l, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, l, c);
            setBorder(BorderFactory.createEmptyBorder(0, Medida.ESP_3, 0, Medida.ESP_3));
            setFont(Fonte.DADO);
            setForeground(Cor.TEXTO);
            setBackground(sel ? t.getSelectionBackground() : Cor.SUPERFICIE);
            return this;
        }
    }

    /** Célula de severidade/estado: texto colorido com ponto (nunca só cor). */
    public static class CelulaSeveridade extends CelulaPadrao {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int l, int c) {
            super.getTableCellRendererComponent(t, v, sel, foco, l, c);
            Severidade s = v instanceof Severidade sev ? sev : null;
            Color cor = Tema.corDa(s);
            setText(s == null ? "OK" : s.name());
            setForeground(cor);
            setFont(Fonte.DADO_NEGRITO);
            setIcon(new Ponto(cor));
            setIconTextGap(Medida.ESP_2);
            return this;
        }
    }

    private static final class CabecalhoPadrao extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco, int l, int c) {
            super.getTableCellRendererComponent(t, v, false, false, l, c);
            setText(v == null ? "" : v.toString().toUpperCase());
            setFont(Fonte.ROTULO_NEGRITO);
            setForeground(Cor.TEXTO_SECUNDARIO);
            setBackground(Cor.SUPERFICIE_ELEVADA);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Cor.BORDA),
                    BorderFactory.createEmptyBorder(0, Medida.ESP_3, 0, Medida.ESP_3)));
            return this;
        }
    }

    // ================================================================== rolagem

    /** Área de rolagem temada: sem moldura, barra fina, sem setas, rolagem horizontal desligada. */
    public static JScrollPane rolagem(java.awt.Component conteudo) {
        JScrollPane sp = new JScrollPane(conteudo);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUI(new BarraRolagemUI());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(Medida.ESP_2 + 2, 0));
        sp.getVerticalScrollBar().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(Medida.ESP_4);
        return sp;
    }

    /**
     * Painel que ACOMPANHA a largura da área de rolagem (só rola na vertical).
     * Use como conteúdo de {@link #rolagem} para listas e grades — sem ele, o conteúdo
     * fica na largura preferida e é cortado à direita.
     */
    public static class PainelRolavel extends JPanel implements javax.swing.Scrollable {
        private static final long serialVersionUID = 1L;

        @SuppressWarnings("this-escape")
        public PainelRolavel(java.awt.LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return Medida.ESP_4;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return r.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class BarraRolagemUI extends javax.swing.plaf.basic.BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = Cor.BORDA;
            trackColor = Cor.SUPERFICIE;
        }

        @Override
        protected JButton createDecreaseButton(int o) {
            return semSeta();
        }

        @Override
        protected JButton createIncreaseButton(int o) {
            return semSeta();
        }

        private static JButton semSeta() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            return b;
        }

        @Override
        protected Dimension getMinimumThumbSize() {
            return new Dimension(Medida.ESP_2, Medida.ESP_6 + Medida.ESP_2);
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, java.awt.Rectangle r) {
            // trilho invisível: só o "polegar" aparece
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, java.awt.Rectangle r) {
            if (r.isEmpty()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isThumbRollover() ? Cor.TEXTO_DESATIVADO : Tema.translucida(Cor.TEXTO_DESATIVADO, 120));
            g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, r.width - 4, r.width - 4);
            g2.dispose();
        }
    }

    // ================================================================== campos

    /** Combo temado: fundo elevado, contorno arredondado, seta em chevron, lista temada. */
    public static <T> javax.swing.JComboBox<T> combo(javax.swing.JComboBox<T> c) {
        c.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override
            protected JButton createArrowButton() {
                return botaoChevron(true);
            }

            @Override
            public void paintCurrentValueBackground(Graphics g, java.awt.Rectangle b, boolean foco) {
                g.setColor(Cor.SUPERFICIE_ELEVADA);
                g.fillRect(b.x, b.y, b.width, b.height);
            }
        });
        c.setFont(Fonte.CORPO);
        c.setForeground(Cor.TEXTO);
        c.setBackground(Cor.SUPERFICIE_ELEVADA);
        c.setBorder(new BordaArredondada(Cor.BORDA, 2));
        c.setOpaque(false);
        c.setRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> l, Object v, int i, boolean sel, boolean f) {
                super.getListCellRendererComponent(l, v, i, sel, f);
                setText(v instanceof domain.TipoSensor t ? t.nome() : String.valueOf(v));
                setBorder(margem(Medida.ESP_1, Medida.ESP_2, Medida.ESP_1, Medida.ESP_2));
                setBackground(sel && i >= 0 ? Tema.translucida(Cor.PRIMARIA, 70) : Cor.SUPERFICIE_ELEVADA);
                setForeground(Cor.TEXTO);
                return this;
            }
        });
        if (c.isEditable() && c.getEditor().getEditorComponent() instanceof JComponent ed) {
            ed.setBorder(margem(0, Medida.ESP_2, 0, 0));
            ed.setBackground(Cor.SUPERFICIE_ELEVADA);
            ed.setForeground(Cor.TEXTO);
            ed.setFont(Fonte.CORPO);
            if (ed instanceof javax.swing.text.JTextComponent tc) {
                tc.setCaretColor(Cor.PRIMARIA);
            }
        }
        c.setPreferredSize(new Dimension(Math.max(c.getPreferredSize().width + Medida.ESP_4, 120), Medida.ALTURA_BOTAO));
        return c;
    }

    /** Spinner numérico temado: número em fonte de dados, setas em chevron. */
    public static javax.swing.JSpinner spinner(javax.swing.JSpinner s) {
        s.setUI(new javax.swing.plaf.basic.BasicSpinnerUI() {
            @Override
            protected Component createNextButton() {
                JButton b = botaoChevron(false);
                installNextButtonListeners(b);
                return b;
            }

            @Override
            protected Component createPreviousButton() {
                JButton b = botaoChevron(true);
                installPreviousButtonListeners(b);
                return b;
            }
        });
        s.setBorder(new BordaArredondada(Cor.BORDA, 2));
        s.setOpaque(false);
        if (s.getEditor() instanceof javax.swing.JSpinner.DefaultEditor ed) {
            var tf = ed.getTextField();
            tf.setFont(Fonte.DADO_NEGRITO);
            tf.setForeground(Cor.TEXTO);
            tf.setBackground(Cor.SUPERFICIE_ELEVADA);
            tf.setCaretColor(Cor.PRIMARIA);
            tf.setBorder(margem(0, Medida.ESP_2, 0, Medida.ESP_1));
            tf.setDisabledTextColor(Cor.TEXTO_DESATIVADO);
        }
        s.setPreferredSize(new Dimension(104, Medida.ALTURA_BOTAO));
        return s;
    }

    /** Botão de seta em chevron (para combo e spinner). */
    private static JButton botaoChevron(boolean paraBaixo) {
        JButton b = new JButton() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isRollover() ? Cor.BORDA : Cor.SUPERFICIE_ELEVADA);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(isEnabled() ? Cor.TEXTO_SECUNDARIO : Cor.TEXTO_DESATIVADO);
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                int t = Math.min(4, Math.max(2, getHeight() / 5));
                if (paraBaixo) {
                    g2.drawPolyline(new int[]{cx - t, cx, cx + t}, new int[]{cy - t / 2, cy + t / 2, cy - t / 2}, 3);
                } else {
                    g2.drawPolyline(new int[]{cx - t, cx, cx + t}, new int[]{cy + t / 2, cy - t / 2, cy + t / 2}, 3);
                }
                g2.dispose();
            }
        };
        b.setBorder(BorderFactory.createEmptyBorder());
        b.setContentAreaFilled(false);
        b.setFocusable(false);
        b.setRolloverEnabled(true);
        b.setPreferredSize(new Dimension(24, 14));
        return b;
    }

    // ================================================================== diálogo de erro

    /** Monta o conteúdo do diálogo de erro (separado para poder ser pré-visualizado/testado). */
    public static JPanel painelErro(MensagemErro m) {
        JPanel p = transparente(new BorderLayout(Medida.ESP_4, Medida.ESP_3));
        p.setBorder(BorderFactory.createEmptyBorder(Medida.ESP_6, Medida.ESP_6, Medida.ESP_4, Medida.ESP_6));
        p.setOpaque(true);
        p.setBackground(Cor.SUPERFICIE);

        JLabel icone = new JLabel(Icones.de(Icone.ERRO, Medida.ICONE_GRANDE, Cor.PERIGO));
        icone.setVerticalAlignment(SwingConstants.TOP);
        p.add(icone, BorderLayout.WEST);

        JPanel textos = transparente(null);
        textos.setLayout(new BoxLayout(textos, BoxLayout.Y_AXIS));
        textos.add(rotulo(m.titulo(), Fonte.SECAO, Cor.TEXTO));
        textos.add(Box.createVerticalStrut(Medida.ESP_3));
        textos.add(rotulo("O QUE ACONTECEU", Fonte.ROTULO_NEGRITO, Cor.TEXTO_SECUNDARIO));
        textos.add(paragrafo(m.oQueAconteceu()));
        textos.add(Box.createVerticalStrut(Medida.ESP_3));
        textos.add(rotulo("O QUE FAZER", Fonte.ROTULO_NEGRITO, Cor.PRIMARIA));
        textos.add(paragrafo(m.oQueFazer()));
        if (!m.detalheTecnico().isEmpty()) {
            textos.add(Box.createVerticalStrut(Medida.ESP_3));
            JTextArea det = new JTextArea(m.detalheTecnico());
            det.setFont(Fonte.DADO);
            det.setForeground(Cor.TEXTO_SECUNDARIO);
            det.setBackground(Cor.FUNDO);
            det.setEditable(false);
            det.setLineWrap(true);
            det.setBorder(BorderFactory.createEmptyBorder(Medida.ESP_2, Medida.ESP_2, Medida.ESP_2, Medida.ESP_2));
            det.setVisible(false);
            JButton ver = botao("Ver detalhes técnicos", null, Estilo.FANTASMA);
            ver.addActionListener(e -> {
                det.setVisible(!det.isVisible());
                ver.setText(det.isVisible() ? "Ocultar detalhes técnicos" : "Ver detalhes técnicos");
                Window w = javax.swing.SwingUtilities.getWindowAncestor(ver);
                if (w != null) {
                    w.pack();
                }
            });
            ver.setAlignmentX(Component.LEFT_ALIGNMENT);
            det.setAlignmentX(Component.LEFT_ALIGNMENT);
            textos.add(ver);
            textos.add(det);
        }
        for (Component c : textos.getComponents()) {
            ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        p.add(textos, BorderLayout.CENTER);
        return p;
    }

    /** Mostra o diálogo modal de erro. Chamar na EDT. */
    public static void mostrarErro(Component pai, MensagemErro m) {
        Window dono = pai == null ? null : javax.swing.SwingUtilities.getWindowAncestor(pai);
        JDialog d = new JDialog(dono, m.titulo(), java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JPanel conteudo = painelErro(m);
        JButton ok = botao("Entendi", null, Estilo.PRIMARIO);
        ok.addActionListener(e -> d.dispose());
        JPanel rodape = transparente(new FlowLayout(FlowLayout.RIGHT));
        rodape.add(ok);
        conteudo.add(rodape, BorderLayout.SOUTH);
        d.setContentPane(conteudo);
        d.getRootPane().setDefaultButton(ok);
        d.setMinimumSize(new Dimension(420, 0));
        d.pack();
        ok.requestFocusInWindow();
        d.setLocationRelativeTo(pai);
        d.setVisible(true);
    }

    /**
     * Parágrafo com quebra de linha e altura correta já no pack().
     * (JTextArea com lineWrap informa 1 linha antes de ter largura e corta o que vem abaixo.)
     */
    public static JLabel paragrafo(String texto) {
        String html = texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        JLabel l = new JLabel("<html><div style='width:320px'>" + html + "</div></html>");
        l.setFont(Fonte.CORPO);
        l.setForeground(Cor.TEXTO);
        l.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        return l;
    }

    // ================================================================== bordas

    /** Contorno arredondado com margem interna. */
    public static final class BordaArredondada extends AbstractBorder {
        private final Color cor;
        private final int margem;

        public BordaArredondada(Color cor, int margem) {
            this.cor = cor;
            this.margem = margem;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(cor);
            g2.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1, Medida.RAIO * 2, Medida.RAIO * 2));
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(margem, margem, margem, margem);
        }
    }

    /** Borda vazia na grade de 4 px. */
    public static Border margem(int topo, int esq, int base, int dir) {
        return BorderFactory.createEmptyBorder(topo, esq, base, dir);
    }
}
