package app.ui.telas;

import app.ControladorMonitoramento;
import app.EstatisticasExecucao;
import app.ExtratoExecucao;
import app.FaseSistema;
import app.FormatoMensagens;
import app.OuvinteTelemetria;
import app.ui.tema.Componentes;
import app.ui.tema.Componentes.Estilo;
import app.ui.tema.Icones;
import app.ui.tema.Icones.Icone;
import app.ui.tema.MensagemErro;
import domain.Alerta;
import domain.LeituraTelemetria;

import javax.swing.AbstractButton;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static app.ui.tema.Tema.Cor;
import static app.ui.tema.Tema.Fonte;
import static app.ui.tema.Tema.Medida;

/**
 * JANELA PRINCIPAL — Central de Telemetria (1280×800, mín. 1024×680)
 * <pre>
 * ┌ [SENSOR] Central de Telemetria   ● Em execução 0:04:12       [▶ Iniciar] [⏸ Pausar] [■ Parar] ┐
 * ├ [Monitoramento] [Histórico de alertas] [Regras e sensores]                                     ┤
 * │                         (CardLayout com a aba selecionada)                                    │
 * └ Leituras 12.430 · Fila 3/1000 · Alertas 87 · Threads 6 · Perdidas 0 · ● Banco · Log …         ┘
 * </pre>
 *
 * <p><b>Threads:</b> o controller chama {@link OuvinteTelemetria} a partir da thread
 * consumidora. Cada leitura entra numa fila concorrente e UM {@code invokeLater} é
 * agendado por vez (coalescência): sob estresse a EDT recebe lotes, não milhares de
 * tarefas. Nenhum componente Swing é tocado fora da EDT.</p>
 */
public final class JanelaPrincipal extends JFrame implements OuvinteTelemetria {

    private static final long serialVersionUID = 1L;
    private static final String CAMINHO_LOG = "data/leituras.log";

    private final transient ControladorMonitoramento controlador;

    // ---- fila de entrega para a EDT (escrita por threads de trabalho, lida na EDT)
    private final transient ConcurrentLinkedQueue<Object[]> pendentes = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean entregaAgendada = new AtomicBoolean(false);

    // ---- componentes
    private final JButton iniciar = Componentes.botao("Iniciar", Icone.INICIAR, Estilo.PRIMARIO);
    private final JButton pausar = Componentes.botao("Pausar", Icone.PAUSAR, Estilo.SECUNDARIO);
    private final JButton parar = Componentes.botao("Parar", Icone.PARAR, Estilo.PERIGO);
    private final JLabel indicadorFase = Componentes.indicador("Parado", Cor.TEXTO_SECUNDARIO);
    private final JLabel tempo = Componentes.rotulo("0:00:00", Fonte.DADO, Cor.TEXTO_SECUNDARIO);
    private final JLabel stLeituras = Componentes.dado("Leituras 0");
    private final JLabel stFila = Componentes.dado("Fila 0");
    private final JLabel stAlertas = Componentes.dado("Alertas 0");
    private final JLabel stThreads = Componentes.dado("Threads 0");
    private final JLabel stPendentes = Componentes.dado("Pendentes 0");
    private final JLabel stBanco = Componentes.indicador("Banco conectado", Cor.NORMAL);
    private final PainelMonitoramento painelMonitoramento;
    private final PainelHistorico painelHistorico;
    private final PainelRegras painelRegras;

    public JanelaPrincipal(ControladorMonitoramento controlador) {
        super("Central de Telemetria");
        this.controlador = controlador;
        this.painelMonitoramento = new PainelMonitoramento(controlador.regras(), null);
        this.painelHistorico = new PainelHistorico(controlador, this::exportarUltimoExtrato);
        this.painelRegras = new PainelRegras(controlador);

        setContentPane(criarConteudo());
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                aoFecharJanela();
            }
        });
        setSize(Medida.LARGURA_JANELA, Medida.ALTURA_JANELA);
        setMinimumSize(new Dimension(Medida.LARGURA_MINIMA, Medida.ALTURA_MINIMA));
        setLocationRelativeTo(null);

        iniciar.addActionListener(e -> aoClicarIniciar());
        pausar.addActionListener(e -> aoClicarPausar());
        parar.addActionListener(e -> aoClicarParar());
        controlador.registrarOuvinte(this);
        aplicarFase(controlador.fase());
    }

    /** Monta toda a interface (também usado para gerar as prévias sem abrir janela). */
    public JPanel criarConteudo() {
        JPanel raiz = new JPanel(new BorderLayout());
        raiz.setBackground(Cor.FUNDO);

        // ---- barra superior
        JPanel barra = Componentes.transparente(new BorderLayout(Medida.ESP_6, 0));
        barra.setBorder(Componentes.margem(Medida.ESP_4, Medida.ESP_6, Medida.ESP_3, Medida.ESP_6));
        JPanel marca = Componentes.transparente(new FlowLayout(FlowLayout.LEFT, Medida.ESP_3, 0));
        JLabel titulo = Componentes.titulo("Central de Telemetria");
        titulo.setIcon(Icones.de(Icone.SENSOR, Medida.ICONE_GRANDE, Cor.PRIMARIA));
        titulo.setIconTextGap(Medida.ESP_3);
        marca.add(titulo);
        marca.add(separadorVertical());
        marca.add(indicadorFase);
        marca.add(tempo);
        JPanel acoes = Componentes.transparente(new FlowLayout(FlowLayout.RIGHT, Medida.ESP_2, 0));
        acoes.add(iniciar);
        acoes.add(pausar);
        acoes.add(parar);
        barra.add(marca, BorderLayout.WEST);
        barra.add(acoes, BorderLayout.EAST);

        // ---- navegação segmentada + CardLayout
        CardLayout cards = new CardLayout();
        JPanel abas = Componentes.transparente(cards);
        abas.setBorder(Componentes.margem(Medida.ESP_3, Medida.ESP_6, Medida.ESP_4, Medida.ESP_6));
        abas.add(painelMonitoramento, "monitoramento");
        abas.add(painelHistorico, "historico");
        abas.add(painelRegras, "regras");

        JPanel nav = Componentes.transparente(new FlowLayout(FlowLayout.LEFT, Medida.ESP_1, 0));
        nav.setBorder(Componentes.margem(0, Medida.ESP_6 - Medida.ESP_1, 0, Medida.ESP_6));
        ButtonGroup grupo = new ButtonGroup();
        Object[][] defs = {
                {"Monitoramento", Icone.MONITORAMENTO, "monitoramento"},
                {"Histórico de alertas", Icone.HISTORICO, "historico"},
                {"Regras e sensores", Icone.REGRAS, "regras"}};
        for (Object[] d : defs) {
            JToggleButton aba = aba((String) d[0], (Icone) d[1]);
            grupo.add(aba);
            nav.add(aba);
            aba.addActionListener(e -> {
                cards.show(abas, (String) d[2]);
                if ("historico".equals(d[2])) {
                    painelHistorico.consultar();     // sempre dados frescos do banco
                }
            });
        }
        ((AbstractButton) nav.getComponent(0)).setSelected(true);

        JPanel topo = Componentes.transparente(new BorderLayout());
        topo.add(barra, BorderLayout.NORTH);
        topo.add(nav, BorderLayout.SOUTH);

        // ---- barra de status
        JPanel status = new JPanel(new BorderLayout());
        status.setBackground(Cor.SUPERFICIE);
        status.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0, Cor.BORDA),
                Componentes.margem(Medida.ESP_2, Medida.ESP_6, Medida.ESP_2, Medida.ESP_6)));
        JPanel contadores = Componentes.transparente(new FlowLayout(FlowLayout.LEFT, Medida.ESP_4, 0));
        stLeituras.setIcon(Icones.de(Icone.MONITORAMENTO, Medida.ICONE_PEQ, Cor.TEXTO_SECUNDARIO));
        stFila.setIcon(Icones.de(Icone.FILA, Medida.ICONE_PEQ, Cor.TEXTO_SECUNDARIO));
        stAlertas.setIcon(Icones.de(Icone.ALERTA, Medida.ICONE_PEQ, Cor.TEXTO_SECUNDARIO));
        for (JLabel l : new JLabel[]{stLeituras, stFila, stAlertas, stThreads, stPendentes}) {
            l.setIconTextGap(Medida.ESP_1 + 2);
            contadores.add(l);
        }
        JPanel direita = Componentes.transparente(new FlowLayout(FlowLayout.RIGHT, Medida.ESP_4, 0));
        stBanco.setFont(Fonte.DADO);
        direita.add(stBanco);
        JLabel log = Componentes.rotulo("Log " + CAMINHO_LOG, Fonte.DADO, Cor.TEXTO_SECUNDARIO);
        log.setIcon(Icones.de(Icone.LOG, Medida.ICONE_PEQ, Cor.TEXTO_SECUNDARIO));
        direita.add(log);
        status.add(contadores, BorderLayout.WEST);
        status.add(direita, BorderLayout.EAST);

        raiz.add(topo, BorderLayout.NORTH);
        raiz.add(abas, BorderLayout.CENTER);
        raiz.add(status, BorderLayout.SOUTH);
        return raiz;
    }

    // ================================================================== OuvinteTelemetria (OUTRAS threads)

    @Override
    public void aoProcessarLeitura(LeituraTelemetria leitura, Alerta alerta) {
        pendentes.add(new Object[]{leitura, alerta});
        agendarEntrega();
    }

    @Override
    public void aoAtualizarEstatisticas(EstatisticasExecucao est) {
        SwingUtilities.invokeLater(() -> mostrarEstatisticas(est));
    }

    @Override
    public void aoOcorrerFalha(String oQueAconteceu, String oQueFazer, Throwable causa) {
        SwingUtilities.invokeLater(() -> Componentes.mostrarErro(this,
                new MensagemErro("Falha durante a execução", oQueAconteceu, oQueFazer, causa)));
    }

    /** Agenda no máximo UM invokeLater por vez; ele entrega tudo o que acumulou. */
    private void agendarEntrega() {
        if (entregaAgendada.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(this::entregarPendentes);
        }
    }

    /** Roda na EDT. */
    private void entregarPendentes() {
        entregaAgendada.set(false);
        List<Object[]> lote = new ArrayList<>();
        for (Object[] item; (item = pendentes.poll()) != null; ) {
            lote.add(item);
        }
        for (Object[] item : lote) {
            painelMonitoramento.registrarLeitura((LeituraTelemetria) item[0], (Alerta) item[1]);
        }
    }

    // ================================================================== ações (EDT)

    private void aoClicarIniciar() {
        if (controlador.fase() == FaseSistema.PAUSADO) {
            controlador.retomar();
        } else {
            controlador.iniciar();
        }
        aplicarFase(controlador.fase());
    }

    private void aoClicarPausar() {
        controlador.pausar();
        aplicarFase(controlador.fase());
    }

    private void aoClicarParar() {
        int r = JOptionPane.showConfirmDialog(this,
                "Parar interrompe todos os sensores e processa as leituras que ainda estão na fila.\nDeseja parar?",
                "Parar o sistema", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.YES_OPTION) {
            return;
        }
        pararEmSegundoPlano(extrato -> DialogoExtrato.mostrar(this, extrato, controlador));
    }

    private ExtratoExecucao ultimoExtrato;

    /** Encerrar threads pode demorar (drenar fila, join) → SwingWorker. */
    private void pararEmSegundoPlano(java.util.function.Consumer<ExtratoExecucao> depois) {
        iniciar.setEnabled(false);
        pausar.setEnabled(false);
        parar.setEnabled(false);
        Componentes.atualizarIndicador(indicadorFase, "Encerrando…", Cor.SEV_MEDIA);
        new SwingWorker<ExtratoExecucao, Void>() {
            @Override
            protected ExtratoExecucao doInBackground() {
                return controlador.parar();
            }

            @Override
            protected void done() {
                aplicarFase(controlador.fase());
                try {
                    ultimoExtrato = get();
                    depois.accept(ultimoExtrato);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    Componentes.mostrarErro(JanelaPrincipal.this, new MensagemErro(
                            "O sistema não encerrou corretamente",
                            "Alguma thread não terminou dentro do tempo limite ao parar.",
                            "Feche a janela e abra o programa de novo. Se repetir, confira o console: "
                                    + "a linha ERRO indica qual sensor ou consumidor travou.",
                            ex.getCause()));
                }
            }
        }.execute();
    }

    private void exportarUltimoExtrato() {
        if (ultimoExtrato == null) {
            Componentes.mostrarErro(this, new MensagemErro(
                    "Ainda não há extrato",
                    "O extrato é gerado quando uma execução termina, e nenhuma terminou nesta sessão.",
                    "Clique em Iniciar, deixe os sensores rodarem e depois clique em Parar.",
                    null));
            return;
        }
        DialogoExtrato.mostrar(this, ultimoExtrato, controlador);
    }

    private void aoFecharJanela() {
        if (controlador.fase() == FaseSistema.PARADO) {
            dispose();
            return;
        }
        pararEmSegundoPlano(extrato -> dispose());
    }

    /** Habilita os botões conforme a fase (EDT). */
    public void aplicarFase(FaseSistema fase) {
        switch (fase) {
            case PARADO -> {
                iniciar.setText("Iniciar");
                iniciar.setEnabled(true);
                pausar.setEnabled(false);
                parar.setEnabled(false);
                Componentes.atualizarIndicador(indicadorFase, "Parado", Cor.TEXTO_SECUNDARIO);
            }
            case EXECUTANDO -> {
                iniciar.setText("Iniciar");
                iniciar.setEnabled(false);
                pausar.setEnabled(true);
                parar.setEnabled(true);
                Componentes.atualizarIndicador(indicadorFase, "Em execução", Cor.NORMAL);
            }
            case PAUSADO -> {
                iniciar.setText("Retomar");
                iniciar.setEnabled(true);
                pausar.setEnabled(false);
                parar.setEnabled(true);
                Componentes.atualizarIndicador(indicadorFase, "Pausado", Cor.SEV_MEDIA);
            }
        }
        painelRegras.aoMudarFase(fase);
    }

    /** EDT. Público para pré-visualização. */
    public void mostrarEstatisticas(EstatisticasExecucao e) {
        stLeituras.setText("Leituras " + FormatoMensagens.inteiro(e.leiturasProcessadas()));
        stFila.setText("Fila " + e.tamanhoFila() + "/" + FormatoMensagens.inteiro(e.capacidadeFila()));
        stFila.setForeground(e.tamanhoFila() > e.capacidadeFila() * 0.9 ? Cor.SEV_MEDIA : Cor.TEXTO);
        stAlertas.setText("Alertas " + FormatoMensagens.inteiro(e.alertasGerados()));
        stThreads.setText("Threads " + e.threadsAtivas());
        stPendentes.setText("Pendentes " + FormatoMensagens.inteiro(e.leiturasPendentes()));
        stPendentes.setForeground(e.leiturasPendentes() > e.capacidadeFila() ? Cor.PERIGO : Cor.TEXTO);
        Componentes.atualizarIndicador(stBanco, e.bancoConectado() ? "Banco conectado" : "Banco indisponível",
                e.bancoConectado() ? Cor.NORMAL : Cor.PERIGO);
        tempo.setText(FormatoMensagens.duracao(e.segundosDecorridos()));
    }

    public PainelMonitoramento painelMonitoramento() {
        return painelMonitoramento;
    }

    public PainelHistorico painelHistorico() {
        return painelHistorico;
    }

    // ================================================================== peças locais

    private static JToggleButton aba(String texto, Icone icone) {
        JToggleButton b = new JToggleButton(texto, Icones.queSegueOTexto(icone, Medida.ICONE_PEQ)) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (isSelected()) {
                    g2.setColor(Cor.SUPERFICIE_ELEVADA);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight() - 3, Medida.RAIO * 2, Medida.RAIO * 2);
                    g2.setColor(Cor.PRIMARIA);
                    g2.fillRect(Medida.ESP_3, getHeight() - 2, getWidth() - Medida.ESP_3 * 2, 2);
                } else if (getModel().isRollover()) {
                    g2.setColor(Cor.SUPERFICIE);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight() - 3, Medida.RAIO * 2, Medida.RAIO * 2);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setFont(Fonte.CORPO_NEGRITO);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setRolloverEnabled(true);
        b.setIconTextGap(Medida.ESP_2);
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        b.setBorder(Componentes.margem(Medida.ESP_2, Medida.ESP_4, Medida.ESP_2 + 3, Medida.ESP_4));
        Runnable cor = () -> b.setForeground(b.isSelected() ? Cor.TEXTO
                : (b.getModel().isRollover() ? Cor.TEXTO : Cor.TEXTO_SECUNDARIO));
        b.getModel().addChangeListener(e -> cor.run());
        cor.run();
        return b;
    }

    private static JPanel separadorVertical() {
        JPanel s = new JPanel();
        s.setBackground(Cor.BORDA);
        s.setPreferredSize(new Dimension(1, 20));
        return s;
    }
}
