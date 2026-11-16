package app.ui.telas;

import app.ConfiguracaoSimulacao;
import app.ControladorMonitoramento;
import app.FalhaDeOperacao;
import app.FaseSistema;
import app.FormatoMensagens;
import app.ui.tema.Componentes;
import app.ui.tema.Componentes.Estilo;
import app.ui.tema.Icones;
import app.ui.tema.Icones.Icone;
import app.ui.tema.MensagemErro;
import domain.ConfiguracaoSensores;
import domain.RegraDeAnomalia;
import domain.SensorConfigurado;
import domain.TipoSensor;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static app.ui.tema.Tema.Cor;
import static app.ui.tema.Tema.Fonte;
import static app.ui.tema.Tema.Medida;

/**
 * ABA CONFIGURAÇÕES — lista com um grupo por tipo; a seta abre o grupo e mostra os sensores dele,
 * recuados para a direita.
 * <pre>
 * ┌ Configurações ─────────────────────────────────────────── ● Edição liberada: o sistema está parado ┐
 * │ Intervalo entre leituras (ms) [200 ⇕] [Salvar intervalo]   vale para todos os sensores             │
 * │ TIPO / SENSOR              FAIXA ACEITA               SEVERIDADE ALÉM DO LIMITE                     │
 * │ ⌄ 🌡 Temperatura (°C)       mín [0,0]  máx [80,0]     até 4,0 BAIXA · 12,0 MEDIA · …  [Salvar padrão] [+ Adicionar sensor] │
 * │      padrão p/ novos · 2 sensores                                                                  │
 * │        TEMP-01             mín [0,0]  máx [80,0]     até 4,0 BAIXA · …    [Salvar faixa] [Remover]  │
 * │        TEMP-02  própria    mín [10,0] máx [60,0]     até 2,5 BAIXA · …    [Salvar faixa] [Remover]  │
 * │        ✓ Faixa de TEMP-02 salva: 10,0 a 60,0 °C                                                    │
 * │ ──────────────────────────────────────────────────────────────────────────────────────────────── │
 * │ › ◔ Velocidade (km/h)      mín [0,0]  máx [120,0]    …                     [Salvar padrão] [+ Adicionar sensor] │
 * │ › ⊙ Pressão (bar) …                                                                                │
 * │ › 💧 Umidade (%) …                                                                                  │
 * └──────────────────────────────────────────────────────────────────────────────────────────────────┘
 * ┌ Simulação ── Consumidores da fila [1 ⇕] [Aplicar]   Vale a partir do próximo Iniciar ┐
 * </pre>
 *
 * <ul>
 *   <li>Uma única grade ({@link GridBagLayout}) para a lista inteira: as colunas das linhas de tipo e
 *       das linhas de sensor ficam alinhadas. Fechar um grupo só esconde as linhas dele.</li>
 *   <li>A linha do tipo edita a faixa PADRÃO (só vale para sensores criados depois) e tem
 *       [+ Adicionar sensor]; cada linha de sensor edita a faixa DAQUELE sensor e tem [Remover]
 *       (pede confirmação; os alertas já gravados continuam no histórico).</li>
 *   <li>Sensores, faixas e intervalo vêm de {@link ControladorMonitoramento#configuracaoSensores()} e
 *       são GRAVADOS no banco pelo controller, sempre em {@link SwingWorker}, com tudo travado enquanto
 *       grava. Depois, só as linhas cuja faixa mudou são redesenhadas: o que foi digitado e ainda não
 *       salvo nas outras linhas fica.</li>
 *   <li>Editável só com o sistema PARADO. A View só lê o número digitado; a regra (mínimo &lt; máximo)
 *       é do controller, e a mensagem dele aparece na linha de status do grupo. Banco que não gravou →
 *       {@link MensagemErro}.</li>
 * </ul>
 */
public final class PainelConfiguracoes extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final String DICA_PARADO = "Pare o sistema para alterar as configurações";

    private final ControladorMonitoramento controlador;
    private final Runnable aoMudarConfiguracao;

    private final JSpinner intervalo = Componentes.spinner(new JSpinner(new SpinnerNumberModel(
            ConfiguracaoSensores.INTERVALO_PADRAO_MS, ConfiguracaoSensores.INTERVALO_MINIMO_MS,
            ConfiguracaoSensores.INTERVALO_MAXIMO_MS, 10)));
    private final JButton salvarIntervalo = Componentes.botao("Salvar intervalo", Icone.OK, Estilo.SECUNDARIO);
    private final JLabel statusIntervalo = Componentes.rotulo("vale para todos os sensores");
    private final JLabel avisoFase = Componentes.indicador("", Cor.NORMAL);

    /** A lista: cabeçalho, depois um grupo por tipo (linha do tipo + linhas dos sensores + status). */
    private final JPanel lista = Componentes.transparente(new GridBagLayout());
    private final Map<TipoSensor, Grupo> grupos = new EnumMap<>(TipoSensor.class);

    private final JSpinner consumidores;
    private final JButton aplicar = Componentes.botao("Aplicar", Icone.OK, Estilo.PRIMARIO);
    private final JLabel avisoSimulacao = Componentes.rotulo("Vale a partir do próximo Iniciar.");

    private ConfiguracaoSensores atual;
    private FaseSistema fase = FaseSistema.PARADO;
    private boolean gravando;

    /** @param aoMudarConfiguracao chamado (EDT) depois de cada alteração: a aba Monitoramento refaz os cartões */
    public PainelConfiguracoes(ControladorMonitoramento controlador, Runnable aoMudarConfiguracao) {
        this.controlador = controlador;
        this.aoMudarConfiguracao = aoMudarConfiguracao;
        setOpaque(false);
        setLayout(new BorderLayout());

        // ---- seção Configurações: intervalo + lista de tipos e sensores
        JPanel geral = Componentes.transparente(new FlowLayout(FlowLayout.LEFT, Medida.ESP_3, 0));
        geral.add(Componentes.rotulo("Intervalo entre leituras (ms)", Fonte.CORPO, Cor.TEXTO));
        geral.add(intervalo);
        geral.add(salvarIntervalo);
        geral.add(statusIntervalo);
        salvarIntervalo.addActionListener(e -> salvarIntervalo());

        for (TipoSensor tipo : TipoSensor.values()) {
            grupos.put(tipo, new Grupo(tipo));
        }
        JPanel corpo = Componentes.transparente(new BorderLayout(0, Medida.ESP_4));
        corpo.add(geral, BorderLayout.NORTH);
        corpo.add(lista, BorderLayout.CENTER);
        JPanel secConfig = Componentes.secao("Configurações", Icone.REGRAS, corpo, avisoFase);

        // ---- seção Simulação: só os consumidores (não são gravados)
        consumidores = Componentes.spinner(new JSpinner(new SpinnerNumberModel(
                controlador.configuracao().consumidores(), 1, 4, 1)));
        JPanel form = Componentes.transparente(new FlowLayout(FlowLayout.LEFT, Medida.ESP_3, 0));
        form.add(Componentes.rotulo("Consumidores da fila", Fonte.CORPO, Cor.TEXTO));
        form.add(consumidores);
        form.add(aplicar);
        form.add(avisoSimulacao);
        aplicar.addActionListener(e -> aplicarSimulacao());
        consumidores.setToolTipText("Threads que retiram leituras da fila e as processam (1 a 4)");
        JPanel secSimulacao = Componentes.secao("Simulação", Icone.SENSOR, form);

        Componentes.PainelRolavel conteudo = new Componentes.PainelRolavel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        g.anchor = GridBagConstraints.NORTH;
        conteudo.add(secConfig, g);
        g.gridy = 1;
        g.insets = new Insets(Medida.ESP_3, 0, 0, 0);
        conteudo.add(secSimulacao, g);
        g.gridy = 2;
        g.weighty = 1;                               // sobra de altura fica embaixo
        conteudo.add(Componentes.transparente(new BorderLayout()), g);
        add(Componentes.rolagem(conteudo), BorderLayout.CENTER);

        mostrar(controlador.configuracaoSensores());
        aoMudarFase(controlador.fase());
    }

    // ================================================================== fase e habilitação

    /** Edição só com o sistema PARADO (EDT). */
    public void aoMudarFase(FaseSistema novaFase) {
        fase = novaFase;
        boolean parado = fase == FaseSistema.PARADO;
        if (parado && !gravando) {
            mostrar(controlador.configuracaoSensores());   // pode ter mudado (ex.: modo console)
        }
        Componentes.atualizarIndicador(avisoFase, parado ? "Edição liberada: o sistema está parado"
                : DICA_PARADO, parado ? Cor.NORMAL : Cor.SEV_MEDIA);
        atualizarHabilitacao();
    }

    private void atualizarHabilitacao() {
        boolean livre = fase == FaseSistema.PARADO && !gravando;
        String dica = fase == FaseSistema.PARADO ? null : DICA_PARADO;
        for (JComponent c : List.of(intervalo, salvarIntervalo, consumidores, aplicar)) {
            c.setEnabled(livre);
        }
        salvarIntervalo.setToolTipText(dica);
        aplicar.setToolTipText(dica);
        grupos.values().forEach(gr -> gr.habilitar(livre, dica));
    }

    // ================================================================== mostrar a configuração

    /**
     * Atualiza a tela com a configuração dada (EDT). Linhas que já existem só têm os campos
     * reescritos se a faixa delas mudou; sensor novo ganha linha e a lista é remontada.
     */
    private void mostrar(ConfiguracaoSensores cfg) {
        atual = cfg;
        intervalo.setValue(cfg.intervaloMs());
        boolean remontar = lista.getComponentCount() == 0;
        for (Grupo gr : grupos.values()) {
            remontar |= gr.mostrar(cfg);
        }
        if (remontar) {
            montarLista();
        }
    }

    /** (Re)coloca na grade: cabeçalho, e para cada tipo a linha dele, os sensores e o status. EDT. */
    private void montarLista() {
        lista.removeAll();
        int y = 0;
        for (String[] col : new String[][]{{"0", "TIPO / SENSOR"}, {"1", "FAIXA ACEITA (mín – máx)"},
                {"2", "SEVERIDADE ALÉM DO LIMITE"}}) {
            GridBagConstraints c = base(Integer.parseInt(col[0]), y);
            c.insets = new Insets(0, 0, Medida.ESP_2, Medida.ESP_4);     // respiro antes do 1º grupo
            lista.add(cabecalho(col[1]), c);
        }
        y++;
        boolean primeiro = true;
        for (Grupo gr : grupos.values()) {
            if (!primeiro) {
                JPanel divisoria = Componentes.transparente(new BorderLayout());
                divisoria.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Cor.BORDA));
                divisoria.setPreferredSize(new Dimension(0, 1));
                GridBagConstraints c = base(0, y++);
                c.gridwidth = 4;
                c.fill = GridBagConstraints.HORIZONTAL;
                c.insets = new Insets(Medida.ESP_2, 0, Medida.ESP_2, 0);
                lista.add(divisoria, c);
            }
            primeiro = false;
            y = gr.colocar(y);
        }
        lista.revalidate();
        lista.repaint();
    }

    private static JLabel cabecalho(String texto) {
        return Componentes.rotulo(texto, Fonte.ROTULO_NEGRITO, Cor.TEXTO_SECUNDARIO);
    }

    private static GridBagConstraints base(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        return c;
    }

    /**
     * Célula da lista, dentro de uma {@link Celula}: com a janela estreita a grade usa os tamanhos
     * MÍNIMOS, e só a coluna 2 (escala) pode encolher — cortada com "…", o texto inteiro fica na
     * dica. As outras nunca ficam menores que o necessário (botão achatado, nome quebrado).
     * O recuo vale para a coluna 0.
     *
     * @return a célula colocada (para esconder/mostrar a linha)
     */
    private JComponent adicionar(JComponent comp, int x, int y, int recuo, int acima) {
        Celula celula = new Celula(comp, x == 2);
        GridBagConstraints c = base(x, y);
        c.insets = new Insets(acima, x == 0 ? recuo : 0, 0, Medida.ESP_4);
        if (x == 2) {
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
        }
        if (x == 3) {
            c.anchor = GridBagConstraints.EAST;
            c.insets = new Insets(acima, 0, 0, 0);
        }
        lista.add(celula, c);
        return celula;
    }

    /** Invólucro de uma célula da lista: o mínimo é o tamanho preferido (só a escala encolhe). */
    private static final class Celula extends JPanel {
        private static final long serialVersionUID = 1L;
        private final boolean encolhe;

        Celula(JComponent conteudo, boolean encolhe) {
            super(new BorderLayout());
            this.encolhe = encolhe;
            setOpaque(false);
            add(conteudo, BorderLayout.CENTER);
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension p = getPreferredSize();
            return encolhe ? new Dimension(0, p.height) : p;
        }
    }

    // ================================================================== ações

    private void salvarIntervalo() {
        int ms = (Integer) intervalo.getValue();
        gravar(() -> controlador.alterarIntervalo(ms),
                nova -> status(statusIntervalo, true, "Intervalo salvo: " + ms + " ms"),
                msg -> status(statusIntervalo, false, msg));
    }

    private void aplicarSimulacao() {
        try {
            controlador.aplicarConfiguracao(new ConfiguracaoSimulacao((Integer) consumidores.getValue()));
            status(avisoSimulacao, true, "Aplicado. Vale a partir do próximo Iniciar.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            status(avisoSimulacao, false, ex.getMessage());
        }
    }

    /**
     * Executa uma alteração no controller fora da EDT (grava no banco). Na volta: atualiza a tela
     * com a configuração devolvida e avisa a janela; valor recusado → {@code aoRecusar} (mensagem na
     * linha de status); banco sem gravar → diálogo de erro.
     */
    private void gravar(Supplier<ConfiguracaoSensores> alteracao,
                        Consumer<ConfiguracaoSensores> aoConcluir, Consumer<String> aoRecusar) {
        gravando = true;
        atualizarHabilitacao();
        new SwingWorker<ConfiguracaoSensores, Void>() {
            @Override
            protected ConfiguracaoSensores doInBackground() {
                return alteracao.get();
            }

            @Override
            protected void done() {
                gravando = false;
                try {
                    ConfiguracaoSensores nova = get();
                    mostrar(nova);
                    aoConcluir.accept(nova);
                    aoMudarConfiguracao.run();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    Throwable causa = ex.getCause();
                    if (causa instanceof IllegalArgumentException || causa instanceof IllegalStateException) {
                        aoRecusar.accept(causa.getMessage());
                    } else if (causa instanceof FalhaDeOperacao f) {
                        aoRecusar.accept("Não salvo: o banco de dados não respondeu.");
                        Componentes.mostrarErro(PainelConfiguracoes.this, new MensagemErro(
                                "Configuração não salva", f.oQueAconteceu(), f.oQueFazer(), f.getCause()));
                    } else {
                        aoRecusar.accept("Não salvo.");
                        Componentes.mostrarErro(PainelConfiguracoes.this, new MensagemErro(
                                "Configuração não salva", "Ocorreu um erro inesperado ao salvar a configuração.",
                                "Tente de novo. Se continuar, feche e abra o programa.", causa));
                    }
                }
                atualizarHabilitacao();
            }
        }.execute();
    }

    /** Mensagem de status com ícone (nunca só cor): ✓ verde ou ⚠ vermelho. */
    private static void status(JLabel rotulo, boolean ok, String texto) {
        rotulo.setText(texto);
        rotulo.setForeground(ok ? Cor.NORMAL : Cor.PERIGO);
        rotulo.setIcon(Icones.de(ok ? Icone.OK : Icone.ALERTA, Medida.ICONE_PEQ, ok ? Cor.NORMAL : Cor.PERIGO));
        rotulo.setIconTextGap(Medida.ESP_1);
        rotulo.setVisible(true);
    }

    /** Lê o número do campo; se não for número, avisa no status e devolve vazio. */
    private static OptionalDouble ler(JTextField campo, String nome, JLabel statusDoGrupo) {
        OptionalDouble v = FormatoMensagens.lerNumero(campo.getText());
        if (v.isEmpty()) {
            status(statusDoGrupo, false, "Digite um número no " + nome + " (ex.: 12,5).");
            campo.requestFocusInWindow();
            campo.selectAll();
        }
        return v;
    }

    /** "mín [__]  máx [__]" numa célula só. */
    private static JPanel faixa(JTextField min, JTextField max) {
        JPanel p = Componentes.transparente(new FlowLayout(FlowLayout.LEFT, Medida.ESP_2, 0));
        p.add(Componentes.rotulo("mín"));
        p.add(min);
        p.add(Componentes.rotulo("máx"));
        p.add(max);
        return p;
    }

    // ================================================================== grupo de um tipo

    /** Um tipo na lista: a linha dele (faixa padrão + ações), as linhas dos sensores e o status. */
    private final class Grupo {
        private final TipoSensor tipo;
        private final JButton seta = Componentes.botaoIcone(Icone.RECOLHIDO, "Mostrar os sensores deste tipo");
        private final JLabel resumo = Componentes.rotulo("");
        private final JTextField padraoMin = Componentes.campoTexto(6);
        private final JTextField padraoMax = Componentes.campoTexto(6);
        private final JLabel escalaPadrao = Componentes.rotulo("");
        private final JButton salvarPadrao = Componentes.botaoPequeno("Salvar padrão", Estilo.SECUNDARIO);
        private final JButton adicionar = Componentes.botaoPequeno("+ Adicionar sensor", Estilo.PRIMARIO);
        private final JLabel status = Componentes.rotulo(" ");
        private final JPanel celulaTipo;
        private final JPanel celulaFaixa;
        private final JPanel celulaAcoes;
        /** Linhas de sensor, na ordem da configuração. */
        private final Map<String, LinhaSensor> sensores = new LinkedHashMap<>();
        private boolean aberto;
        private RegraDeAnomalia padraoMostrado;

        /**
         * Recuo das linhas deste grupo: passa a seta e o ícone do tipo, então o id do sensor começa
         * alinhado ao nome do tipo — fica claro que ele está "dentro" daquela categoria.
         */
        int recuo() {
            return seta.getPreferredSize().width + Medida.ESP_1 + Medida.ICONE + Medida.ESP_2;
        }

        Grupo(TipoSensor tipo) {
            this.tipo = tipo;
            JLabel nome = Componentes.rotulo(tipo.nome() + " (" + tipo.unidade() + ")", Fonte.CORPO_NEGRITO, Cor.TEXTO);
            nome.setIcon(Icones.de(Icones.doTipo(tipo), Medida.ICONE, Cor.TEXTO_SECUNDARIO));
            nome.setIconTextGap(Medida.ESP_2);
            JPanel textos = Componentes.transparente(new BorderLayout());
            textos.add(nome, BorderLayout.NORTH);
            resumo.setBorder(Componentes.margem(0, Medida.ICONE + Medida.ESP_2, 0, 0));   // alinhado ao nome
            textos.add(resumo, BorderLayout.SOUTH);
            celulaTipo = Componentes.transparente(new BorderLayout(Medida.ESP_1, 0));
            celulaTipo.add(seta, BorderLayout.WEST);
            celulaTipo.add(textos, BorderLayout.CENTER);
            celulaFaixa = faixa(padraoMin, padraoMax);
            celulaAcoes = Componentes.transparente(new FlowLayout(FlowLayout.RIGHT, Medida.ESP_2, 0));
            celulaAcoes.add(salvarPadrao);
            celulaAcoes.add(adicionar);
            status.setVisible(false);

            padraoMin.setToolTipText("Mínimo aceito pelos sensores deste tipo criados daqui em diante");
            padraoMax.setToolTipText("Máximo aceito pelos sensores deste tipo criados daqui em diante");
            salvarPadrao.setToolTipText("Só vale para sensores novos: os que já existem não mudam");
            seta.addActionListener(e -> abrir(!aberto));
            adicionar.addActionListener(e -> {
                abrir(true);
                gravar(() -> controlador.adicionarSensor(tipo),
                        nova -> {
                            SensorConfigurado novo = nova.sensoresDo(tipo).get(nova.sensoresDo(tipo).size() - 1);
                            status(status, true, "Sensor " + novo.id() + " criado com a faixa "
                                    + FormatoMensagens.faixa(novo.regra()));
                        },
                        msg -> status(status, false, msg));
            });
            salvarPadrao.addActionListener(e -> salvarPadrao());
        }

        /** Abre/fecha o grupo: as linhas dos sensores aparecem (recuadas) ou somem. */
        void abrir(boolean abrirGrupo) {
            aberto = abrirGrupo;
            seta.setIcon(Icones.queSegueOTexto(aberto ? Icone.EXPANDIDO : Icone.RECOLHIDO, Medida.ICONE));
            seta.setToolTipText(aberto ? "Esconder os sensores deste tipo" : "Mostrar os sensores deste tipo");
            sensores.values().forEach(l -> l.mostrarNaLista(aberto));
            lista.revalidate();
            lista.repaint();
        }

        /**
         * Atualiza o grupo com a configuração. Campos só são reescritos quando a faixa mudou (o que
         * foi digitado e não salvo fica).
         *
         * @return {@code true} se surgiu ou sumiu sensor (a lista precisa ser remontada)
         */
        boolean mostrar(ConfiguracaoSensores cfg) {
            List<SensorConfigurado> doTipo = cfg.sensoresDo(tipo);
            int n = doTipo.size();
            resumo.setText("padrão p/ novos · " + (n == 0 ? "nenhum sensor" : n + (n == 1 ? " sensor" : " sensores")));
            RegraDeAnomalia padrao = cfg.padraoDo(tipo);
            if (!padrao.equals(padraoMostrado)) {
                padraoMostrado = padrao;
                padraoMin.setText(FormatoMensagens.decimalCompacto(padrao.minimo()));
                padraoMax.setText(FormatoMensagens.decimalCompacto(padrao.maximo()));
                escalaPadrao.setText(FormatoMensagens.textoEscala(controlador.escalaDe(padrao), tipo));
                escalaPadrao.setToolTipText("Severidade pelo quanto a leitura passa do limite (faixa padrão): "
                        + escalaPadrao.getText());
            }
            boolean mudouLista = !sensores.keySet().equals(new java.util.LinkedHashSet<>(
                    doTipo.stream().map(SensorConfigurado::id).toList()));
            if (mudouLista) {
                Map<String, LinhaSensor> antigas = new LinkedHashMap<>(sensores);
                sensores.clear();
                for (SensorConfigurado s : doTipo) {
                    sensores.put(s.id(), antigas.getOrDefault(s.id(), new LinhaSensor(this, s.id())));
                }
            }
            for (SensorConfigurado s : doTipo) {
                sensores.get(s.id()).mostrar(s.regra(), padrao);
            }
            return mudouLista;
        }

        /** Coloca as linhas deste grupo na grade a partir da linha {@code y}; devolve a próxima livre. */
        int colocar(int y) {
            adicionar(celulaTipo, 0, y, 0, 0);
            adicionar(celulaFaixa, 1, y, 0, 0);
            adicionar(escalaPadrao, 2, y, 0, 0);
            adicionar(celulaAcoes, 3, y, 0, 0);
            y++;
            for (LinhaSensor l : sensores.values()) {
                l.colocar(y++);
                l.mostrarNaLista(aberto);
            }
            GridBagConstraints c = base(0, y++);
            c.gridwidth = 4;
            c.insets = new Insets(Medida.ESP_1, recuo(), 0, 0);
            lista.add(status, c);
            return y;
        }

        private void salvarPadrao() {
            OptionalDouble a = ler(padraoMin, "mínimo padrão", status);
            OptionalDouble b = ler(padraoMax, "máximo padrão", status);
            if (a.isEmpty() || b.isEmpty()) {
                return;
            }
            gravar(() -> controlador.alterarFaixaPadrao(tipo, a.getAsDouble(), b.getAsDouble()),
                    nova -> status(status, true, "Padrão salvo: " + FormatoMensagens.faixa(nova.padraoDo(tipo))
                            + " (só para sensores novos)"),
                    msg -> status(status, false, msg));
        }

        void habilitar(boolean livre, String dica) {
            for (JComponent c : List.of(padraoMin, padraoMax, salvarPadrao, adicionar)) {
                c.setEnabled(livre);
            }
            seta.setEnabled(!gravando);          // abrir/fechar para consultar vale sempre
            adicionar.setToolTipText(dica != null ? dica : "Cria " + atual.proximoIdDo(tipo)
                    + " com a faixa padrão " + FormatoMensagens.faixa(atual.padraoDo(tipo)));
            salvarPadrao.setToolTipText(dica != null ? dica
                    : "Só vale para sensores novos: os que já existem não mudam");
            sensores.values().forEach(l -> l.habilitar(livre, dica));
        }
    }

    /** Um sensor dentro do grupo do tipo: id recuado, faixa própria, escala, [Salvar faixa] e [Remover]. */
    private final class LinhaSensor {
        private final Grupo grupo;
        private final String id;
        private final JLabel nome;
        private final JLabel propria = Componentes.rotulo("· própria", Fonte.DADO_NEGRITO, Cor.PRIMARIA_TEXTO);
        private final JPanel celulaNome = Componentes.transparente(new FlowLayout(FlowLayout.LEFT, 0, 0));
        private final JTextField min = Componentes.campoTexto(6);
        private final JTextField max = Componentes.campoTexto(6);
        private final JLabel escala = Componentes.rotulo("");
        private final JButton salvar = Componentes.botaoPequeno("Salvar faixa", Estilo.SECUNDARIO);
        private final JButton remover = Componentes.botaoPequeno("Remover", Estilo.PERIGO);
        private final JPanel celulaFaixa;
        private final JPanel celulaAcoes;
        private final List<JComponent> celulas = new ArrayList<>();      // células colocadas na grade
        private RegraDeAnomalia mostrada;

        LinhaSensor(Grupo grupo, String id) {
            this.grupo = grupo;
            this.id = id;
            nome = Componentes.rotulo(id, Fonte.DADO_NEGRITO, Cor.TEXTO);
            celulaNome.add(nome);
            propria.setBorder(Componentes.margem(0, Medida.ESP_1, 0, 0));
            celulaNome.add(propria);
            celulaFaixa = faixa(min, max);
            celulaAcoes = Componentes.transparente(new FlowLayout(FlowLayout.RIGHT, Medida.ESP_2, 0));
            celulaAcoes.add(salvar);
            celulaAcoes.add(remover);
            min.setToolTipText("Mínimo aceito só por " + id);
            max.setToolTipText("Máximo aceito só por " + id);
            salvar.addActionListener(e -> salvar());
            remover.addActionListener(e -> remover());
        }

        /** Botão perigoso: pede confirmação antes (skill de design). */
        private void remover() {
            int r = javax.swing.JOptionPane.showConfirmDialog(PainelConfiguracoes.this,
                    "Remover o sensor " + id + "?\nEle sai da configuração e do Monitoramento; os alertas "
                            + "dele já gravados continuam no Histórico.",
                    "Remover sensor", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
            if (r != javax.swing.JOptionPane.YES_OPTION) {
                return;
            }
            gravar(() -> controlador.removerSensor(id),
                    nova -> status(grupo.status, true, "Sensor " + id + " removido"),
                    msg -> status(grupo.status, false, msg));
        }

        void mostrar(RegraDeAnomalia faixa, RegraDeAnomalia padrao) {
            boolean ehPropria = !faixa.equals(padrao);
            propria.setVisible(ehPropria);
            celulaNome.setToolTipText(ehPropria ? "Faixa diferente do padrão do tipo" : "Mesma faixa do padrão do tipo");
            if (!faixa.equals(mostrada)) {
                mostrada = faixa;
                min.setText(FormatoMensagens.decimalCompacto(faixa.minimo()));
                max.setText(FormatoMensagens.decimalCompacto(faixa.maximo()));
                escala.setText(FormatoMensagens.textoEscala(controlador.escalaDe(faixa), faixa.tipo()));
                escala.setToolTipText("Severidade pelo quanto a leitura de " + id + " passa do limite: "
                        + escala.getText());
            }
        }

        void colocar(int y) {
            celulas.clear();
            celulas.add(adicionar(celulaNome, 0, y, grupo.recuo(), Medida.ESP_2));
            celulas.add(adicionar(celulaFaixa, 1, y, 0, Medida.ESP_2));
            celulas.add(adicionar(escala, 2, y, 0, Medida.ESP_2));
            celulas.add(adicionar(celulaAcoes, 3, y, 0, Medida.ESP_2));
        }

        void mostrarNaLista(boolean visivel) {
            celulas.forEach(c -> c.setVisible(visivel));
        }

        private void salvar() {
            OptionalDouble a = ler(min, "mínimo de " + id, grupo.status);
            OptionalDouble b = ler(max, "máximo de " + id, grupo.status);
            if (a.isEmpty() || b.isEmpty()) {
                return;
            }
            gravar(() -> controlador.alterarFaixaSensor(id, a.getAsDouble(), b.getAsDouble()),
                    nova -> status(grupo.status, true, "Faixa de " + id + " salva: "
                            + FormatoMensagens.faixa(nova.regraDo(id).orElseThrow())),
                    msg -> status(grupo.status, false, id + ": " + msg));
        }

        void habilitar(boolean livre, String dica) {
            min.setEnabled(livre);
            max.setEnabled(livre);
            salvar.setEnabled(livre);
            salvar.setToolTipText(dica);
            remover.setEnabled(livre);
            remover.setToolTipText(dica != null ? dica : "Tira " + id + " da configuração (pede confirmação)");
        }
    }
}
