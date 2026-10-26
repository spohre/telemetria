package app.ui.telas;

import app.FormatoMensagens;
import app.RegraExibida;
import app.ui.tema.Componentes;
import app.ui.tema.Icones;
import app.ui.tema.Tema;
import domain.LeituraTelemetria;
import domain.Severidade;
import domain.TipoSensor;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

import static app.ui.tema.Tema.Cor;
import static app.ui.tema.Tema.Fonte;
import static app.ui.tema.Tema.Medida;

/**
 * Cartão de um sensor na aba Monitoramento.
 *
 * <pre>
 * ┃ [ícone] TEMP-01                 ● ALTA
 * ┃ 92,5 °C
 * ┃ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░  faixa 0,0 – 80,0 °C
 * ┃ Temperatura · última 19:46:02
 * </pre>
 * Faixa lateral e badge mostram o estado (NORMAL ou severidade). Só exibe — não decide nada.
 */
public final class CartaoSensor extends JPanel {

    private static final long serialVersionUID = 1L;

    private final TipoSensor tipo;
    private final RegraExibida regra;
    private final JLabel valor = Componentes.rotulo("—", Fonte.VALOR, Cor.TEXTO);
    private final JLabel rodape;
    private final JPanel areaBadge = Componentes.transparente(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    private final BarraFaixa barra = new BarraFaixa();
    private Color corEstado = Cor.TEXTO_DESATIVADO;

    public CartaoSensor(String sensorId, TipoSensor tipo, RegraExibida regra) {
        this.tipo = tipo;
        this.regra = regra;
        setOpaque(false);
        setLayout(new BorderLayout(0, Medida.ESP_2));
        setBorder(Componentes.margem(Medida.ESP_3, Medida.ESP_4 + Medida.ESP_1, Medida.ESP_3, Medida.ESP_4));

        JPanel topo = Componentes.transparente(new BorderLayout());
        JLabel id = Componentes.rotulo(sensorId, Fonte.CORPO_NEGRITO, Cor.TEXTO);
        id.setIcon(Icones.de(Icones.doTipo(tipo), Medida.ICONE, Cor.TEXTO_SECUNDARIO));
        id.setIconTextGap(Medida.ESP_2);
        topo.add(id, BorderLayout.WEST);
        areaBadge.add(Componentes.badge(null));
        areaBadge.setVisible(false);
        topo.add(areaBadge, BorderLayout.EAST);

        JPanel meio = Componentes.transparente(new BorderLayout(0, Medida.ESP_2));
        valor.setHorizontalAlignment(SwingConstants.LEFT);
        meio.add(valor, BorderLayout.NORTH);
        JPanel linhaFaixa = Componentes.transparente(new BorderLayout(Medida.ESP_2, 0));
        linhaFaixa.add(barra, BorderLayout.CENTER);
        linhaFaixa.add(Componentes.rotulo("faixa " + FormatoMensagens.decimal(regra.minimo()) + " – "
                + FormatoMensagens.valorComUnidade(regra.maximo(), tipo)), BorderLayout.EAST);
        meio.add(linhaFaixa, BorderLayout.CENTER);

        rodape = Componentes.rotulo(tipo.nome() + " · aguardando leitura");

        add(topo, BorderLayout.NORTH);
        add(meio, BorderLayout.CENTER);
        add(rodape, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(230, 128));
    }

    /** Atualiza com a última leitura. Chamar SOMENTE na EDT. */
    public void atualizar(LeituraTelemetria leitura, Severidade severidadeOuNull) {
        valor.setText(FormatoMensagens.valorComUnidade(leitura.valor(), tipo));
        corEstado = Tema.corDa(severidadeOuNull);
        valor.setForeground(severidadeOuNull == null ? Cor.TEXTO : corEstado);
        areaBadge.removeAll();
        JComponent b = Componentes.badge(severidadeOuNull);
        areaBadge.add(b);
        areaBadge.setVisible(true);
        barra.definir(leitura.valor(), corEstado);
        rodape.setText(tipo.nome() + " · última " + FormatoMensagens.hora(leitura.momento()));
        revalidate();
        repaint();
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
