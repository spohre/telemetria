package app.ui.tema;

import domain.TipoSensor;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Ícones vetoriais do sistema, desenhados com Java2D numa grade de 24×24 e escalados.
 * Monocromáticos: a cor vem do contexto (botão, estado, severidade). Sem PNG externo.
 */
public final class Icones {

    private Icones() {
    }

    public enum Icone {
        INICIAR, PAUSAR, PARAR,
        TEMPERATURA, VELOCIDADE, PRESSAO, UMIDADE, SENSOR,
        ALERTA, OK, ERRO,
        BANCO, HISTORICO, REGRAS, FILTRO, EXPORTAR, LOG, FILA, MONITORAMENTO,
        EXPANDIR, RECOLHER
    }

    /** Cria o ícone no tamanho e cor pedidos. */
    public static Icon de(Icone icone, int tamanho, Color cor) {
        return new IconeVetorial(icone, tamanho, cor);
    }

    /** Ícone que acompanha a cor de texto (foreground) do componente onde é desenhado. */
    public static Icon queSegueOTexto(Icone icone, int tamanho) {
        return new IconeVetorial(icone, tamanho, null);
    }

    /** Ícone que representa cada tipo de sensor. */
    public static Icone doTipo(TipoSensor tipo) {
        return switch (tipo) {
            case TEMPERATURA -> Icone.TEMPERATURA;
            case VELOCIDADE -> Icone.VELOCIDADE;
            case PRESSAO -> Icone.PRESSAO;
            case UMIDADE -> Icone.UMIDADE;
        };
    }

    private record IconeVetorial(Icone icone, int tamanho, Color cor) implements Icon {

        @Override
        public int getIconWidth() {
            return tamanho;
        }

        @Override
        public int getIconHeight() {
            return tamanho;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g2.translate(x, y);
                g2.scale(tamanho / 24.0, tamanho / 24.0);
                Color tinta = cor != null ? cor : (c != null ? c.getForeground() : Tema.Cor.TEXTO);
                g2.setColor(c != null && !c.isEnabled() ? Tema.Cor.TEXTO_DESATIVADO : tinta);
                g2.setStroke(new BasicStroke(1.75f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                desenhar(g2, icone);
            } finally {
                g2.dispose();
            }
        }
    }

    // Cada desenho usa coordenadas da grade 24×24.
    private static void desenhar(Graphics2D g, Icone icone) {
        switch (icone) {
            case INICIAR -> {
                Path2D p = new Path2D.Double();
                p.moveTo(7, 4.5);
                p.lineTo(19, 12);
                p.lineTo(7, 19.5);
                p.closePath();
                g.fill(p);
                g.draw(p);
            }
            case PAUSAR -> {
                g.fill(new RoundRectangle2D.Double(6, 5, 4, 14, 1.5, 1.5));
                g.fill(new RoundRectangle2D.Double(14, 5, 4, 14, 1.5, 1.5));
            }
            case PARAR -> g.fill(new RoundRectangle2D.Double(5.5, 5.5, 13, 13, 3, 3));
            case TEMPERATURA -> {
                Path2D p = new Path2D.Double();
                p.moveTo(10, 13.5);
                p.lineTo(10, 5);
                p.curveTo(10, 2.5, 14, 2.5, 14, 5);
                p.lineTo(14, 13.5);
                g.draw(p);
                // bulbo: círculo (12; 17,5) r 4,5, aberto no topo onde entra o tubo
                g.draw(new Arc2D.Double(7.5, 13, 9, 9, 116.4, 307.2, Arc2D.OPEN));
                g.fill(new Ellipse2D.Double(10, 15.5, 4, 4));
                g.draw(new Line2D.Double(12, 16, 12, 9));
            }
            case VELOCIDADE -> {
                g.draw(new Arc2D.Double(3, 5, 18, 18, -15, 210, Arc2D.OPEN));
                g.draw(new Line2D.Double(12, 14, 16.5, 9));
                g.fill(new Ellipse2D.Double(10.5, 12.5, 3, 3));
                g.draw(new Line2D.Double(5, 12, 6.3, 12.6));
                g.draw(new Line2D.Double(12, 6, 12, 7.5));
            }
            case PRESSAO -> {
                g.draw(new Ellipse2D.Double(3.5, 3.5, 17, 17));
                g.draw(new Line2D.Double(12, 12, 8, 8.5));
                g.fill(new Ellipse2D.Double(10.5, 10.5, 3, 3));
                g.draw(new Line2D.Double(8, 17, 16, 17));
            }
            case UMIDADE -> {
                Path2D p = new Path2D.Double();
                p.moveTo(12, 3);
                p.curveTo(12, 3, 5.5, 10.5, 5.5, 14.5);
                p.curveTo(5.5, 18.2, 8.4, 21, 12, 21);
                p.curveTo(15.6, 21, 18.5, 18.2, 18.5, 14.5);
                p.curveTo(18.5, 10.5, 12, 3, 12, 3);
                p.closePath();
                g.draw(p);
                g.draw(new Arc2D.Double(8.5, 11.5, 7, 7, 190, 70, Arc2D.OPEN));
            }
            case SENSOR -> {
                g.fill(new Ellipse2D.Double(10, 10, 4, 4));
                g.draw(new Arc2D.Double(7, 7, 10, 10, -45, 90, Arc2D.OPEN));
                g.draw(new Arc2D.Double(7, 7, 10, 10, 135, 90, Arc2D.OPEN));
                g.draw(new Arc2D.Double(3, 3, 18, 18, -45, 90, Arc2D.OPEN));
                g.draw(new Arc2D.Double(3, 3, 18, 18, 135, 90, Arc2D.OPEN));
            }
            case ALERTA -> {
                Path2D p = new Path2D.Double();
                p.moveTo(12, 3.5);
                p.lineTo(21.5, 20);
                p.lineTo(2.5, 20);
                p.closePath();
                g.draw(p);
                g.draw(new Line2D.Double(12, 9.5, 12, 14));
                g.fill(new Ellipse2D.Double(11, 16, 2, 2));
            }
            case OK -> {
                g.draw(new Ellipse2D.Double(3, 3, 18, 18));
                Path2D p = new Path2D.Double();
                p.moveTo(8, 12.3);
                p.lineTo(10.8, 15);
                p.lineTo(16, 9.3);
                g.draw(p);
            }
            case ERRO -> {
                g.draw(new Ellipse2D.Double(3, 3, 18, 18));
                g.draw(new Line2D.Double(9, 9, 15, 15));
                g.draw(new Line2D.Double(15, 9, 9, 15));
            }
            case BANCO -> {
                g.draw(new Ellipse2D.Double(5, 3.5, 14, 5));
                g.draw(new Line2D.Double(5, 6, 5, 18));
                g.draw(new Line2D.Double(19, 6, 19, 18));
                g.draw(new Arc2D.Double(5, 9.5, 14, 5, 180, 180, Arc2D.OPEN));
                g.draw(new Arc2D.Double(5, 15.5, 14, 5, 180, 180, Arc2D.OPEN));
            }
            case HISTORICO -> {
                g.draw(new Ellipse2D.Double(3.5, 3.5, 17, 17));
                g.draw(new Line2D.Double(12, 7.5, 12, 12));
                g.draw(new Line2D.Double(12, 12, 15, 14));
            }
            case REGRAS -> {
                g.draw(new Line2D.Double(4, 7, 20, 7));
                g.draw(new Line2D.Double(4, 12, 20, 12));
                g.draw(new Line2D.Double(4, 17, 20, 17));
                fillCircleWithBg(g, 9, 7);
                fillCircleWithBg(g, 15.5, 12);
                fillCircleWithBg(g, 7, 17);
            }
            case FILTRO -> {
                Path2D p = new Path2D.Double();
                p.moveTo(4, 5);
                p.lineTo(20, 5);
                p.lineTo(14, 12.5);
                p.lineTo(14, 19);
                p.lineTo(10, 17);
                p.lineTo(10, 12.5);
                p.closePath();
                g.draw(p);
            }
            case EXPORTAR -> {
                Path2D caixa = new Path2D.Double();
                caixa.moveTo(9, 9);
                caixa.lineTo(5, 9);
                caixa.lineTo(5, 20);
                caixa.lineTo(19, 20);
                caixa.lineTo(19, 9);
                caixa.lineTo(15, 9);
                g.draw(caixa);
                g.draw(new Line2D.Double(12, 3.5, 12, 14));
                Path2D seta = new Path2D.Double();
                seta.moveTo(8.5, 7);
                seta.lineTo(12, 3.5);
                seta.lineTo(15.5, 7);
                g.draw(seta);
            }
            case LOG -> {
                Path2D p = new Path2D.Double();
                p.moveTo(14, 3);
                p.lineTo(6, 3);
                p.lineTo(6, 21);
                p.lineTo(18, 21);
                p.lineTo(18, 7);
                p.closePath();
                g.draw(p);
                g.draw(new Line2D.Double(9, 11, 15, 11));
                g.draw(new Line2D.Double(9, 14.5, 15, 14.5));
                g.draw(new Line2D.Double(9, 18, 13, 18));
            }
            case FILA -> {
                g.draw(new RoundRectangle2D.Double(4, 4, 16, 4, 1.5, 1.5));
                g.draw(new RoundRectangle2D.Double(4, 10, 16, 4, 1.5, 1.5));
                g.draw(new RoundRectangle2D.Double(4, 16, 16, 4, 1.5, 1.5));
            }
            case MONITORAMENTO -> {
                Path2D p = new Path2D.Double();
                p.moveTo(3, 12);
                p.lineTo(7, 12);
                p.lineTo(9.5, 6);
                p.lineTo(14, 18);
                p.lineTo(16.5, 12);
                p.lineTo(21, 12);
                g.draw(p);
            }
            case EXPANDIR -> {             // setas saindo pelos cantos
                g.draw(polilinha(14, 4, 20, 4, 20, 10));
                g.draw(new Line2D.Double(20, 4, 13.5, 10.5));
                g.draw(polilinha(10, 20, 4, 20, 4, 14));
                g.draw(new Line2D.Double(4, 20, 10.5, 13.5));
            }
            case RECOLHER -> {             // setas entrando para o centro
                g.draw(polilinha(14, 4.5, 14, 10, 19.5, 10));
                g.draw(new Line2D.Double(14, 10, 20, 4));
                g.draw(polilinha(10, 19.5, 10, 14, 4.5, 14));
                g.draw(new Line2D.Double(10, 14, 4, 20));
            }
        }
    }

    private static Path2D polilinha(double... xy) {
        Path2D p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) {
            p.lineTo(xy[i], xy[i + 1]);
        }
        return p;
    }

    /** "Bolinha" de controle deslizante: limpa a linha por baixo e desenha o contorno. */
    private static void fillCircleWithBg(Graphics2D g, double cx, double cy) {
        Color atual = g.getColor();
        g.setColor(Tema.Cor.SUPERFICIE);
        g.fill(new Ellipse2D.Double(cx - 2.2, cy - 2.2, 4.4, 4.4));
        g.setColor(atual);
        g.draw(new Ellipse2D.Double(cx - 2.2, cy - 2.2, 4.4, 4.4));
    }
}
