package app.ui.telas;

import app.ConfiguracaoSimulacao;
import app.ControladorMonitoramento;
import app.FaseSistema;
import app.FormatoMensagens;
import app.RegraExibida;
import app.ui.tema.Componentes;
import app.ui.tema.Componentes.Estilo;
import app.ui.tema.Icones;
import app.ui.tema.Icones.Icone;
import app.ui.tema.MensagemErro;
import domain.DegrauDeSeveridade;
import domain.Severidade;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import static app.ui.tema.Tema.Cor;
import static app.ui.tema.Tema.Medida;

/**
 * ABA REGRAS E SENSORES
 * <pre>
 * ┌ Regras de anomalia (somente leitura) ──────────┐ ┌ Simulação ───────────────────┐
 * │ "A severidade depende de quanto passou..."     │ │ Sensores por tipo   [ 1 ⇕ ]  │
 * │ Tipo | Faixa | BAIXA | MEDIA | ALTA | CRITICA  │ │ Intervalo (ms)      [200 ⇕]  │
 * │      |       | até 4 | até 12| até 24| > 24   │ │                              │
 * │ "Definidas no AnalisadorDeAnomalias (JUnit)"   │ │                              │
 * └────────────────────────────────────────────────┘ │ Consumidores        [ 1 ⇕ ]  │
 *                                                    │ "Só com o sistema parado" [Aplicar]
 *                                                    └──────────────────────────────┘
 * </pre>
 * A View NÃO valida regra de negócio: só repassa ao controller e mostra o erro dele.
 */
public final class PainelRegras extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int PRIMEIRA_COLUNA_SEVERIDADE = 2;
    private static final String SEM_VALOR = "—";

    private final ControladorMonitoramento controlador;
    private final JSpinner sensores;
    private final JSpinner intervalo;
    private final JSpinner consumidores;
    private final JButton aplicar = Componentes.botao("Aplicar", Icone.OK, Estilo.PRIMARIO);
    private final JLabel aviso = Componentes.rotulo("Edição liberada: o sistema está parado.");

    public PainelRegras(ControladorMonitoramento controlador) {
        this.controlador = controlador;
        setOpaque(false);
        setLayout(new GridBagLayout());

        // ---- regras
        String[] colunas = {"Tipo", "Faixa aceita",
                Severidade.BAIXA.name(), Severidade.MEDIA.name(), Severidade.ALTA.name(), Severidade.CRITICA.name()};
        DefaultTableModel modelo = new DefaultTableModel(colunas, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int l, int c) {
                return false;
            }
        };
        for (RegraExibida r : controlador.regras()) {
            Object[] linha = new Object[colunas.length];
            linha[0] = r.tipo().nome();
            linha[1] = FormatoMensagens.decimal(r.minimo()) + " a " + FormatoMensagens.valorComUnidade(r.maximo(), r.tipo());
            for (Severidade s : Severidade.values()) {
                linha[PRIMEIRA_COLUNA_SEVERIDADE + s.ordinal()] = textoDoDegrau(r, s);
            }
            modelo.addRow(linha);
        }
        JTable tabela = new JTable(modelo);
        for (Severidade s : Severidade.values()) {
            tabela.getColumnModel().getColumn(PRIMEIRA_COLUNA_SEVERIDADE + s.ordinal()).setCellRenderer(new CelulaDegrau(s));
        }
        JScrollPane rolagemRegras = Componentes.tabela(tabela);   // instala o cabeçalho e as células do Tema
        for (int c = 0; c < tabela.getColumnCount(); c++) {
            ajustarLarguraAoConteudo(tabela, c);
        }
        JPanel corpoRegras = Componentes.transparente(new BorderLayout(0, Medida.ESP_2));
        corpoRegras.add(Componentes.paragrafo("A severidade depende de quanto a leitura passou do limite da faixa "
                + "(acima do máximo ou abaixo do mínimo), na unidade do sensor."), BorderLayout.NORTH);
        corpoRegras.add(rolagemRegras, BorderLayout.CENTER);
        JLabel origem = Componentes.rotulo("Regras definidas no AnalisadorDeAnomalias e cobertas por testes JUnit.");
        origem.setIcon(Icones.de(Icone.OK, Medida.ICONE_PEQ, Cor.NORMAL));
        origem.setIconTextGap(Medida.ESP_2);
        corpoRegras.add(origem, BorderLayout.SOUTH);

        // ---- simulação
        ConfiguracaoSimulacao atual = controlador.configuracao();
        sensores = Componentes.spinner(new JSpinner(new SpinnerNumberModel(atual.sensoresPorTipo(), 1, 10, 1)));
        intervalo = Componentes.spinner(new JSpinner(new SpinnerNumberModel(atual.intervaloMs(), 10, 5000, 10)));
        consumidores = Componentes.spinner(new JSpinner(new SpinnerNumberModel(atual.consumidores(), 1, 4, 1)));

        JPanel form = Componentes.transparente(new GridBagLayout());
        adicionarCampo(form, 0, "Sensores por tipo", sensores, "1 a 10 — total = 4 tipos × este valor");
        adicionarCampo(form, 1, "Intervalo entre leituras (ms)", intervalo, "menor = mais estresse na fila");
        adicionarCampo(form, 2, "Consumidores da fila", consumidores, "threads que processam leituras");
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 3;
        c.gridx = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(Medida.ESP_6, 0, 0, 0);
        JPanel rodape = Componentes.transparente(new FlowLayout(FlowLayout.LEFT, 0, 0));
        rodape.add(aplicar);
        form.add(rodape, c);
        c.gridy = 4;
        c.insets = new Insets(Medida.ESP_2, 0, 0, 0);
        form.add(aviso, c);
        c.gridy = 5;
        c.weighty = 1;                       // sobra de altura vai para baixo: formulário fica no topo
        c.fill = GridBagConstraints.VERTICAL;
        form.add(javax.swing.Box.createGlue(), c);
        aplicar.addActionListener(e -> aplicar());

        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.BOTH;
        g.weighty = 1;
        g.weightx = 0.6;
        g.insets = new Insets(0, 0, 0, Medida.ESP_3);
        add(Componentes.secao("Regras de anomalia", Icone.REGRAS, corpoRegras), g);
        g.gridx = 1;
        g.weightx = 0.4;
        g.insets = new Insets(0, 0, 0, 0);
        add(Componentes.secao("Simulação", Icone.SENSOR, form), g);
    }

    /** Habilita a edição só com o sistema PARADO (EDT). */
    public void aoMudarFase(FaseSistema fase) {
        boolean parado = fase == FaseSistema.PARADO;
        sensores.setEnabled(parado);
        intervalo.setEnabled(parado);
        consumidores.setEnabled(parado);
        aplicar.setEnabled(parado);
        aviso.setText(parado ? "Edição liberada: o sistema está parado."
                : "Pare o sistema para alterar a simulação.");
        aviso.setForeground(parado ? Cor.TEXTO_SECUNDARIO : Cor.SEV_MEDIA);
    }

    private void aplicar() {
        try {
            controlador.aplicarConfiguracao(new ConfiguracaoSimulacao(
                    (Integer) sensores.getValue(), (Integer) intervalo.getValue(), (Integer) consumidores.getValue()));
            aviso.setText("Configuração aplicada. Vale a partir do próximo Iniciar.");
            aviso.setForeground(Cor.NORMAL);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Componentes.mostrarErro(this, new MensagemErro(
                    "Configuração não aplicada",
                    ex.getMessage() + ".",
                    "Ajuste o valor indicado e clique em Aplicar novamente.",
                    null));
        }
    }

    /**
     * Texto da célula de uma severidade: "até 4,0" ou "> 24,0" (desvio além do limite).
     * Regra sem escala (severidade fixa): "qualquer" na coluna dela e "—" nas outras.
     */
    private static String textoDoDegrau(RegraExibida r, Severidade s) {
        if (r.escala().isEmpty()) {
            return s == r.severidade() ? "qualquer" : SEM_VALOR;
        }
        DegrauDeSeveridade anterior = null;
        for (DegrauDeSeveridade d : r.escala()) {
            if (d.severidade() == s) {
                return d.semLimite()
                        ? "> " + FormatoMensagens.decimalCompacto(anterior == null ? 0 : anterior.desvioMaximo())
                        : "até " + FormatoMensagens.decimalCompacto(d.desvioMaximo());
            }
            anterior = d;
        }
        return SEM_VALOR;
    }

    /**
     * Largura mínima da coluna = a maior largura preferida entre o cabeçalho e as células,
     * medida pelos próprios renderizadores (fonte, ponto e margens do Tema) — sem pixel fixo.
     */
    private static void ajustarLarguraAoConteudo(JTable tabela, int coluna) {
        TableColumn col = tabela.getColumnModel().getColumn(coluna);
        TableCellRenderer cab = col.getHeaderRenderer() != null
                ? col.getHeaderRenderer() : tabela.getTableHeader().getDefaultRenderer();
        int largura = cab.getTableCellRendererComponent(tabela, col.getHeaderValue(), false, false, -1, coluna)
                .getPreferredSize().width;
        for (int l = 0; l < tabela.getRowCount(); l++) {
            largura = Math.max(largura, tabela.prepareRenderer(tabela.getCellRenderer(l, coluna), l, coluna)
                    .getPreferredSize().width);
        }
        int total = largura + tabela.getIntercellSpacing().width;
        col.setMinWidth(total);
        col.setPreferredWidth(total);
    }

    /** Célula de uma coluna de severidade: texto na cor da severidade, com ponto (nunca só cor). */
    private static final class CelulaDegrau extends Componentes.CelulaSeveridade {
        private static final long serialVersionUID = 1L;
        private final Severidade severidade;

        CelulaDegrau(Severidade severidade) {
            this.severidade = severidade;
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foco,
                                                                int l, int c) {
            super.getTableCellRendererComponent(t, severidade, sel, foco, l, c);   // cor + ponto da severidade
            setText(String.valueOf(v));
            if (SEM_VALOR.equals(v)) {
                setForeground(Cor.TEXTO_DESATIVADO);
                setIcon(null);
            }
            return this;
        }
    }

    private static void adicionarCampo(JPanel form, int linha, String rotulo, JSpinner campo, String dica) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = linha;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(linha == 0 ? 0 : Medida.ESP_3, 0, 0, Medida.ESP_4);
        c.gridx = 0;
        c.weightx = 1;
        JPanel textos = Componentes.transparente(new BorderLayout());
        textos.add(Componentes.rotulo(rotulo, app.ui.tema.Tema.Fonte.CORPO, Cor.TEXTO),
                BorderLayout.NORTH);
        textos.add(Componentes.rotulo(dica), BorderLayout.SOUTH);
        form.add(textos, c);
        c.gridx = 1;
        c.weightx = 0;
        form.add(campo, c);
    }
}
