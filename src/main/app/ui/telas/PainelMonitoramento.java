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
 * ┌ Sensores ─────────────────────────────────────────────────────────────────────────┐
 * │ [TEMP-01 92,5 °C] [VEL-01 64 km/h] [PRES-01 3,8 bar] [UMID-01 30 %]  (colunas      │
 * │                                                       se ajustam à largura; 2 linhas│
 * │                                                       visíveis, depois rola)       │
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
    private static final int LARGURA_MIN_CARTAO = 230;
    private static final int ALTURA_CARTAO = 128;

    private final Map<String, CartaoSensor> cartoes = new java.util.TreeMap<>();   // ordenados por id
    private final Map<TipoSensor, RegraExibida> regras = new LinkedHashMap<>();
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

    public PainelMonitoramento(List<RegraExibida> regrasExibidas, Runnable aoLimparAlertas) {
        regrasExibidas.forEach(r -> regras.put(r.tipo(), r));
        setOpaque(false);
        setLayout(new GridBagLayout());

        // ---- sensores: mensagem de vazio × grade
        JLabel vazio = Componentes.rotulo("Nenhuma leitura ainda — clique em Iniciar para ligar os sensores.",
                Fonte.CORPO, Cor.TEXTO_SECUNDARIO);
        vazio.setHorizontalAlignment(SwingConstants.CENTER);
        vazio.setIcon(Icones.de(Icone.SENSOR, Medida.ICONE_GRANDE, Cor.TEXTO_DESATIVADO));
        vazio.setIconTextGap(Medida.ESP_3);
        areaSensores.add(vazio, "vazio");
        areaSensores.add(Componentes.rolagem(grade), "grade");
        JPanel secSensores = Componentes.secao("Sensores", Icone.SENSOR, areaSensores);

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

    /** Leitura processada (EDT). Cria o cartão do sensor na primeira vez que ele aparece. */
    public void registrarLeitura(LeituraTelemetria l, Alerta alertaOuNull) {
        Severidade sev = alertaOuNull == null ? null : alertaOuNull.severidade();
        CartaoSensor cartao = cartoes.computeIfAbsent(l.sensorId(), id -> {
            RegraExibida r = regras.getOrDefault(l.tipo(), new RegraExibida(l.tipo(), 0, 100, Severidade.MEDIA));
            CartaoSensor novo = new CartaoSensor(id, l.tipo(), r);
            int posicao = (int) cartoes.keySet().stream().filter(k -> k.compareTo(id) < 0).count();
            grade.add(novo, posicao);                // mantém a grade em ordem alfabética
            trocaVazio.show(areaSensores, "grade");
            revalidate();
            return novo;
        });
        cartao.atualizar(l, sev);

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

        GradeCartoes() {
            super(null);
            setLayout(layout);
        }

        private int colunas(int largura) {
            int n = Math.max(1, getComponentCount());
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
            int l = Math.max(1, Math.min(2, linhas(largura)));
            return new Dimension(largura, l * ALTURA_CARTAO + (l - 1) * Medida.ESP_3);
        }
    }
}
