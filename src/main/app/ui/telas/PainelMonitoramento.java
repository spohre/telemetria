package app.ui.telas;

import app.FormatoMensagens;
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
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static app.ui.tema.Tema.Cor;
import static app.ui.tema.Tema.Fonte;
import static app.ui.tema.Tema.Medida;

/**
 * ABA MONITORAMENTO
 * <pre>
 * ┌ Sensores ──────────────────────────────────────────────────────────────────────────┐
 * │ [PRES-02 3,8 bar] [TEMP-01 92,5 °C] [TEMP-02 41 °C] [UMID-01 30 %]   (até 4 cartões   │
 * │  [ Ver gráfico ]   [ Ver gráfico ]   [Fechar gráfico] [ Ver gráfico ]  por página)     │
 * │        [‹ Anterior]  Página 2 de 3 · sensores 5–8 de 10  [Próxima ›]   ← só com > 4    │
 * │ ─ Gráfico · TEMP-02 (quando aberto) ──────────────────────────────────────────────── │
 * ├ Leituras em tempo real ────────── 62% ────────┬ Alertas recentes ─── 38% ─────────┤
 * │ Hora | Sensor | Tipo | Valor | Estado         │ [ALTA] TEMP-01 92,5 °C      19:46 │
 * │ (últimas 200, mais recente no topo)           │ acima do limite 80,0 °C · #128    │
 * └───────────────────────────────────────────────┴───────────────────────────────────┘
 * </pre>
 * Dados chegam da JanelaPrincipal, que já está na EDT. Todos os métodos públicos: EDT.
 */
public final class PainelMonitoramento extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int MAX_ALERTAS = 50;
    /** 4 cartões lado a lado cabem na janela mínima (1024 px). */
    private static final int LARGURA_MIN_CARTAO = 215;
    private static final int ALTURA_CARTAO = CartaoSensor.ALTURA;
    /** Cartões por página: com mais sensores que isto, a seção ganha a paginação. */
    static final int CARTOES_POR_PAGINA = 4;

    private final Map<String, CartaoSensor> cartoes = new java.util.TreeMap<>();   // ordenados por id
    private final Map<TipoSensor, RegraExibida> regras = new LinkedHashMap<>();
    /** Faixa própria de cada sensor (id → regra); sem entrada, vale a faixa padrão do tipo. */
    private final Map<String, RegraExibida> regrasDosSensores = new java.util.HashMap<>();
    private final GradeCartoes grade = new GradeCartoes();
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

    // ---- paginação dos cartões (todos continuam existindo e recebendo dados; só 4 aparecem)
    private int pagina;                                   // a partir de 0
    private final JButton paginaAnterior = Componentes.botaoPequeno("‹ Anterior", Componentes.Estilo.DESTAQUE);
    private final JButton proximaPagina = Componentes.botaoPequeno("Próxima ›", Componentes.Estilo.DESTAQUE);
    private final JLabel infoPagina = Componentes.rotulo("", Fonte.DADO, Cor.TEXTO_SECUNDARIO);
    /** Logo abaixo dos cartões (acima do gráfico, quando aberto), centralizado. */
    private final JPanel paginador = Componentes.transparente(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER,
            Medida.ESP_3, 0));

    private final java.util.function.BiConsumer<String, CartaoSensor.Acao> aoPedirAcaoSensor;
    private boolean controleHabilitado;

    /**
     * @param aoPedirAcaoSensor chamado (na EDT) quando o usuário clica em Iniciar/Pausar/Retomar/Desligar
     *                          no cartão de um sensor; quem executa é a JanelaPrincipal, via controller
     */
    public PainelMonitoramento(List<RegraExibida> regrasExibidas, Runnable aoLimparAlertas,
                               java.util.function.BiConsumer<String, CartaoSensor.Acao> aoPedirAcaoSensor) {
        this.aoPedirAcaoSensor = aoPedirAcaoSensor;
        regrasExibidas.forEach(r -> regras.put(r.tipo(), r));
        setOpaque(false);
        setLayout(new GridBagLayout());

        // ---- sensores: mensagem de vazio × grade
        JLabel vazio = Componentes.rotulo("Nenhum sensor configurado — adicione sensores na aba Configurações.",
                Fonte.CORPO, Cor.TEXTO_SECUNDARIO);
        vazio.setHorizontalAlignment(SwingConstants.CENTER);
        vazio.setIcon(Icones.de(Icone.SENSOR, Medida.ICONE_GRANDE, Cor.TEXTO_DESATIVADO));
        vazio.setIconTextGap(Medida.ESP_3);
        areaSensores.add(vazio, "vazio");
        JScrollPane rolagemGrade = Componentes.rolagem(grade);
        // nunca menos que uma linha inteira de cartões (com o gráfico aberto em janela pequena,
        // quem encolhe são as tabelas de baixo)
        rolagemGrade.setMinimumSize(new Dimension(0, ALTURA_CARTAO));
        areaSensores.setMinimumSize(new Dimension(0, ALTURA_CARTAO));
        areaSensores.add(rolagemGrade, "grade");
        // área "Ver gráfico": escondida até o usuário pedir o gráfico de um sensor
        // cartões → paginação (só com mais de 4) → área "Ver gráfico"
        JPanel cartoesEPaginas = Componentes.transparente(new BorderLayout(0, Medida.ESP_2));
        cartoesEPaginas.add(areaSensores, BorderLayout.CENTER);
        cartoesEPaginas.add(paginador, BorderLayout.SOUTH);
        JPanel conteudoSensores = Componentes.transparente(new BorderLayout(0, Medida.ESP_3));
        conteudoSensores.add(cartoesEPaginas, BorderLayout.CENTER);
        conteudoSensores.add(criarAreaGrafico(), BorderLayout.SOUTH);
        paginador.add(paginaAnterior);
        paginador.add(infoPagina);
        paginador.add(proximaPagina);
        paginador.setVisible(false);
        paginaAnterior.setToolTipText("Sensores anteriores");
        proximaPagina.setToolTipText("Próximos sensores");
        paginaAnterior.addActionListener(e -> irParaPagina(pagina - 1));
        proximaPagina.addActionListener(e -> irParaPagina(pagina + 1));
        JPanel secSensores = Componentes.secao("Sensores", Icone.SENSOR, conteudoSensores);

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

        // ---- montagem
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        c.weighty = 0;
        c.insets = new Insets(0, 0, Medida.ESP_3, 0);
        add(secSensores, c);

        c.gridy = 1;
        c.gridwidth = 1;
        c.weighty = 1;
        c.weightx = 0.62;
        c.insets = new Insets(0, 0, 0, Medida.ESP_3);
        add(secLeituras, c);
        c.gridx = 1;
        c.weightx = 0.38;
        c.insets = new Insets(0, 0, 0, 0);
        add(secAlertas, c);
        // larguras-base proporcionais; o GridBag distribui o resto pelos pesos
        secLeituras.setPreferredSize(new Dimension(620, 0));
        secAlertas.setPreferredSize(new Dimension(380, 0));
    }

    /** Leitura processada (EDT). O cartão já existe (ver {@link #atualizarEstadosSensores}); se não, é criado. */
    public void registrarLeitura(LeituraTelemetria l, Alerta alertaOuNull) {
        Severidade sev = alertaOuNull == null ? null : alertaOuNull.severidade();
        CartaoSensor cartao = cartoes.get(l.sensorId());
        if (cartao == null) {
            cartao = criarCartao(l.sensorId(), l.tipo());
            cartao.definirEstado(app.EstadoSensor.EXECUTANDO, controleHabilitado);
        }
        cartao.atualizar(l, sev);
        grafico(l.sensorId(), l.tipo()).adicionar(l.momento(), l.valor(), sev);   // guarda mesmo escondido

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
     * Mostra um cartão para CADA sensor disponível — inclusive com o sistema parado — e o estado
     * de cada um (EDT). Cria os que faltam e, se a configuração mudou, remove os que não existem mais.
     *
     * @param controleAtivo {@code false} com o sistema pausado no geral ou encerrando: os botões
     *                      dos cartões aparecem, mas desabilitados
     */
    public void atualizarEstadosSensores(java.util.Map<String, TipoSensor> disponiveis,
                                         java.util.Map<String, app.EstadoSensor> estados, boolean controleAtivo) {
        controleHabilitado = controleAtivo;
        boolean mudou = cartoes.keySet().retainAll(disponiveis.keySet());
        if (mudou && faseAtual == app.FaseSistema.PARADO) {
            // sensor removido na aba Configurações: o gráfico guardado dele também sai (um sensor
            // criado depois com o mesmo id começa sem o histórico do antigo)
            graficos.keySet().retainAll(disponiveis.keySet());
        }
        for (Map.Entry<String, TipoSensor> d : disponiveis.entrySet()) {
            if (!cartoes.containsKey(d.getKey())) {
                criarCartao(d.getKey(), d.getValue());
                mudou = true;
            }
        }
        if (mudou) {
            mostrarPagina();                              // páginas recalculadas; fica na atual se ainda existir
        }
        cartoes.forEach((id, c) -> c.definirEstado(estados.getOrDefault(id, app.EstadoSensor.DESLIGADO), controleAtivo));
        trocaVazio.show(areaSensores, cartoes.isEmpty() ? "vazio" : "grade");
        if (sensorDoGrafico != null && !disponiveis.containsKey(sensorDoGrafico)) {
            fecharGrafico();
        }
        atualizarPeriodoGrafico();
        grade.revalidate();
        grade.repaint();
    }

    /** Faixa do sensor; sem faixa própria informada, a padrão do tipo. */
    private RegraExibida regraDe(String id, TipoSensor tipo) {
        RegraExibida propria = regrasDosSensores.get(id);
        if (propria != null && propria.tipo() == tipo) {
            return propria;
        }
        return regras.getOrDefault(tipo, new RegraExibida(tipo, 0, 100, Severidade.MEDIA));
    }

    /**
     * Faixa de cada sensor (EDT). Cartões e gráficos que já existem passam a mostrar a faixa
     * nova (o histórico do gráfico é mantido); os criados depois já nascem com ela.
     */
    public void definirRegrasDosSensores(Map<String, RegraExibida> porSensor) {
        if (porSensor.equals(regrasDosSensores)) {
            return;                                  // chamado a cada 1 s: só trabalha quando muda
        }
        regrasDosSensores.clear();
        regrasDosSensores.putAll(porSensor);
        cartoes.forEach((id, c) -> c.definirRegra(regraDe(id, c.tipo())));
        graficos.forEach((id, g) -> {
            CartaoSensor c = cartoes.get(id);
            if (c != null) {
                g.definirRegra(regraDe(id, c.tipo()));
            }
        });
    }

    private CartaoSensor criarCartao(String id, TipoSensor tipo) {
        RegraExibida r = regraDe(id, tipo);
        CartaoSensor novo = new CartaoSensor(id, tipo, r, aoPedirAcaoSensor, this::alternarGrafico);
        novo.marcarGraficoAberto(id.equals(sensorDoGrafico));
        cartoes.put(id, novo);                   // TreeMap: ordem de id
        mostrarPagina();
        return novo;
    }

    // ================================================================== paginação

    /**
     * Mostra na grade só os cartões da página atual (até {@link #CARTOES_POR_PAGINA}), em ordem de
     * id. Os outros cartões continuam existindo e recebendo leituras; trocar de página não recria
     * nada nem perde histórico. EDT.
     */
    private void mostrarPagina() {
        java.util.List<CartaoSensor> todos = new java.util.ArrayList<>(cartoes.values());
        app.Paginacao p = new app.Paginacao(todos.size(), CARTOES_POR_PAGINA);
        pagina = p.ajustar(pagina);
        grade.removeAll();
        todos.subList(p.inicio(pagina), p.fim(pagina)).forEach(grade::add);
        paginador.setVisible(p.necessaria());
        // paginando, a grade reserva as 4 colunas: a última página (com menos cartões) mantém a largura deles
        grade.colunasMinimas = p.necessaria() ? CARTOES_POR_PAGINA : 1;
        infoPagina.setText(p.descricao(pagina));
        paginaAnterior.setEnabled(pagina > 0);
        proximaPagina.setEnabled(pagina < p.paginas() - 1);
        trocaVazio.show(areaSensores, todos.isEmpty() ? "vazio" : "grade");
        grade.revalidate();
        grade.repaint();
        revalidate();
    }

    /** Botões ‹ Anterior / Próxima › (EDT). O gráfico aberto continua aberto. */
    private void irParaPagina(int nova) {
        pagina = nova;
        mostrarPagina();
    }

    /** Página atual, a partir de 0 (para testes de tela e para a janela). */
    public int paginaAtual() {
        return pagina;
    }

    // ================================================================== área "Ver gráfico"

    private final Map<String, GraficoSensor> graficos = new java.util.HashMap<>();
    private final JPanel areaGrafico = Componentes.transparente(new BorderLayout(0, Medida.ESP_1));
    private final JPanel lugarDoGrafico = Componentes.transparente(new BorderLayout());
    private final JLabel tituloGrafico = Componentes.rotulo("", Fonte.CORPO_NEGRITO, Cor.TEXTO);
    private final JLabel periodoGrafico = Componentes.rotulo("");
    private final javax.swing.ButtonGroup grupoJanela = new javax.swing.ButtonGroup();
    private String sensorDoGrafico;            // null = área escondida
    private long janelaGraficoMs;              // 0 = a execução inteira
    private app.FaseSistema faseAtual = app.FaseSistema.PARADO;
    private boolean historicoDoLog;

    private JPanel criarAreaGrafico() {
        JPanel cabecalho = Componentes.transparente(new BorderLayout(Medida.ESP_3, 0));
        JPanel titulos = Componentes.transparente(new FlowLayout(FlowLayout.LEFT, Medida.ESP_2, 0));
        tituloGrafico.setIcon(Icones.de(Icone.MONITORAMENTO, Medida.ICONE_PEQ, Cor.TEXTO_SECUNDARIO));
        tituloGrafico.setIconTextGap(Medida.ESP_2);
        titulos.add(tituloGrafico);
        titulos.add(periodoGrafico);
        JPanel acoes = Componentes.transparente(new FlowLayout(FlowLayout.RIGHT, Medida.ESP_1, 0));
        acoes.add(Componentes.rotulo("Janela"));
        Object[][] janelas = {{"Tudo", 0L}, {"5 min", 300_000L}, {"1 min", 60_000L}, {"30 s", 30_000L}};
        for (Object[] j : janelas) {
            javax.swing.JToggleButton b = chip((String) j[0]);
            long ms = (Long) j[1];
            b.addActionListener(e -> {
                janelaGraficoMs = ms;
                GraficoSensor g = sensorDoGrafico == null ? null : graficos.get(sensorDoGrafico);
                if (g != null) {
                    g.definirJanela(ms);
                }
            });
            grupoJanela.add(b);
            acoes.add(b);
            b.setSelected(ms == 0);
        }
        JButton fechar = Componentes.botaoPequeno("Fechar", Componentes.Estilo.SECUNDARIO);
        fechar.addActionListener(e -> fecharGrafico());
        acoes.add(javax.swing.Box.createHorizontalStrut(Medida.ESP_2));
        acoes.add(fechar);
        cabecalho.add(titulos, BorderLayout.WEST);
        cabecalho.add(acoes, BorderLayout.EAST);

        areaGrafico.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Cor.BORDA),
                Componentes.margem(Medida.ESP_3, 0, 0, 0)));
        areaGrafico.add(cabecalho, BorderLayout.NORTH);
        areaGrafico.add(lugarDoGrafico, BorderLayout.CENTER);
        Dimension altura = new Dimension(0, Medida.ALTURA_AREA_GRAFICO);
        areaGrafico.setPreferredSize(altura);
        areaGrafico.setMinimumSize(altura);
        areaGrafico.setVisible(false);
        return areaGrafico;
    }

    /** Gráfico (com o histórico guardado) de um sensor; criado na primeira leitura. */
    private GraficoSensor grafico(String id, TipoSensor tipo) {
        return graficos.computeIfAbsent(id, k -> new GraficoSensor(regraDe(id, tipo), null));
    }

    /** Botão "Ver gráfico" do cartão: abre o gráfico deste sensor ou fecha, se já é ele (EDT). */
    public void alternarGrafico(String sensorId) {
        if (sensorId.equals(sensorDoGrafico)) {
            fecharGrafico();
            return;
        }
        CartaoSensor c = cartoes.get(sensorId);
        TipoSensor tipo = c != null ? c.tipo() : null;
        GraficoSensor g = tipo != null ? grafico(sensorId, tipo) : graficos.get(sensorId);
        if (g == null) {
            return;
        }
        g.definirJanela(janelaGraficoMs);
        lugarDoGrafico.removeAll();
        lugarDoGrafico.add(g, BorderLayout.CENTER);
        sensorDoGrafico = sensorId;
        tituloGrafico.setText("Gráfico · " + sensorId + (tipo == null ? "" : " (" + tipo.nome() + ")"));
        atualizarPeriodoGrafico();
        cartoes.forEach((id, cs) -> cs.marcarGraficoAberto(id.equals(sensorId)));
        areaGrafico.setVisible(true);
        grade.maxLinhasVisiveis = 1;
        revalidate();
        repaint();
    }

    public void fecharGrafico() {
        sensorDoGrafico = null;
        lugarDoGrafico.removeAll();
        areaGrafico.setVisible(false);
        grade.maxLinhasVisiveis = 2;
        cartoes.values().forEach(cs -> cs.marcarGraficoAberto(false));
        revalidate();
        repaint();
    }

    /** "ao vivo" × "última execução · período (lida do log)" ao lado do título do gráfico. */
    private void atualizarPeriodoGrafico() {
        GraficoSensor g = sensorDoGrafico == null ? null : graficos.get(sensorDoGrafico);
        if (g == null) {
            return;
        }
        java.time.Instant ini = g.primeiro();
        java.time.Instant fim = g.ultimo();
        if (faseAtual != app.FaseSistema.PARADO) {
            periodoGrafico.setText(ini == null ? "· ao vivo — aguardando leituras deste sensor"
                    : "· ao vivo desde " + FormatoMensagens.hora(ini));
        } else {
            periodoGrafico.setText(ini == null ? "· sem leituras deste sensor na última execução"
                    : "· última execução " + FormatoMensagens.dataHora(ini) + " – " + FormatoMensagens.hora(fim)
                    + (historicoDoLog ? " (lida do log)" : ""));
        }
    }

    /** Fase do sistema mudou (EDT). */
    public void aoMudarFase(app.FaseSistema fase) {
        faseAtual = fase;
        atualizarPeriodoGrafico();
    }

    /** Antes de uma execução nova: os gráficos passam a mostrar só ela (EDT). */
    public void novaExecucao() {
        graficos.values().forEach(GraficoSensor::limpar);
        historicoDoLog = false;
        atualizarPeriodoGrafico();
    }

    /**
     * Última execução lida do log, ao abrir o programa (EDT): alimenta os gráficos e mostra a
     * última leitura de cada sensor no cartão. Não mexe na tabela ao vivo nem nos alertas.
     */
    public void carregarHistorico(app.HistoricoExecucao historico) {
        if (historico.vazio()) {
            return;
        }
        graficos.values().forEach(GraficoSensor::limpar);
        Map<String, app.LeituraRegistrada> ultimas = new LinkedHashMap<>();
        for (app.LeituraRegistrada r : historico.leituras()) {
            LeituraTelemetria l = r.leitura();
            grafico(l.sensorId(), l.tipo()).adicionar(l.momento(), l.valor(), r.severidade());
            ultimas.put(l.sensorId(), r);
        }
        ultimas.forEach((id, r) -> {
            CartaoSensor c = cartoes.get(id);
            if (c != null) {
                c.atualizar(r.leitura(), r.severidade());
            }
        });
        historicoDoLog = true;
        atualizarPeriodoGrafico();
    }

    /** Já há leituras em algum gráfico (desta sessão)? */
    public boolean temLeiturasNosGraficos() {
        return graficos.values().stream().anyMatch(g -> g.total() > 0);
    }

    /** Botão pequeno de alternância para a janela de tempo (selecionado = destaque). */
    private static javax.swing.JToggleButton chip(String texto) {
        javax.swing.JToggleButton b = new javax.swing.JToggleButton(texto) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                var forma = new java.awt.geom.RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1,
                        getHeight(), getHeight());
                if (isSelected()) {
                    g2.setColor(Tema.translucida(Cor.PRIMARIA, 40));
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
        Runnable cor = () -> b.setForeground(b.isSelected() ? Cor.PRIMARIA_TEXTO : Cor.TEXTO_SECUNDARIO);
        b.getModel().addChangeListener(e -> cor.run());
        cor.run();
        return b;
    }

    /** O pedido feito pelo cartão deste sensor terminou (EDT). */
    public void liberarSensor(String sensorId) {
        CartaoSensor c = cartoes.get(sensorId);
        if (c != null) {
            c.liberar();
        }
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

    /**
     * Grade de cartões que calcula o número de colunas pela largura disponível e
     * mostra no máximo 2 linhas antes de rolar.
     */
    private static final class GradeCartoes extends Componentes.PainelRolavel {
        private static final long serialVersionUID = 1L;
        private final GridLayout layout = new GridLayout(0, 4, Medida.ESP_3, Medida.ESP_3);
        /**
         * Com a paginação (4 por página) a grade costuma ter 1 linha; em janela estreita os 4 podem
         * quebrar em 2. Com o gráfico aberto, 1 linha (as tabelas de baixo não ficam espremidas).
         */
        private int maxLinhasVisiveis = 2;

        GradeCartoes() {
            super(null);
            setLayout(layout);
        }

        /** Colunas reservadas mesmo com menos cartões (paginando = 4). */
        private int colunasMinimas = 1;

        private int colunas(int largura) {
            int n = Math.max(colunasMinimas, Math.max(1, getComponentCount()));
            int cabem = Math.max(1, (largura + Medida.ESP_3) / (LARGURA_MIN_CARTAO + Medida.ESP_3));
            return Math.min(n, cabem);
        }

        private int linhas(int largura) {
            int cols = colunas(largura);
            return (getComponentCount() + cols - 1) / cols;
        }

        @Override
        public void doLayout() {
            layout.setColumns(colunas(getWidth()));
            super.doLayout();
        }

        @Override
        public Dimension getPreferredSize() {
            int largura = getParent() != null && getParent().getWidth() > 0 ? getParent().getWidth() : 1000;
            int l = Math.max(1, linhas(largura));
            return new Dimension(largura, l * ALTURA_CARTAO + (l - 1) * Medida.ESP_3);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            int largura = getParent() != null && getParent().getWidth() > 0 ? getParent().getWidth() : 1000;
            int l = Math.max(1, Math.min(maxLinhasVisiveis, linhas(largura)));
            return new Dimension(largura, l * ALTURA_CARTAO + (l - 1) * Medida.ESP_3);
        }
    }
}
