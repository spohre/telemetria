package app.ui.telas;

import app.FormatoMensagens;
import app.RegraExibida;
import app.ui.tema.Componentes;
import app.ui.tema.Icones;
import app.ui.tema.Icones.Icone;
import app.ui.tema.Tema;
import domain.LeituraTelemetria;
import domain.Severidade;
import domain.TipoSensor;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.time.Instant;

import static app.ui.tema.Tema.Cor;
import static app.ui.tema.Tema.Fonte;
import static app.ui.tema.Tema.Medida;

/**
 * Linha de UM sensor na aba Monitoramento: à esquerda as informações do antigo cartão
 * (estado, valor, faixa, última leitura) e, à direita, o gráfico do histórico.
 *
 * <pre>
 * ┃ [ícone] TEMP-01   ● ALTA  [⤢]      │ [legenda · janela: Tudo 5 min 1 min 30 s] (só expandido)
 * ┃ 92,5 °C                            │  80 - - - - - - - - - - - - - - - - - máx
 * ┃ ▓▓▓▓▓▓▓▓░░  faixa 0,0 – 80,0 °C    │  40 ╱╲╱╲__╱╲  ●
 * ┃ Temperatura · última 19:46:02      │   0 - - - - - - - - - - - - - - - - - mín
 * ┃ mín · máx · média   (só expandido) │     13:21:10    13:21:20    13:21:30
 * ┃ 1.204 leituras · 9 alertas         │
 * </pre>
 * Compacta: {@link Medida#ALTURA_SENSOR}; expandida (botão ou duplo clique no gráfico):
 * a altura visível da lista, entre {@link Medida#ALTURA_SENSOR_EXPANDIDO} e
 * {@link Medida#ALTURA_SENSOR_EXPANDIDO_MAX}. Só exibe — não decide nada. Todos os métodos: EDT.
 */
public final class CartaoSensor extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Object[][] JANELAS = {{"Tudo", 0L}, {"5 min", 300_000L}, {"1 min", 60_000L}, {"30 s", 30_000L}};

    private final TipoSensor tipo;
    private final RegraExibida regra;
    private final GraficoSensor grafico;
    private final JLabel valor = Componentes.rotulo("—", Fonte.VALOR, Cor.TEXTO);
    private final JLabel rodape;
    private final JLabel resumo = Componentes.rotulo(" ", Fonte.DADO, Cor.TEXTO_SECUNDARIO);
    private final JLabel contagem = Componentes.rotulo(" ", Fonte.ROTULO, Cor.TEXTO_SECUNDARIO);
    private final JPanel areaBadge = Componentes.transparente(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    private final BarraFaixa barra = new BarraFaixa();
    private final JButton expandir = Componentes.botaoIconePequeno(Icone.EXPANDIR, "Expandir o gráfico");
    private final JPanel barraDetalhe;
    private final ButtonGroup grupoJanela = new ButtonGroup();
    private Color corEstado = Cor.TEXTO_DESATIVADO;
    private boolean expandido;

    public CartaoSensor(String sensorId, TipoSensor tipo, RegraExibida regra) {
        this.tipo = tipo;
        this.regra = regra;
        this.grafico = new GraficoSensor(regra, () -> definirExpandido(!expandido));
        grafico.setToolTipText(null);
        setOpaque(false);
        setLayout(new BorderLayout(Medida.ESP_4, 0));
        setBorder(Componentes.margem(Medida.ESP_3, Medida.ESP_4 + Medida.ESP_1, Medida.ESP_3, Medida.ESP_4));

        // ---- esquerda: (barra de detalhe, só expandido) + gráfico
        barraDetalhe = criarBarraDetalhe();
        barraDetalhe.setVisible(false);
        JPanel esquerda = Componentes.transparente(new BorderLayout(0, Medida.ESP_2));
        esquerda.add(barraDetalhe, BorderLayout.NORTH);
        esquerda.add(grafico, BorderLayout.CENTER);

        // ---- direita: as informações do cartão
        JPanel info = Componentes.transparente(null);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Cor.BORDA),
                Componentes.margem(0, 0, 0, Medida.ESP_4)));

        JPanel topo = Componentes.transparente(new BorderLayout(Medida.ESP_2, 0));
        JLabel id = Componentes.rotulo(sensorId, Fonte.CORPO_NEGRITO, Cor.TEXTO);
        id.setIcon(Icones.de(Icones.doTipo(tipo), Medida.ICONE, Cor.TEXTO_SECUNDARIO));
        id.setIconTextGap(Medida.ESP_2);
        topo.add(id, BorderLayout.WEST);
        areaBadge.add(Componentes.badge(null));
        areaBadge.setVisible(false);
        JPanel direitaTopo = Componentes.transparente(new FlowLayout(FlowLayout.RIGHT, Medida.ESP_1, 0));
        direitaTopo.add(areaBadge);
        direitaTopo.add(expandir);
        topo.add(direitaTopo, BorderLayout.EAST);
        expandir.addActionListener(e -> definirExpandido(!expandido));

        valor.setHorizontalAlignment(SwingConstants.LEFT);
        JPanel linhaFaixa = Componentes.transparente(new BorderLayout(Medida.ESP_2, 0));
        linhaFaixa.add(barra, BorderLayout.CENTER);
        linhaFaixa.add(Componentes.rotulo("faixa " + FormatoMensagens.decimal(regra.minimo()) + " – "
                + FormatoMensagens.valorComUnidade(regra.maximo(), tipo)), BorderLayout.EAST);
        rodape = Componentes.rotulo(tipo.nome() + " · aguardando leitura");

        adicionarLinha(info, topo, 0);
        adicionarLinha(info, valor, 0);
        adicionarLinha(info, linhaFaixa, Medida.ESP_2);
        adicionarLinha(info, rodape, Medida.ESP_1);
        info.add(Box.createVerticalGlue());
        adicionarLinha(info, resumo, 0);
        adicionarLinha(info, contagem, Medida.ESP_1);
        resumo.setVisible(false);          // compacto (100 px) não tem altura para o resumo:
        contagem.setVisible(false);        // ele aparece só com o gráfico expandido
        Dimension largura = new Dimension(Medida.LARGURA_INFO_SENSOR, 0);
        info.setPreferredSize(largura);
        info.setMinimumSize(largura);

        add(info, BorderLayout.WEST);          // informações à esquerda, gráfico ocupa o resto
        add(esquerda, BorderLayout.CENTER);
        atualizarAltura();
    }

    private static void adicionarLinha(JPanel destino, JComponent linha, int espacoAntes) {
        if (espacoAntes > 0) {
            destino.add(Box.createVerticalStrut(espacoAntes));
        }
        linha.setAlignmentX(Component.LEFT_ALIGNMENT);
        linha.setMaximumSize(new Dimension(Integer.MAX_VALUE, linha.getPreferredSize().height));
        destino.add(linha);
    }

    /** Legenda + escolha da janela de tempo — aparece só com o gráfico expandido. */
    private JPanel criarBarraDetalhe() {
        JPanel barraD = Componentes.transparente(new BorderLayout(Medida.ESP_4, 0));
        JPanel legenda = Componentes.transparente(new FlowLayout(FlowLayout.LEFT, Medida.ESP_3, 0));
        legenda.add(Componentes.rotulo("— leitura", Fonte.ROTULO, Cor.GRAFICO_LINHA));
        legenda.add(Componentes.rotulo("- - - limite antes do alerta", Fonte.ROTULO, Cor.GRAFICO_LIMITE));
        legenda.add(Componentes.rotulo("● alerta", Fonte.ROTULO, Cor.SEV_ALTA));
        legenda.setToolTipText("Passe o mouse sobre o gráfico para ver cada leitura; duplo clique recolhe");
        barraD.add(legenda, BorderLayout.WEST);
        JPanel janelas = Componentes.transparente(new FlowLayout(FlowLayout.RIGHT, Medida.ESP_1, 0));
        janelas.add(Componentes.rotulo("Janela"));
        for (Object[] j : JANELAS) {
            JToggleButton b = chip((String) j[0]);
            long ms = (Long) j[1];
            b.addActionListener(e -> grafico.definirJanela(ms));
            grupoJanela.add(b);
            janelas.add(b);
        }
        ((AbstractButton) janelas.getComponent(1)).setSelected(true);
        barraD.add(janelas, BorderLayout.EAST);
        return barraD;
    }

    /** Botão pequeno de alternância para a janela de tempo (selecionado = PRIMARIA). */
    private static JToggleButton chip(String texto) {
        JToggleButton b = new JToggleButton(texto) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                var forma = new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1,
                        getHeight(), getHeight());
                if (isSelected()) {
                    g2.setColor(Tema.translucida(Cor.PRIMARIA, 28));
                    g2.fill(forma);
                    g2.setColor(Cor.PRIMARIA);
                } else {
                    g2.setColor(getModel().isRollover() ? Cor.SUPERFICIE_ELEVADA : Cor.SUPERFICIE);
                    g2.fill(forma);
                    g2.setColor(Cor.BORDA);
                }
                g2.draw(forma);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setFont(Fonte.ROTULO_NEGRITO);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        b.setBorder(Componentes.margem(Medida.ESP_1, Medida.ESP_3, Medida.ESP_1, Medida.ESP_3));
        Runnable cor = () -> b.setForeground(b.isSelected() ? Cor.PRIMARIA : Cor.TEXTO_SECUNDARIO);
        b.getModel().addChangeListener(e -> cor.run());
        cor.run();
        return b;
    }

    // ================================================================== estado

    /** Atualiza com uma leitura nova (ou do histórico) e acrescenta o ponto ao gráfico. EDT. */
    public void atualizar(LeituraTelemetria leitura, Severidade severidadeOuNull) {
        grafico.adicionar(leitura.momento(), leitura.valor(), severidadeOuNull);
        mostrarUltima(leitura, severidadeOuNull);
    }

    /** Acrescenta ao gráfico sem redesenhar o resto (carga do histórico); chame {@link #mostrarUltima} no fim. */
    void acrescentarAoGrafico(LeituraTelemetria leitura, Severidade severidadeOuNull) {
        grafico.adicionar(leitura.momento(), leitura.valor(), severidadeOuNull);
    }

    void mostrarUltima(LeituraTelemetria leitura, Severidade severidadeOuNull) {
        valor.setText(FormatoMensagens.valorComUnidade(leitura.valor(), tipo));
        corEstado = Tema.corDa(severidadeOuNull);
        valor.setForeground(severidadeOuNull == null ? Cor.TEXTO : corEstado);
        areaBadge.removeAll();
        areaBadge.add(Componentes.badge(severidadeOuNull));
        areaBadge.setVisible(true);
        barra.definir(leitura.valor(), corEstado);
        rodape.setText(tipo.nome() + " · última " + FormatoMensagens.hora(leitura.momento()));
        resumo.setText("mín " + FormatoMensagens.decimal(grafico.minimo())
                + " · máx " + FormatoMensagens.decimal(grafico.maximo())
                + " · média " + FormatoMensagens.decimal(grafico.media()));
        contagem.setText(FormatoMensagens.inteiro(grafico.total()) + " leituras · "
                + FormatoMensagens.inteiro(grafico.alertas()) + (grafico.alertas() == 1 ? " alerta" : " alertas"));
        revalidate();
        repaint();
    }

    public boolean expandido() {
        return expandido;
    }

    public void definirExpandido(boolean sim) {
        if (sim == expandido) {
            return;
        }
        expandido = sim;
        grafico.definirExpandido(sim);
        barraDetalhe.setVisible(sim);
        resumo.setVisible(sim);
        contagem.setVisible(sim);
        if (!sim) {
            ((AbstractButton) ((JPanel) barraDetalhe.getComponent(1)).getComponent(1)).setSelected(true);
        }
        expandir.setIcon(Icones.queSegueOTexto(sim ? Icone.RECOLHER : Icone.EXPANDIR, Medida.ICONE_PEQ));
        expandir.setToolTipText(sim ? "Recolher o gráfico" : "Expandir o gráfico");
        atualizarAltura();
        revalidate();
        repaint();
        if (sim) {
            javax.swing.SwingUtilities.invokeLater(() -> scrollRectToVisible(new java.awt.Rectangle(getSize())));
        }
    }

    public Instant primeiraLeitura() {
        return grafico.primeiro();
    }

    public Instant ultimaLeitura() {
        return grafico.ultimo();
    }

    /** Linha expandida acompanha a altura visível da lista (chamado quando ela muda de tamanho). */
    public void ajustarAoEspacoVisivel() {
        if (expandido) {
            atualizarAltura();
            revalidate();
        }
    }

    private int alturaExpandida() {
        var vp = (javax.swing.JViewport) javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JViewport.class, this);
        int visivel = vp == null ? 0 : vp.getExtentSize().height;
        return Math.max(Medida.ALTURA_SENSOR_EXPANDIDO, Math.min(visivel, Medida.ALTURA_SENSOR_EXPANDIDO_MAX));
    }

    private void atualizarAltura() {
        int h = expandido ? alturaExpandida() : Medida.ALTURA_SENSOR;
        setPreferredSize(new Dimension(Medida.LARGURA_INFO_SENSOR * 2, h));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        setMinimumSize(new Dimension(Medida.LARGURA_INFO_SENSOR * 2, h));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        var forma = new RoundRectangle2D.Double(0, 0, getWidth() - 1, getHeight() - 1, Medida.RAIO * 2, Medida.RAIO * 2);
        g2.setColor(Cor.SUPERFICIE);
        g2.fill(forma);
        g2.setColor(Cor.BORDA);
        g2.draw(forma);
        g2.clip(forma);
        g2.setColor(corEstado);
        g2.fillRect(0, 0, Medida.ESP_1, getHeight());
        g2.dispose();
    }

    /** Barra horizontal: posição do valor dentro da faixa aceita (passa de 100% se violar). */
    private final class BarraFaixa extends JComponent {
        private static final long serialVersionUID = 1L;
        private double valorAtual = Double.NaN;
        private Color cor = Cor.TEXTO_DESATIVADO;

        BarraFaixa() {
            setPreferredSize(new Dimension(80, 6));
        }

        void definir(double v, Color c) {
            valorAtual = v;
            cor = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = 6;
            int y = (getHeight() - h) / 2;
            g2.setColor(Cor.SUPERFICIE_ELEVADA);
            g2.fillRoundRect(0, y, getWidth(), h, h, h);
            if (!Double.isNaN(valorAtual)) {
                double amplitude = regra.maximo() - regra.minimo();
                double fracao = amplitude <= 0 ? 1 : (valorAtual - regra.minimo()) / amplitude;
                int w = (int) Math.round(Math.max(0.04, Math.min(1, fracao)) * getWidth());
                g2.setColor(cor);
                g2.fillRoundRect(0, y, w, h, h, h);
            }
            g2.dispose();
        }
    }
}
