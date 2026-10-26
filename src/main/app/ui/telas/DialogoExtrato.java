package app.ui.telas;

import app.ControladorMonitoramento;
import app.ExtratoExecucao;
import app.FormatoMensagens;
import app.ui.tema.Componentes;
import app.ui.tema.Componentes.Estilo;
import app.ui.tema.Icones;
import app.ui.tema.Icones.Icone;
import app.ui.tema.MensagemErro;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.concurrent.ExecutionException;

import static app.ui.tema.Tema.Cor;
import static app.ui.tema.Tema.Fonte;
import static app.ui.tema.Tema.Medida;

/**
 * DIÁLOGO EXTRATO DA EXECUÇÃO (abre ao Parar)
 * <pre>
 * [LOG] Extrato da execução                       0:00:28
 * ┌ Produzidas ┐┌ Processadas ┐┌ Perdidas ┐┌ Alertas ┐   ← 4 indicadores
 * ┌ texto do extrato (monoespaçado, mesmo conteúdo do arquivo) ┐
 * [Exportar para arquivo]                         [Fechar]
 * </pre>
 * O texto vem de FormatoMensagens.extrato — a View só exibe.
 */
public final class DialogoExtrato {

    private DialogoExtrato() {
    }

    /** Conteúdo do diálogo (separado para pré-visualização). */
    public static JPanel conteudo(ExtratoExecucao e, ControladorMonitoramento controlador, Runnable fechar) {
        JPanel raiz = new JPanel(new BorderLayout(0, Medida.ESP_4));
        raiz.setBackground(Cor.FUNDO);
        raiz.setBorder(Componentes.margem(Medida.ESP_6, Medida.ESP_6, Medida.ESP_4, Medida.ESP_6));

        JPanel topo = Componentes.transparente(new BorderLayout());
        JLabel titulo = Componentes.titulo("Extrato da execução");
        titulo.setIcon(Icones.de(Icone.LOG, Medida.ICONE, Cor.PRIMARIA));
        titulo.setIconTextGap(Medida.ESP_2);
        topo.add(titulo, BorderLayout.WEST);
        long seg = java.time.Duration.between(e.inicio(), e.fim()).toSeconds();
        topo.add(Componentes.rotulo(FormatoMensagens.dataHora(e.inicio()) + "  ·  duração "
                + FormatoMensagens.duracao(seg), Fonte.DADO, Cor.TEXTO_SECUNDARIO), BorderLayout.EAST);

        JPanel indicadores = Componentes.transparente(new GridLayout(1, 4, Medida.ESP_3, 0));
        indicadores.add(indicador("Produzidas", FormatoMensagens.inteiro(e.leiturasProduzidas()), Cor.TEXTO));
        indicadores.add(indicador("Processadas", FormatoMensagens.inteiro(e.leiturasProcessadas()), Cor.TEXTO));
        indicadores.add(indicador("Perdidas", FormatoMensagens.inteiro(e.leiturasPerdidas()),
                e.leiturasPerdidas() == 0 ? Cor.NORMAL : Cor.PERIGO));
        indicadores.add(indicador("Alertas", FormatoMensagens.inteiro(e.totalAlertas()), Cor.SEV_ALTA));

        JTextArea texto = new JTextArea(FormatoMensagens.extrato(e));
        texto.setFont(Fonte.DADO);
        texto.setForeground(Cor.TEXTO);
        texto.setBackground(Cor.SUPERFICIE);
        texto.setEditable(false);
        texto.setBorder(Componentes.margem(Medida.ESP_3, Medida.ESP_4, Medida.ESP_3, Medida.ESP_4));

        JPanel centro = Componentes.transparente(new BorderLayout(0, Medida.ESP_4));
        centro.add(indicadores, BorderLayout.NORTH);
        centro.add(texto, BorderLayout.CENTER);

        JButton exportar = Componentes.botao("Exportar para arquivo", Icone.EXPORTAR, Estilo.SECUNDARIO);
        JButton botaoFechar = Componentes.botao("Fechar", null, Estilo.PRIMARIO);
        JLabel situacao = Componentes.rotulo("");
        botaoFechar.addActionListener(ev -> fechar.run());
        exportar.addActionListener(ev -> exportar(raiz, e, controlador, exportar, situacao));

        JPanel rodape = Componentes.transparente(new BorderLayout());
        JPanel esq = Componentes.transparente(new FlowLayout(FlowLayout.LEFT, Medida.ESP_2, 0));
        esq.add(exportar);
        esq.add(situacao);
        rodape.add(esq, BorderLayout.WEST);
        rodape.add(botaoFechar, BorderLayout.EAST);

        raiz.add(topo, BorderLayout.NORTH);
        raiz.add(centro, BorderLayout.CENTER);
        raiz.add(rodape, BorderLayout.SOUTH);
        return raiz;
    }

    public static void mostrar(Component pai, ExtratoExecucao e, ControladorMonitoramento controlador) {
        Window dono = javax.swing.SwingUtilities.getWindowAncestor(pai);
        JDialog d = new JDialog(dono, "Extrato da execução", Dialog.ModalityType.APPLICATION_MODAL);
        d.setContentPane(conteudo(e, controlador, d::dispose));
        d.pack();
        d.setLocationRelativeTo(pai);
        d.setVisible(true);
    }

    private static void exportar(Component pai, ExtratoExecucao e, ControladorMonitoramento controlador,
                                 JButton botao, JLabel situacao) {
        botao.setEnabled(false);
        situacao.setText("Gravando arquivo…");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return controlador.exportarExtrato(e);
            }

            @Override
            protected void done() {
                botao.setEnabled(true);
                try {
                    situacao.setText("Salvo em " + get());
                    situacao.setForeground(Cor.NORMAL);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    situacao.setText("");
                    Componentes.mostrarErro(pai, new MensagemErro(
                            "Extrato não foi salvo",
                            "Não foi possível gravar o arquivo de extrato na pasta data/.",
                            "Verifique se a pasta data/ existe e se você tem permissão de escrita nela, "
                                    + "depois clique em Exportar novamente.",
                            ex.getCause()));
                }
            }
        }.execute();
    }

    private static JPanel indicador(String rotulo, String valor, java.awt.Color cor) {
        JPanel c = Componentes.cartao();
        c.setLayout(new BorderLayout(0, Medida.ESP_1));
        c.add(Componentes.rotulo(rotulo.toUpperCase(), Fonte.ROTULO_NEGRITO, Cor.TEXTO_SECUNDARIO), BorderLayout.NORTH);
        c.add(Componentes.rotulo(valor, Fonte.VALOR, cor), BorderLayout.CENTER);
        return c;
    }
}
