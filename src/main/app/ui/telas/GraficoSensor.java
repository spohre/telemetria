package app.ui.telas;

import app.FormatoMensagens;
import app.RegraExibida;
import app.ui.tema.Tema;
import domain.Severidade;
import domain.TipoSensor;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.time.Instant;
import java.util.Arrays;

import static app.ui.tema.Tema.Cor;
import static app.ui.tema.Tema.Fonte;
import static app.ui.tema.Tema.Medida;

/**
 * Gráfico de linhas do histórico de UM sensor na execução (ao vivo ou a última encerrada).
 *
 * <pre>
 *  85 ┤░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ (faixa fora do aceito, sombreada)
 *  80 ┤- - - - - - - - - - - - - - - máx 80,0   ← limite: pontilhado
 *  60 ┤      ╱╲    ╱╲●  ╱╲                       ← leitura (● = gerou alerta)
 *  40 ┤  ╱╲╱    ╲╱    ╲╱  ╲╱╲
 *   0 ┤- - - - - - - - - - - - - - - mín 0,0
 *     └──────┬──────┬──────┬──────
 *         13:21:10 13:21:20 13:21:30
 * </pre>
 *
 * <p>Compacto: poucas marcações. Expandido: mais marcações de valor e de tempo, pontos em
 * cada leitura quando há espaço e escolha de janela de tempo. Passar o mouse mostra a
 * leitura mais próxima. Só exibe — limites vêm da {@link RegraExibida} do controller.</p>
 *
 * <p>Muitas leituras: cada coluna de pixel desenha o mínimo e o máximo do trecho, então
 * nenhum pico some do desenho. Acima de {@link Medida#MAX_PONTOS_GRAFICO} pontos guardados,
 * metade das leituras normais é descartada (alertas ficam sempre). Todos os métodos: EDT.</p>
 */
public final class GraficoSensor extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final int SEM_ALERTA = -1;
    private static final long[] PASSOS_TEMPO_MS = {1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000,
            300_000, 600_000, 900_000, 1_800_000, 3_600_000, 7_200_000};

    private final RegraExibida regra;
    private final TipoSensor tipo;

    // série: tempo (ms), valor e severidade (ordinal ou SEM_ALERTA), sempre em ordem de tempo
    private long[] tempos = new long[256];
    private double[] valores = new double[256];
    private byte[] severidades = new byte[256];
    private int n;

    // estatísticas de TODAS as leituras recebidas (não só as guardadas)
    private long total;
    private long alertas;
    private double minimo = Double.NaN;
    private double maximo = Double.NaN;
    private double soma;

    private boolean expandido;
    private long janelaMs;          // 0 = tudo
    private int mouseX = -1;

    public GraficoSensor(RegraExibida regra, Runnable aoDuploClique) {
        this.regra = regra;
        this.tipo = regra.tipo();
        setOpaque(false);
        setPreferredSize(new Dimension(Medida.LARGURA_INFO_SENSOR, Medida.ALTURA_SENSOR));
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                mouseX = e.getX();
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                mouseX = -1;
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && aoDuploClique != null) {
                    aoDuploClique.run();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        setToolTipText(null);
    }

    // ================================================================== dados

    /** Acrescenta uma leitura (mantém a ordem de tempo, mesmo que chegue um pouco atrasada). */
    public void adicionar(Instant momento, double valor, Severidade severidadeOuNull) {
        total++;
        soma += valor;
        minimo = Double.isNaN(minimo) ? valor : Math.min(minimo, valor);
        maximo = Double.isNaN(maximo) ? valor : Math.max(maximo, valor);
        if (severidadeOuNull != null) {
            alertas++;
        }
        if (n == Medida.MAX_PONTOS_GRAFICO) {
            reduzirResolucao();
        }
        garantirCapacidade(n + 1);
        long t = momento.toEpochMilli();
        int pos = n;
        while (pos > 0 && tempos[pos - 1] > t) {      // vários consumidores: pode chegar fora de ordem
            pos--;
        }
        if (pos < n) {
            System.arraycopy(tempos, pos, tempos, pos + 1, n - pos);
            System.arraycopy(valores, pos, valores, pos + 1, n - pos);
            System.arraycopy(severidades, pos, severidades, pos + 1, n - pos);
        }
        tempos[pos] = t;
        valores[pos] = valor;
        severidades[pos] = (byte) (severidadeOuNull == null ? SEM_ALERTA : severidadeOuNull.ordinal());
        n++;
        repaint();
    }

    public void limpar() {
        n = 0;
        total = 0;
        alertas = 0;
        soma = 0;
        minimo = Double.NaN;
        maximo = Double.NaN;
        repaint();
    }

    public long total() {
        return total;
    }

    public long alertas() {
        return alertas;
    }

    /** Menor valor lido na execução ({@code NaN} sem leituras). */
    public double minimo() {
        return minimo;
    }

    public double maximo() {
        return maximo;
    }

    public double media() {
        return total == 0 ? Double.NaN : soma / total;
    }

    /** Momento da primeira leitura guardada, ou {@code null}. */
    public Instant primeiro() {
        return n == 0 ? null : Instant.ofEpochMilli(tempos[0]);
    }

    public Instant ultimo() {
        return n == 0 ? null : Instant.ofEpochMilli(tempos[n - 1]);
    }

    public void definirExpandido(boolean sim) {
        expandido = sim;
        if (!sim) {
            janelaMs = 0;
        }
        repaint();
    }

    /** Janela de tempo visível em ms (0 = a execução inteira). */
    public void definirJanela(long ms) {
        janelaMs = Math.max(0, ms);
        repaint();
    }

    private void garantirCapacidade(int minimoNecessario) {
        if (minimoNecessario > tempos.length) {
            int nova = Math.max(minimoNecessario, tempos.length * 2);
            tempos = Arrays.copyOf(tempos, nova);
            valores = Arrays.copyOf(valores, nova);
            severidades = Arrays.copyOf(severidades, nova);
        }
    }

    /** Descarta uma de cada duas leituras normais; leituras com alerta ficam todas. */
    private void reduzirResolucao() {
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (i % 2 == 0 || severidades[i] != SEM_ALERTA || i == n - 1) {
                tempos[j] = tempos[i];
                valores[j] = valores[i];
                severidades[j] = severidades[i];
                j++;
            }
        }
        n = j;
    }

    // ================================================================== desenho

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            desenhar(g2);
        } finally {
            g2.dispose();
        }
    }

    private void desenhar(Graphics2D g) {
        g.setFont(Fonte.EIXO);
        FontMetrics fm = g.getFontMetrics();

        // ---- intervalo de tempo visível
        long tFim = n == 0 ? System.currentTimeMillis() : tempos[n - 1];
        long tIni = n == 0 ? tFim - 60_000 : (janelaMs > 0 ? tFim - janelaMs : tempos[0]);
        if (tFim - tIni < 5_000) {
            tIni = tFim - 5_000;          // poucas leituras: não esticar 2 pontos na largura toda
        }
        int i0 = n == 0 ? 0 : Math.max(0, primeiroIndiceEmOuDepois(tIni) - 1);
        int i1 = n;                       // exclusivo

        // ---- intervalo de valores: faixa aceita + o que foi lido no trecho, com folga
        double lo = regra.minimo();
        double hi = regra.maximo();
        for (int i = i0; i < i1; i++) {
            lo = Math.min(lo, valores[i]);
            hi = Math.max(hi, valores[i]);
        }
        double folga = Math.max((hi - lo) * 0.08, 1e-6);
        lo -= folga;
        hi += folga;
        double passoV = passoBonito((hi - lo) / (expandido ? 8 : 3.5));

        // ---- área do gráfico (margem esquerda = rótulo de valor mais largo)
        int largRotulo = 0;
        for (double v = Math.ceil(lo / passoV) * passoV; v <= hi; v += passoV) {
            largRotulo = Math.max(largRotulo, fm.stringWidth(rotuloValor(v, passoV)));
        }
        Rectangle area = new Rectangle(largRotulo + Medida.ESP_2, Medida.ESP_2,
                getWidth() - largRotulo - Medida.ESP_2 - Medida.ESP_1,
                getHeight() - Medida.ESP_2 - fm.getHeight() - Medida.ESP_1);
        if (area.width < 40 || area.height < 30) {
            return;
        }
        Escala esc = new Escala(area, tIni, tFim, lo, hi);

        // ---- fora da faixa aceita: sombreado bem leve
        g.setColor(Tema.translucida(Cor.GRAFICO_LIMITE, 12));
        int yMax = (int) Math.round(esc.y(regra.maximo()));
        int yMin = (int) Math.round(esc.y(regra.minimo()));
        if (yMax > area.y) {
            g.fillRect(area.x, area.y, area.width, yMax - area.y);
        }
        if (yMin < area.y + area.height) {
            g.fillRect(area.x, yMin, area.width, area.y + area.height - yMin);
        }

        // ---- marcações de valor (grade horizontal + rótulos)
        g.setStroke(new BasicStroke(1f));
        for (double v = Math.ceil(lo / passoV) * passoV; v <= hi + 1e-9; v += passoV) {
            int y = (int) Math.round(esc.y(v));
            g.setColor(Cor.GRAFICO_GRADE);
            g.drawLine(area.x, y, area.x + area.width, y);
            g.setColor(Cor.TEXTO_SECUNDARIO);
            String r = rotuloValor(v, passoV);
            g.drawString(r, area.x - Medida.ESP_1 - fm.stringWidth(r), y + fm.getAscent() / 2 - 1);
        }

        // ---- marcações de tempo (grade vertical + rótulos)
        long passoT = passoTempo(tFim - tIni, Math.max(2, area.width / (expandido ? 110 : 150)));
        for (long t = ((tIni + passoT - 1) / passoT) * passoT; t <= tFim; t += passoT) {
            int x = (int) Math.round(esc.x(t));
            g.setColor(Cor.GRAFICO_GRADE);
            g.drawLine(x, area.y, x, area.y + area.height);
            String r = FormatoMensagens.hora(Instant.ofEpochMilli(t));
            int w = fm.stringWidth(r);
            int xr = Math.max(area.x, Math.min(x - w / 2, area.x + area.width - w));
            g.setColor(Cor.TEXTO_SECUNDARIO);
            g.drawString(r, xr, area.y + area.height + Medida.ESP_1 + fm.getAscent());
        }
        g.setColor(Cor.BORDA);
        g.drawLine(area.x, area.y + area.height, area.x + area.width, area.y + area.height);

        // ---- limites (pontilhado) com rótulo
        desenharLimite(g, fm, area, yMax, "máx " + FormatoMensagens.decimalCompacto(regra.maximo()), true);
        desenharLimite(g, fm, area, yMin, "mín " + FormatoMensagens.decimalCompacto(regra.minimo()), false);

        if (n == 0) {
            String msg = "Sem leituras nesta execução";
            g.setFont(Fonte.ROTULO);
            FontMetrics fr = g.getFontMetrics();
            g.setColor(Cor.TEXTO_SECUNDARIO);
            g.drawString(msg, area.x + (area.width - fr.stringWidth(msg)) / 2, area.y + area.height / 2);
            return;
        }

        // ---- série
        Shape antes = g.getClip();
        g.clipRect(area.x, area.y - 2, area.width + 1, area.height + 4);
        Path2D linha = caminho(esc, i0, i1);
        Path2D preenchimento = (Path2D) linha.clone();
        preenchimento.lineTo(linha.getCurrentPoint().getX(), area.y + area.height);
        preenchimento.lineTo(esc.x(tempos[i0]), area.y + area.height);
        preenchimento.closePath();
        g.setColor(Tema.translucida(Cor.GRAFICO_LINHA, 18));
        g.fill(preenchimento);
        g.setColor(Cor.GRAFICO_LINHA);
        g.setStroke(new BasicStroke(Medida.TRACO_GRAFICO, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(linha);

        // ---- pontos: alertas sempre; todas as leituras quando expandido e há espaço
        boolean todos = expandido && (i1 - i0) <= area.width / 6;
        double raio = expandido ? 3.5 : 2.5;
        int ultimoX = Integer.MIN_VALUE;
        for (int i = i0; i < i1; i++) {
            boolean alerta = severidades[i] != SEM_ALERTA;
            if (!alerta && !todos) {
                continue;
            }
            double x = esc.x(tempos[i]);
            if (!todos && (int) x == ultimoX) {
                continue;                 // vários alertas no mesmo pixel: um ponto basta
            }
            ultimoX = (int) x;
            double y = esc.y(valores[i]);
            Color cor = alerta ? Tema.corDa(Severidade.values()[severidades[i]]) : Cor.GRAFICO_LINHA;
            g.setColor(Cor.SUPERFICIE);
            g.fill(new Ellipse2D.Double(x - raio - 1, y - raio - 1, (raio + 1) * 2, (raio + 1) * 2));
            g.setColor(cor);
            g.fill(new Ellipse2D.Double(x - raio, y - raio, raio * 2, raio * 2));
        }
        g.setClip(antes);

        // ---- leitura sob o mouse
        if (mouseX >= area.x && mouseX <= area.x + area.width) {
            desenharLeituraSobMouse(g, esc, area, i0, i1);
        }
    }

    private void desenharLimite(Graphics2D g, FontMetrics fm, Rectangle area, int y, String texto, boolean acima) {
        g.setColor(Tema.translucida(Cor.GRAFICO_LIMITE, 190));
        g.setStroke(new BasicStroke(Medida.TRACO_LIMITE, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                Medida.TRACEJADO_LIMITE, 0f));
        g.draw(new Line2D.Double(area.x, y + 0.5, area.x + area.width, y + 0.5));
        g.setStroke(new BasicStroke(1f));
        int w = fm.stringWidth(texto) + Medida.ESP_2;
        int h = fm.getHeight();
        int x = area.x + area.width - w - Medida.ESP_1;
        int yr = acima ? y - h - 1 : y + 2;
        yr = Math.max(area.y, Math.min(yr, area.y + area.height - h));
        g.setColor(Tema.translucida(Cor.SUPERFICIE, 225));
        g.fill(new RoundRectangle2D.Double(x, yr, w, h, Medida.ESP_1, Medida.ESP_1));
        g.setColor(Cor.GRAFICO_LIMITE);
        g.drawString(texto, x + Medida.ESP_1, yr + fm.getAscent());
    }

    private void desenharLeituraSobMouse(Graphics2D g, Escala esc, Rectangle area, int i0, int i1) {
        long t = esc.tempoEm(mouseX);
        int i = Math.max(i0, Math.min(i1 - 1, primeiroIndiceEmOuDepois(t)));
        if (i > i0 && Math.abs(tempos[i - 1] - t) < Math.abs(tempos[i] - t)) {
            i--;
        }
        double x = esc.x(tempos[i]);
        double y = esc.y(valores[i]);
        g.setColor(Tema.translucida(Cor.TEXTO_SECUNDARIO, 120));
        g.setStroke(new BasicStroke(1f));
        g.draw(new Line2D.Double(x, area.y, x, area.y + area.height));
        Severidade sev = severidades[i] == SEM_ALERTA ? null : Severidade.values()[severidades[i]];
        g.setColor(Cor.SUPERFICIE);
        g.fill(new Ellipse2D.Double(x - 5, y - 5, 10, 10));
        g.setColor(sev == null ? Cor.GRAFICO_LINHA : Tema.corDa(sev));
        g.fill(new Ellipse2D.Double(x - 3.5, y - 3.5, 7, 7));

        String texto = FormatoMensagens.hora(Instant.ofEpochMilli(tempos[i])) + "  "
                + FormatoMensagens.valorComUnidade(valores[i], tipo)
                + (sev == null ? "  OK" : "  " + sev.name());
        g.setFont(Fonte.DADO_NEGRITO);
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(texto) + Medida.ESP_4;
        int h = fm.getHeight() + Medida.ESP_2;
        int bx = (int) x + Medida.ESP_3;
        if (bx + w > area.x + area.width) {
            bx = (int) x - Medida.ESP_3 - w;
        }
        int by = area.y + Medida.ESP_1;
        var caixa = new RoundRectangle2D.Double(bx, by, w, h, Medida.RAIO, Medida.RAIO);
        g.setColor(Cor.SUPERFICIE);
        g.fill(caixa);
        g.setColor(Cor.BORDA);
        g.draw(caixa);
        g.setColor(sev == null ? Cor.TEXTO : Tema.corDa(sev));
        g.drawString(texto, bx + Medida.ESP_2, by + Medida.ESP_1 + fm.getAscent());
    }

    /** Linha da série; com mais leituras que pixels, mínimo e máximo por coluna (picos não somem). */
    private Path2D caminho(Escala esc, int i0, int i1) {
        Path2D p = new Path2D.Double();
        if (i1 - i0 <= esc.area.width * 1.5) {
            p.moveTo(esc.x(tempos[i0]), esc.y(valores[i0]));
            for (int i = i0 + 1; i < i1; i++) {
                p.lineTo(esc.x(tempos[i]), esc.y(valores[i]));
            }
            return p;
        }
        int coluna = Integer.MIN_VALUE;
        double vIni = 0;
        double vMin = 0;
        double vMax = 0;
        double vFim = 0;
        boolean primeiro = true;
        for (int i = i0; i <= i1; i++) {
            int c = i < i1 ? (int) esc.x(tempos[i]) : Integer.MAX_VALUE;
            if (c != coluna) {
                if (coluna != Integer.MIN_VALUE) {
                    if (primeiro) {
                        p.moveTo(coluna, esc.y(vIni));
                        primeiro = false;
                    } else {
                        p.lineTo(coluna, esc.y(vIni));
                    }
                    p.lineTo(coluna, esc.y(vMin));
                    p.lineTo(coluna, esc.y(vMax));
                    p.lineTo(coluna, esc.y(vFim));
                }
                if (i == i1) {
                    break;
                }
                coluna = c;
                vIni = vMin = vMax = valores[i];
            }
            vMin = Math.min(vMin, valores[i]);
            vMax = Math.max(vMax, valores[i]);
            vFim = valores[i];
        }
        return p;
    }

    private int primeiroIndiceEmOuDepois(long t) {
        int a = 0;
        int b = n;
        while (a < b) {
            int m = (a + b) >>> 1;
            if (tempos[m] < t) {
                a = m + 1;
            } else {
                b = m;
            }
        }
        return a;
    }

    /** 1, 2, 2,5 ou 5 × 10ⁿ mais próximo do passo bruto. */
    static double passoBonito(double bruto) {
        if (!(bruto > 0) || Double.isInfinite(bruto)) {
            return 1;
        }
        double base = Math.pow(10, Math.floor(Math.log10(bruto)));
        double f = bruto / base;
        double m = f < 1.5 ? 1 : f < 2.25 ? 2 : f < 3.5 ? 2.5 : f < 7.5 ? 5 : 10;
        return m * base;
    }

    private static long passoTempo(long duracaoMs, int marcas) {
        for (long p : PASSOS_TEMPO_MS) {
            if (duracaoMs / p <= marcas) {
                return p;
            }
        }
        return PASSOS_TEMPO_MS[PASSOS_TEMPO_MS.length - 1];
    }

    private static String rotuloValor(double v, double passo) {
        double arred = Math.abs(v) < passo / 1e6 ? 0 : v;
        return passo >= 1 && arred == Math.rint(arred)
                ? FormatoMensagens.inteiro(Math.round(arred))
                : FormatoMensagens.decimalCompacto(arred);
    }

    /** Converte tempo/valor em pixel dentro da área do gráfico. */
    private record Escala(Rectangle area, long tIni, long tFim, double lo, double hi) {
        double x(long t) {
            return area.x + (double) (t - tIni) / Math.max(1, tFim - tIni) * area.width;
        }

        double y(double v) {
            return area.y + area.height - (v - lo) / (hi - lo) * area.height;
        }

        long tempoEm(int px) {
            return tIni + Math.round((double) (px - area.x) / area.width * (tFim - tIni));
        }
    }
}
