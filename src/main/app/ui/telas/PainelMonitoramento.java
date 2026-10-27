package app.ui.telas;

import app.FaseSistema;
import app.FormatoMensagens;
import app.HistoricoExecucao;
import app.LeituraRegistrada;
import app.RegraExibida;
import app.ui.tema.Componentes;
import app.ui.tema.Icones;
import app.ui.tema.Icones.Icone;
import app.ui.tema.Tema;
import domain.Alerta;
import domain.LeituraTelemetria;
import domain.Severidade;
import domain.TipoSensor;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static app.ui.tema.Tema.Cor;
import static app.ui.tema.Tema.Fonte;
import static app.ui.tema.Tema.Medida;

/**
 * ABA MONITORAMENTO
 * <pre>
 * ┌ Sensores ────────────── ● Ao vivo desde 13:21:09 · [Expandir todos] ┐
 * │ ┃ PRES-01  NORMAL  [⤢]    │ gráfico do histórico (100 px)              │  um sensor por linha,
 * │ ┃ 2,6 bar · faixa · última│ - - máx ─╱╲─╱╲── - - mín                  │  empilhados na vertical
 * │ ┃ TEMP-01 …               │ gráfico …                                 │  (rola; expandir = mais alto)
 * ╞══════════════════════ divisor arrastável ════════════════════════════╡
 * ├ Leituras em tempo real ───── 62% ──────┬ Alertas recentes ── 38% ─────┤
 * │ Hora | Sensor | Tipo | Valor | Estado  │ [ALTA] TEMP-01 92,5 °C 19:46 │
 * └────────────────────────────────────────┴──────────────────────────────┘
 * </pre>
 * Com o sistema parado, os gráficos mostram a última execução (desta sessão ou lida do log
 * ao abrir o programa). Ao Iniciar, começam vazios e acompanham as leituras em tempo real.
 * Dados chegam da JanelaPrincipal, que já está na EDT. Todos os métodos públicos: EDT.
 */
public final class PainelMonitoramento extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int MAX_ALERTAS = 50;
    private static final double PROPORCAO_SENSORES = 0.7;

    private final Map<String, CartaoSensor> cartoes = new java.util.TreeMap<>();   // ordenados por id
    private final Map<TipoSensor, RegraExibida> regras = new LinkedHashMap<>();
    private final Componentes.PainelRolavel listaSensores = new Componentes.PainelRolavel(null);
    private final JLabel estadoDados = Componentes.rotulo("", Fonte.ROTULO, Cor.TEXTO_SECUNDARIO);
    private final JButton expandirTodos = Componentes.botao("Expandir todos", null, Componentes.Estilo.FANTASMA);
    private final JLabel vazio = Componentes.rotulo("", Fonte.CORPO, Cor.TEXTO_SECUNDARIO);
    private FaseSistema fase = FaseSistema.PARADO;
    private boolean historicoDoLog;
    private final DefaultTableModel modeloLeituras = new DefaultTableModel(
            new Object[]{"Hora", "Sensor", "Tipo", "Valor", "Estado"}, 0) {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(int l, int c) {
            return false;
        }
    };
    private final Componentes.PainelRolavel listaAlertas = new Componentes.PainelRolavel(null);
    private final CardLayout trocaVazio = new CardLayout();
    private final JPanel areaSensores = Componentes.transparente(trocaVazio);
    private final JLabel contagemAlertas = Componentes.rotulo("0", Fonte.DADO_NEGRITO, Cor.TEXTO_SECUNDARIO);
    private long totalAlertas;

    public PainelMonitoramento(List<RegraExibida> regrasExibidas, Runnable aoLimparAlertas) {
        regrasExibidas.forEach(r -> regras.put(r.tipo(), r));
        setOpaque(false);

        // ---- sensores: mensagem de vazio × lista vertical (um sensor por linha)
        vazio.setHorizontalAlignment(SwingConstants.CENTER);
        vazio.setIcon(Icones.de(Icone.SENSOR, Medida.ICONE_GRANDE, Cor.TEXTO_DESATIVADO));
        vazio.setIconTextGap(Medida.ESP_3);
        mostrarVazio("Nenhuma execução registrada — clique em Iniciar para ligar os sensores.");
        areaSensores.add(vazio, "vazio");
        listaSensores.setLayout(new BoxLayout(listaSensores, BoxLayout.Y_AXIS));
        JScrollPane rolagemSensores = Componentes.rolagem(listaSensores);
        rolagemSensores.getVerticalScrollBar().setUnitIncrement(Medida.ESP_6);
        areaSensores.add(rolagemSensores, "lista");
        rolagemSensores.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                cartoes.values().forEach(CartaoSensor::ajustarAoEspacoVisivel);
            }
        });
        expandirTodos.setToolTipText("Expande ou recolhe o gráfico de todos os sensores");
        expandirTodos.addActionListener(e -> {
            boolean expandir = cartoes.values().stream().anyMatch(c -> !c.expandido());
            cartoes.values().forEach(c -> c.definirExpandido(expandir));
            atualizarBotaoExpandirTodos();
            listaSensores.revalidate();
        });
        expandirTodos.setEnabled(false);
        JPanel secSensores = Componentes.secao("Sensores", Icone.SENSOR, areaSensores, estadoDados, expandirTodos);

        // ---- tabela ao vivo
        JTable tabela = new JTable(modeloLeituras);
        tabela.getColumnModel().getColumn(4).setCellRenderer(new Componentes.CelulaSeveridade());
        tabela.getColumnModel().getColumn(0).setPreferredWidth(80);
        tabela.getColumnModel().getColumn(2).setPreferredWidth(110);
        JPanel secLeituras = Componentes.secao("Leituras em tempo real", Icone.MONITORAMENTO, Componentes.tabela(tabela),
                Componentes.rotulo("últimas " + Medida.MAX_LINHAS_AO_VIVO));

        // ---- alertas (lista que acompanha a largura → nada cortado à direita)
        listaAlertas.setLayout(new BoxLayout(listaAlertas, BoxLayout.Y_AXIS));
        JScrollPane rolagemAlertas = Componentes.rolagem(listaAlertas);
        JButton limpar = Componentes.botao("Limpar", null, Componentes.Estilo.FANTASMA);
        limpar.setToolTipText("Limpa só esta lista — os alertas continuam salvos no banco");
        limpar.addActionListener(e -> {
            listaAlertas.removeAll();
            totalAlertas = 0;
            contagemAlertas.setText("0");
            listaAlertas.revalidate();
            listaAlertas.repaint();
            if (aoLimparAlertas != null) {
                aoLimparAlertas.run();
            }
        });
        JPanel secAlertas = Componentes.secao("Alertas recentes", Icone.ALERTA, rolagemAlertas, contagemAlertas, limpar);

        // ---- montagem: sensores em cima; leituras + alertas embaixo; divisor arrastável
        JPanel inferior = Componentes.transparente(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.gridy = 0;
        c.weighty = 1;
        c.gridx = 0;
        c.weightx = 0.62;
        c.insets = new Insets(0, 0, 0, Medida.ESP_3);
        inferior.add(secLeituras, c);
        c.gridx = 1;
        c.weightx = 0.38;
        c.insets = new Insets(0, 0, 0, 0);
        inferior.add(secAlertas, c);
        // larguras-base proporcionais; o GridBag distribui o resto pelos pesos
        secLeituras.setPreferredSize(new Dimension(620, 0));
        secAlertas.setPreferredSize(new Dimension(380, 0));
        secSensores.setMinimumSize(new Dimension(0, Medida.ALTURA_SENSOR + 3 * Medida.ESP_6));   // título + uma linha
        inferior.setMinimumSize(new Dimension(0, Medida.ALTURA_MINIMA_TABELAS));

        JSplitPane divisor = new JSplitPane(JSplitPane.VERTICAL_SPLIT, secSensores, inferior);
        divisor.setUI(new DivisorDiscreto());
        divisor.setBorder(null);
        divisor.setOpaque(false);
        divisor.setDividerSize(Medida.ESP_3);
        divisor.setContinuousLayout(true);
        divisor.setResizeWeight(PROPORCAO_SENSORES);
        divisor.addComponentListener(new ComponentAdapter() {      // posição inicial proporcional, uma vez
            @Override
            public void componentResized(ComponentEvent e) {
                if (divisor.getHeight() > 0) {
                    divisor.setDividerLocation(PROPORCAO_SENSORES);
                    divisor.removeComponentListener(this);
                }
            }
        });
        setLayout(new BorderLayout());
        add(divisor, BorderLayout.CENTER);
    }

    /** Leitura processada (EDT). Cria a linha do sensor na primeira vez que ele aparece. */
    public void registrarLeitura(LeituraTelemetria l, Alerta alertaOuNull) {
        Severidade sev = alertaOuNull == null ? null : alertaOuNull.severidade();
        cartao(l).atualizar(l, sev);
        atualizarEstadoDados();

        modeloLeituras.insertRow(0, new Object[]{
                FormatoMensagens.hora(l.momento()), l.sensorId(), l.tipo().nome(),
                FormatoMensagens.valorComUnidade(l.valor(), l.tipo()), sev == null ? "OK" : sev});
        while (modeloLeituras.getRowCount() > Medida.MAX_LINHAS_AO_VIVO) {
            modeloLeituras.removeRow(modeloLeituras.getRowCount() - 1);
        }
        if (alertaOuNull != null) {
            adicionarAlerta(alertaOuNull);
        }
    }

    /**
     * Mostra nos gráficos a última execução lida do log (ao abrir o programa). Não mexe na
     * tabela ao vivo nem na lista de alertas, que são só da execução em andamento.
     */
    public void carregarHistorico(HistoricoExecucao historico) {
        limparSensores();
        historicoDoLog = true;
        if (historico.vazio()) {
            mostrarVazio("Nenhuma execução registrada no log — clique em Iniciar para ligar os sensores.");
            atualizarEstadoDados();
            return;
        }
        Map<String, LeituraRegistrada> ultimas = new LinkedHashMap<>();
        for (LeituraRegistrada r : historico.leituras()) {
            cartao(r.leitura()).acrescentarAoGrafico(r.leitura(), r.severidade());
            ultimas.put(r.leitura().sensorId(), r);
        }
        ultimas.forEach((id, r) -> cartoes.get(id).mostrarUltima(r.leitura(), r.severidade()));
        atualizarEstadoDados();
    }

    /** Não foi possível ler o log da última execução: avisa no lugar dos gráficos (não é erro grave). */
    public void avisarHistoricoIndisponivel(String motivo) {
        if (cartoes.isEmpty()) {
            mostrarVazio("Não foi possível ler a última execução (" + motivo + "). Clique em Iniciar para começar.");
        }
    }

    /** Antes de Iniciar: os gráficos passam a mostrar só a nova execução. */
    public void novaExecucao() {
        limparSensores();
        historicoDoLog = false;
        mostrarVazio("Aguardando a primeira leitura dos sensores…");
        modeloLeituras.setRowCount(0);
        atualizarEstadoDados();
    }

    public void aoMudarFase(FaseSistema nova) {
        fase = nova;
        atualizarEstadoDados();
    }

    /** Há dados nos gráficos (desta sessão ou do log)? */
    public boolean temDados() {
        return !cartoes.isEmpty();
    }

    private CartaoSensor cartao(LeituraTelemetria l) {
        CartaoSensor existente = cartoes.get(l.sensorId());
        if (existente != null) {
            return existente;
        }
        RegraExibida r = regras.getOrDefault(l.tipo(), new RegraExibida(l.tipo(), 0, 100, Severidade.MEDIA));
        CartaoSensor novo = new CartaoSensor(l.sensorId(), l.tipo(), r);
        cartoes.put(l.sensorId(), novo);
        reordenarLista();
        return novo;
    }

    /** Remonta a lista em ordem de id, com espaço entre as linhas. */
    private void reordenarLista() {
        listaSensores.removeAll();
        boolean primeiro = true;
        for (CartaoSensor c : cartoes.values()) {
            if (!primeiro) {
                listaSensores.add(Box.createVerticalStrut(Medida.ESP_3));
            }
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            listaSensores.add(c);
            primeiro = false;
        }
        trocaVazio.show(areaSensores, cartoes.isEmpty() ? "vazio" : "lista");
        expandirTodos.setEnabled(!cartoes.isEmpty());
        atualizarBotaoExpandirTodos();
        listaSensores.revalidate();
        listaSensores.repaint();
    }

    private void limparSensores() {
        cartoes.clear();
        reordenarLista();
    }

    private void mostrarVazio(String texto) {
        vazio.setText(texto);
        trocaVazio.show(areaSensores, "vazio");
    }

    private void atualizarBotaoExpandirTodos() {
        boolean todosExpandidos = !cartoes.isEmpty() && cartoes.values().stream().allMatch(CartaoSensor::expandido);
        expandirTodos.setText(todosExpandidos ? "Recolher todos" : "Expandir todos");
    }

    /** Texto ao lado do título: ao vivo × última execução (com período). */
    private void atualizarEstadoDados() {
        Instant ini = cartoes.values().stream().map(CartaoSensor::primeiraLeitura)
                .filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
        Instant fim = cartoes.values().stream().map(CartaoSensor::ultimaLeitura)
                .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
        switch (fase) {
            case EXECUTANDO -> Componentes.atualizarIndicador(estadoDados,
                    ini == null ? "Ao vivo" : "Ao vivo desde " + FormatoMensagens.hora(ini), Cor.NORMAL);
            case PAUSADO -> Componentes.atualizarIndicador(estadoDados, "Pausado", Cor.SEV_MEDIA);
            case PARADO -> {
                estadoDados.setIcon(null);
                estadoDados.setForeground(Cor.TEXTO_SECUNDARIO);
                estadoDados.setText(ini == null ? ""
                        : "Última execução · " + FormatoMensagens.dataHora(ini) + " – " + FormatoMensagens.hora(fim)
                        + (historicoDoLog ? " · lida do log" : ""));
            }
        }
        estadoDados.setFont(fase == FaseSistema.PARADO ? Fonte.ROTULO : Fonte.ROTULO_NEGRITO);
    }

    private void adicionarAlerta(Alerta a) {
        listaAlertas.add(itemAlerta(a), 0);
        while (listaAlertas.getComponentCount() > MAX_ALERTAS) {
            listaAlertas.remove(listaAlertas.getComponentCount() - 1);
        }
        totalAlertas++;
        contagemAlertas.setText(FormatoMensagens.inteiro(totalAlertas));
        listaAlertas.revalidate();
        listaAlertas.repaint();
    }

    /** Item: [BADGE] SENSOR valor ................ hora / regra violada · salvo #id */
    private static JPanel itemAlerta(Alerta a) {
        JPanel item = Componentes.transparente(new BorderLayout(Medida.ESP_2, Medida.ESP_1));
        item.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Cor.BORDA),
                Componentes.margem(Medida.ESP_2, 0, Medida.ESP_2, Medida.ESP_2)));

        JPanel esquerda = Componentes.transparente(null);
        esquerda.setLayout(new BoxLayout(esquerda, BoxLayout.X_AXIS));
        esquerda.add(Componentes.badge(a.severidade()));
        esquerda.add(Box.createHorizontalStrut(Medida.ESP_2));
        esquerda.add(Componentes.rotulo(a.sensorId(), Fonte.CORPO_NEGRITO, Cor.TEXTO));
        esquerda.add(Box.createHorizontalStrut(Medida.ESP_2));
        esquerda.add(Componentes.rotulo(FormatoMensagens.valorComUnidade(a.valorLido(), a.tipo()),
                Fonte.DADO_NEGRITO, Tema.corDa(a.severidade())));

        JPanel linha1 = Componentes.transparente(new BorderLayout(Medida.ESP_2, 0));
        linha1.add(esquerda, BorderLayout.WEST);
        linha1.add(Componentes.rotulo(FormatoMensagens.hora(a.momentoLeitura()), Fonte.DADO, Cor.TEXTO_SECUNDARIO),
                BorderLayout.EAST);

        String salvo = a.id() == null ? "não salvo" : "salvo #" + a.id();
        item.add(linha1, BorderLayout.NORTH);
        item.add(Componentes.rotulo(a.regraViolada() + " · " + salvo), BorderLayout.CENTER);
        item.setAlignmentX(Component.LEFT_ALIGNMENT);
        item.setMaximumSize(new Dimension(Integer.MAX_VALUE, item.getPreferredSize().height));
        return item;
    }

    /** Divisor do JSplitPane no tema: faixa transparente com um traço curto de "pegador". */
    private static final class DivisorDiscreto extends javax.swing.plaf.basic.BasicSplitPaneUI {
        @Override
        public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
            javax.swing.plaf.basic.BasicSplitPaneDivider d = new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                private static final long serialVersionUID = 1L;

                @Override
                public void paint(Graphics g) {
                    int largura = Medida.ESP_6 * 2;
                    g.setColor(Cor.BORDA);
                    g.fillRoundRect((getWidth() - largura) / 2, getHeight() / 2 - 2, largura, Medida.ESP_1,
                            Medida.ESP_1, Medida.ESP_1);
                }
            };
            d.setBorder(null);
            d.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR));
            return d;
        }
    }
}
