package app.ui.telas;

import app.EstadoSensor;
import app.FormatoMensagens;
import app.RegraExibida;
import app.ui.tema.Componentes;
import app.ui.tema.Icones;
import app.ui.tema.Tema;
import domain.LeituraTelemetria;
import domain.Severidade;
import domain.TipoSensor;

import javax.swing.JButton;
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
import java.util.function.BiConsumer;

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
 * ┃ [Ver gráfico]             [Pausar] [Desligar]   ← controle só deste sensor
 * </pre>
 * Faixa lateral e etiqueta mostram o estado da leitura (NORMAL ou severidade). Sensor
 * pausado ou desligado troca a etiqueta por PAUSADO / DESLIGADO. A última linha tem os
 * botões que valem no estado: desligado → [Iniciar]; ligado → [Pausar] [Desligar];
 * pausado → [Retomar] [Desligar].
 * Só exibe e repassa o clique — quem decide e executa é o controller (via JanelaPrincipal).
 */
public final class CartaoSensor extends JPanel {

    private static final long serialVersionUID = 1L;

    private final TipoSensor tipo;
    private RegraExibida regra;
    private final JLabel faixa = Componentes.rotulo("");
    private final JLabel valor = Componentes.rotulo("—", Fonte.VALOR, Cor.TEXTO);
    private final JLabel rodape;
    private final JPanel areaBadge = Componentes.transparente(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    private final BarraFaixa barra = new BarraFaixa();
    private Color corEstado = Cor.TEXTO_DESATIVADO;

    /** Faixa aceita deste sensor mudou (aba Configurações): texto e barra passam a usar a nova. EDT. */
    public void definirRegra(RegraExibida nova) {
        if (nova.equals(regra)) {
            return;
        }
        regra = nova;
        mostrarFaixa();
        barra.repaint();
    }

    private void mostrarFaixa() {
        faixa.setText("faixa " + FormatoMensagens.decimal(regra.minimo()) + " – "
                + FormatoMensagens.valorComUnidade(regra.maximo(), tipo));
    }

    /** O que o usuário pediu para fazer com este sensor. */
    public enum Acao { INICIAR, PAUSAR, RETOMAR, DESLIGAR }

    private final String sensorId;
    private JComponent etiquetaLeitura;      // NORMAL / severidade da última leitura
    private final JButton iniciarOuRetomar = Componentes.botaoPequeno("Iniciar", Componentes.Estilo.PRIMARIO);
    private final JButton pausar = Componentes.botaoPequeno("Pausar", Componentes.Estilo.SECUNDARIO);
    private final JButton desligar = Componentes.botaoPequeno("Desligar", Componentes.Estilo.PERIGO);
    private EstadoSensor estadoAtual = EstadoSensor.DESLIGADO;
    private boolean ocupado;          // pedido enviado ao controller e ainda sem resposta

    /** Ação em destaque do cartão: largura total do rodapé, contorno laranja; preenchido = gráfico aberto. */
    private final JButton verGrafico = Componentes.botaoPequeno("Ver gráfico", Icones.Icone.MONITORAMENTO,
            Componentes.Estilo.DESTAQUE);

    /**
     * @param aoPedirAcao  Iniciar/Pausar/Retomar/Desligar deste sensor (quem executa é o controller)
     * @param aoVerGrafico abre/fecha o gráfico deste sensor na área de gráfico da aba
     */
    public CartaoSensor(String sensorId, TipoSensor tipo, RegraExibida regra, BiConsumer<String, Acao> aoPedirAcao,
                        java.util.function.Consumer<String> aoVerGrafico) {
        this.sensorId = sensorId;
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
        mostrarFaixa();
        linhaFaixa.add(faixa, BorderLayout.EAST);
        meio.add(linhaFaixa, BorderLayout.CENTER);

        rodape = Componentes.rotulo(tipo.nome() + " · aguardando leitura");

        add(topo, BorderLayout.NORTH);
        add(meio, BorderLayout.CENTER);
        // ---- linha de controle do sensor
        JPanel botoes = Componentes.transparente(new FlowLayout(FlowLayout.RIGHT, Medida.ESP_1, 0));
        botoes.add(iniciarOuRetomar);
        botoes.add(pausar);
        botoes.add(desligar);
        JPanel controle = Componentes.transparente(new BorderLayout(Medida.ESP_1, 0));
        controle.add(botoes, BorderLayout.EAST);
        verGrafico.setToolTipText("Mostra o histórico deste sensor num gráfico abaixo dos cartões");
        verGrafico.addActionListener(e -> aoVerGrafico.accept(sensorId));
        iniciarOuRetomar.addActionListener(e -> pedir(aoPedirAcao,
                estadoAtual == EstadoSensor.PAUSADO ? Acao.RETOMAR : Acao.INICIAR));
        pausar.addActionListener(e -> pedir(aoPedirAcao, Acao.PAUSAR));
        desligar.addActionListener(e -> pedir(aoPedirAcao, Acao.DESLIGAR));
        iniciarOuRetomar.setToolTipText("Liga só este sensor (com o sistema parado, liga só ele)");
        pausar.setToolTipText("Pausa só este sensor");
        desligar.setToolTipText("Desliga só este sensor (pode ser ligado de novo)");

        JPanel base = Componentes.transparente(new BorderLayout(0, Medida.ESP_2));
        base.add(rodape, BorderLayout.NORTH);
        base.add(controle, BorderLayout.CENTER);
        base.add(verGrafico, BorderLayout.SOUTH);           // largura total: a ação que mais se procura

        add(base, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(215, ALTURA));
        definirEstado(EstadoSensor.DESLIGADO, true);
    }

    /** Altura do cartão (leitura + linha de controle); a grade da aba usa o mesmo valor. */
    static final int ALTURA = 128 + 3 * Medida.ESP_2 + 2 * Medida.ALTURA_BOTAO_PEQUENO + Medida.ESP_1;

    /** Desabilita os botões até o controller responder (evita clique duplo). EDT. */
    private void pedir(BiConsumer<String, Acao> aoPedirAcao, Acao acao) {
        ocupado = true;
        iniciarOuRetomar.setEnabled(false);
        pausar.setEnabled(false);
        desligar.setEnabled(false);
        aoPedirAcao.accept(sensorId, acao);
    }

    /**
     * Mostra o estado do sensor e os botões que valem nele. EDT.
     *
     * @param habilitado {@code false} com o sistema pausado no geral ou encerrando: os botões
     *                   aparecem, mas desabilitados
     */
    public void definirEstado(EstadoSensor novo, boolean habilitado) {
        estadoAtual = novo;
        iniciarOuRetomar.setText(novo == EstadoSensor.PAUSADO ? "Retomar" : "Iniciar");
        iniciarOuRetomar.setVisible(novo != EstadoSensor.EXECUTANDO);
        pausar.setVisible(novo == EstadoSensor.EXECUTANDO);
        desligar.setVisible(novo != EstadoSensor.DESLIGADO);
        boolean ativo = habilitado && !ocupado;
        iniciarOuRetomar.setEnabled(ativo);
        pausar.setEnabled(ativo);
        desligar.setEnabled(ativo);
        aplicarAparenciaDoEstado();
        revalidate();
        repaint();
    }

    /** Gráfico DESTE sensor aberto: botão preenchido de laranja, "Fechar gráfico" (EDT). */
    public void marcarGraficoAberto(boolean aberto) {
        verGrafico.setSelected(aberto);
        verGrafico.setText(aberto ? "Fechar gráfico" : "Ver gráfico");
        verGrafico.setToolTipText(aberto ? "O gráfico deste sensor está aberto abaixo dos cartões — clique para fechar"
                : "Mostra o histórico deste sensor num gráfico abaixo dos cartões");
    }

    /** O controller respondeu ao último pedido (com sucesso ou erro): botões voltam a valer. EDT. */
    public void liberar() {
        ocupado = false;
    }

    public TipoSensor tipo() {
        return tipo;
    }

    public EstadoSensor estado() {
        return estadoAtual;
    }

    /**
     * Etiqueta do topo: severidade da última leitura com o sensor ligado; PAUSADO ou DESLIGADO
     * nos outros estados. Desligado também apaga o valor e a faixa lateral (a última leitura
     * continua visível).
     */
    private void aplicarAparenciaDoEstado() {
        if (estadoAtual == EstadoSensor.EXECUTANDO) {
            if (etiquetaLeitura != null) {
                trocarEtiqueta(etiquetaLeitura);
            }
            return;
        }
        boolean desligado = estadoAtual == EstadoSensor.DESLIGADO;
        trocarEtiqueta(Componentes.badge(desligado ? "DESLIGADO" : "PAUSADO",
                desligado ? Cor.TEXTO_SECUNDARIO : Cor.SEV_MEDIA));
        if (desligado) {
            valor.setForeground(Cor.TEXTO_DESATIVADO);
            corEstado = Cor.TEXTO_DESATIVADO;
        }
    }

    private void trocarEtiqueta(JComponent etiqueta) {
        areaBadge.removeAll();
        areaBadge.add(etiqueta);
        areaBadge.setVisible(true);
    }

    /** Atualiza com a última leitura. Chamar SOMENTE na EDT. */
    public void atualizar(LeituraTelemetria leitura, Severidade severidadeOuNull) {
        valor.setText(FormatoMensagens.valorComUnidade(leitura.valor(), tipo));
        corEstado = Tema.corDa(severidadeOuNull);
        valor.setForeground(severidadeOuNull == null ? Cor.TEXTO : corEstado);
        etiquetaLeitura = Componentes.badge(severidadeOuNull);
        trocarEtiqueta(etiquetaLeitura);
        barra.definir(leitura.valor(), corEstado);
        rodape.setText(tipo.nome() + " · última " + FormatoMensagens.hora(leitura.momento()));
        aplicarAparenciaDoEstado();      // leitura que ainda estava na fila não "religa" o visual
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
