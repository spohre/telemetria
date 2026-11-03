package app.ui.telas;

import app.ControladorMonitoramento;
import app.FiltroAlertas;
import app.FormatoMensagens;
import app.ui.tema.Componentes;
import app.ui.tema.Componentes.Estilo;
import app.ui.tema.Icones.Icone;
import app.ui.tema.MensagemErro;
import domain.Alerta;
import domain.Severidade;
import domain.TipoSensor;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static app.ui.tema.Tema.Cor;
import static app.ui.tema.Tema.Medida;

/**
 * ABA HISTÓRICO DE ALERTAS
 * <pre>
 * Título: Histórico de alertas (banco de dados)
 * Filtros: Sensor [Todos ▾] Tipo [Todos ▾] Severidade [Todas ▾] Período [Tudo ▾]  [Filtrar] [Exportar extrato]
 * ┌ Nº | Data/hora | Sensor | Tipo | Valor | Regra violada | Severidade ┐
 * └ rodapé: "128 alertas encontrados no banco"  /  "Consultando o banco…"  ┘
 * </pre>
 * A consulta é DEMORADA (JDBC) → roda em SwingWorker; botões ficam desabilitados.
 * Mostra alertas de execuções anteriores — é a prova visual da persistência.
 */
public final class PainelHistorico extends JPanel {

    private static final long serialVersionUID = 1L;

    private final ControladorMonitoramento controlador;
    private final JComboBox<String> comboSensor = new JComboBox<>(new String[]{"Todos"});
    private final JComboBox<Object> comboTipo = new JComboBox<>();
    private final JComboBox<Object> comboSeveridade = new JComboBox<>();
    private final JComboBox<String> comboPeriodo = new JComboBox<>(new String[]{"Tudo", "Última hora", "Últimas 24 h"});
    private final JButton filtrar = Componentes.botao("Filtrar", Icone.FILTRO, Estilo.PRIMARIO);
    private final JLabel situacao = Componentes.rotulo("Clique em Filtrar para consultar o banco.");
    private final DefaultTableModel modelo = new DefaultTableModel(
            new Object[]{"Nº", "Data/hora", "Sensor", "Tipo", "Valor", "Regra violada", "Severidade"}, 0) {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(int l, int c) {
            return false;
        }
    };

    public PainelHistorico(ControladorMonitoramento controlador, Runnable aoExportarExtrato) {
        this.controlador = controlador;
        setOpaque(false);
        setLayout(new BorderLayout(0, Medida.ESP_3));

        comboTipo.addItem("Todos");
        for (TipoSensor t : TipoSensor.values()) {
            comboTipo.addItem(t);
        }
        comboSeveridade.addItem("Todas");
        for (Severidade s : Severidade.values()) {
            comboSeveridade.addItem(s);
        }

        comboSensor.setEditable(true);
        comboSensor.setToolTipText("Digite o id do sensor (ex.: TEMP-01) ou deixe 'Todos'");
        Componentes.combo(comboSensor);
        Componentes.combo(comboTipo);
        Componentes.combo(comboSeveridade);
        Componentes.combo(comboPeriodo);

        JPanel filtros = Componentes.transparente(new FlowLayout(FlowLayout.LEFT, Medida.ESP_2, 0));
        filtros.add(Componentes.rotulo("Sensor"));
        filtros.add(comboSensor);
        filtros.add(Componentes.rotulo("Tipo"));
        filtros.add(comboTipo);
        filtros.add(Componentes.rotulo("Severidade"));
        filtros.add(comboSeveridade);
        filtros.add(Componentes.rotulo("Período"));
        filtros.add(comboPeriodo);
        filtros.add(Box.createHorizontalStrut(Medida.ESP_2));
        filtros.add(filtrar);
        JButton exportar = Componentes.botao("Exportar extrato", Icone.EXPORTAR, Estilo.SECUNDARIO);
        exportar.addActionListener(e -> aoExportarExtrato.run());
        filtros.add(exportar);

        JTable tabela = new JTable(modelo);
        tabela.getColumnModel().getColumn(6).setCellRenderer(new Componentes.CelulaSeveridade());
        tabela.getColumnModel().getColumn(0).setPreferredWidth(50);
        tabela.getColumnModel().getColumn(1).setPreferredWidth(150);
        tabela.getColumnModel().getColumn(5).setPreferredWidth(220);

        JPanel corpo = Componentes.transparente(new BorderLayout(0, Medida.ESP_3));
        corpo.add(filtros, BorderLayout.NORTH);
        corpo.add(Componentes.tabela(tabela), BorderLayout.CENTER);
        situacao.setIcon(app.ui.tema.Icones.de(Icone.BANCO, Medida.ICONE_PEQ, Cor.TEXTO_SECUNDARIO));
        situacao.setIconTextGap(Medida.ESP_2);
        corpo.add(situacao, BorderLayout.SOUTH);

        add(Componentes.secao("Histórico de alertas (banco de dados)", Icone.HISTORICO, corpo), BorderLayout.CENTER);

        filtrar.addActionListener(e -> consultar());
    }

    /** Dispara a consulta em segundo plano. Chamar na EDT. */
    public void consultar() {
        FiltroAlertas filtro = filtroAtual();
        filtrar.setEnabled(false);
        situacao.setText("Consultando o banco…");

        new SwingWorker<List<Alerta>, Void>() {
            @Override
            protected List<Alerta> doInBackground() {
                return controlador.buscarAlertas(filtro);         // fora da EDT
            }

            @Override
            protected void done() {                                // de volta na EDT
                filtrar.setEnabled(true);
                try {
                    mostrar(get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    situacao.setText("A última consulta falhou — os dados exibidos podem estar desatualizados.");
                    Componentes.mostrarErro(PainelHistorico.this, new MensagemErro(
                            "Não foi possível consultar o histórico",
                            "O banco de dados não respondeu à consulta de alertas.",
                            "Confira se nenhum outro programa está usando o arquivo data/telemetria.mv.db "
                                    + "e se db.url em db.properties está correto. Depois clique em Filtrar de novo.",
                            ex.getCause()));
                }
            }
        }.execute();
    }

    /** Preenche a tabela (EDT). Público para permitir pré-visualização com dados fixos. */
    public void mostrar(List<Alerta> alertas) {
        modelo.setRowCount(0);
        for (Alerta a : alertas) {
            modelo.addRow(new Object[]{
                    a.id(), FormatoMensagens.dataHora(a.momentoLeitura()), a.sensorId(), a.tipo().nome(),
                    FormatoMensagens.valorComUnidade(a.valorLido(), a.tipo()), a.regraViolada(), a.severidade()});
        }
        situacao.setText(alertas.isEmpty()
                ? "Nenhum alerta encontrado com esses filtros."
                : FormatoMensagens.inteiro(alertas.size()) + " alertas encontrados no banco (inclui execuções anteriores).");
    }

    private FiltroAlertas filtroAtual() {
        Object s = comboSensor.getSelectedItem();
        String sensor = s == null || "Todos".equals(s) || s.toString().isBlank() ? null : s.toString().trim();
        TipoSensor tipo = comboTipo.getSelectedItem() instanceof TipoSensor t ? t : null;
        Severidade sev = comboSeveridade.getSelectedItem() instanceof Severidade v ? v : null;
        Duration periodo = switch (comboPeriodo.getSelectedIndex()) {
            case 1 -> Duration.ofHours(1);
            case 2 -> Duration.ofHours(24);
            default -> null;
        };
        return new FiltroAlertas(sensor, tipo, sev, periodo);
    }
}
