package app;

import domain.Alerta;
import domain.AnalisadorDeAnomalias;
import domain.LeituraTelemetria;
import domain.RepositorioDeAlertas;
import domain.Severidade;
import infra.FilaDeLeituras;
import infra.RegistroDeLeituras;

import java.util.Objects;
import java.util.Optional;

/**
 * Consumidor da fila — roda em thread própria. Para cada leitura:
 * <ol>
 *   <li>retira da {@link FilaDeLeituras};</li>
 *   <li>aplica o {@link AnalisadorDeAnomalias};</li>
 *   <li>se houver alerta, salva no {@link RepositorioDeAlertas};</li>
 *   <li>registra a linha no log de leituras (todas as leituras, normais e anômalas);</li>
 *   <li>avisa o {@link OuvinteTelemetria} (interface/console);</li>
 *   <li>atualiza os {@link ContadoresExecucao}.</li>
 * </ol>
 *
 * <p><b>Concorrência:</b> pode haver vários processadores sobre a mesma fila. Nenhum deles
 * segura lock enquanto analisa, grava no banco ou escreve no log — a única sincronização é
 * a da própria fila no {@code take()}. O analisador é puro, o repositório abre uma conexão
 * por operação e os contadores são atômicos, então nada aqui é compartilhado de forma
 * insegura.</p>
 *
 * <p><b>Falhas:</b> erro ao salvar um alerta ou ao gravar o log não derruba a thread — o alerta
 * conta como "não salvo" e o ouvinte é avisado só quando o banco (ou o log) passa de disponível
 * para indisponível (para não inundar a tela com o mesmo erro). Qualquer outro erro inesperado ao processar
 * uma leitura também é avisado e o consumidor segue para a próxima.</p>
 *
 * <p><b>Fim:</b> termina ao receber a pílula de veneno (ver {@link FilaDeLeituras}); uma
 * interrupção aborta na hora, restaurando o flag.</p>
 */
public class ProcessadorDeLeituras implements Runnable {

    static final String FALHA_BANCO_O_QUE = "Alerta não salvo: o banco de dados não respondeu.";
    static final String FALHA_BANCO_FAZER =
            "Verifique se o banco está acessível e se db.url em db.properties está correto. "
                    + "Os alertas continuam sendo gerados e aparecem na tela.";
    static final String FALHA_LOG_O_QUE = "O arquivo de log de leituras parou de ser gravado.";
    static final String FALHA_LOG_FAZER =
            "Verifique se há espaço em disco e se data/leituras.log não está aberto em outro programa. "
                    + "Leituras e alertas continuam sendo processados e salvos no banco.";
    static final String FALHA_INESPERADA_O_QUE = "Uma leitura não pôde ser processada.";
    static final String FALHA_INESPERADA_FAZER =
            "O sistema continua processando as próximas leituras. Veja o detalhe técnico e o log.";

    private final FilaDeLeituras fila;
    private final AnalisadorDeAnomalias analisador;
    private final RepositorioDeAlertas repositorio;
    private final RegistroDeLeituras registro;
    private final OuvinteTelemetria ouvinte;
    private final ContadoresExecucao contadores;

    public ProcessadorDeLeituras(FilaDeLeituras fila, AnalisadorDeAnomalias analisador,
                                 RepositorioDeAlertas repositorio, RegistroDeLeituras registro,
                                 OuvinteTelemetria ouvinte, ContadoresExecucao contadores) {
        this.fila = Objects.requireNonNull(fila, "fila");
        this.analisador = Objects.requireNonNull(analisador, "analisador");
        this.repositorio = Objects.requireNonNull(repositorio, "repositorio");
        this.registro = Objects.requireNonNull(registro, "registro");
        this.ouvinte = Objects.requireNonNull(ouvinte, "ouvinte");
        this.contadores = Objects.requireNonNull(contadores, "contadores");
    }

    /** Usa o {@link AnalisadorDeAnomalias} padrão (sem estado — pode ser compartilhado). */
    public ProcessadorDeLeituras(FilaDeLeituras fila, RepositorioDeAlertas repositorio, RegistroDeLeituras registro,
                                 OuvinteTelemetria ouvinte, ContadoresExecucao contadores) {
        this(fila, new AnalisadorDeAnomalias(), repositorio, registro, ouvinte, contadores);
    }

    @Override
    public void run() {
        try {
            while (true) {
                Optional<LeituraTelemetria> proxima = fila.retirar();
                if (proxima.isEmpty()) {
                    return;   // pílula de veneno: a fila já foi drenada até aqui
                }
                processar(proxima.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Processa uma leitura. Visível no pacote para ser testado sem threads. */
    void processar(LeituraTelemetria leitura) {
        try {
            Alerta alerta = analisador.analisar(leitura).map(this::salvar).orElse(null);
            Severidade severidade = alerta == null ? null : alerta.severidade();

            registrarNoLog(leitura, severidade);
            ouvinte.aoProcessarLeitura(leitura, alerta);
        } catch (RuntimeException e) {
            ouvinte.aoOcorrerFalha(FALHA_INESPERADA_O_QUE, FALHA_INESPERADA_FAZER, e);
        } finally {
            // a leitura saiu da fila e foi tratada (mesmo com erro): conta como processada,
            // senão apareceria como "perdida" no extrato
            contadores.leituraProcessada();
        }
    }

    /**
     * Grava a linha no log. Se o log parar (disco cheio, arquivo travado), avisa UMA vez — e não
     * a cada leitura, o que inundaria a tela de diálogos — e o processamento continua.
     */
    private void registrarNoLog(LeituraTelemetria leitura, Severidade severidade) {
        try {
            registro.registrar(FormatoMensagens.linhaLog(leitura, severidade, nomeThreadDoSensor(leitura)));
            contadores.marcarLogDisponivel(true);
        } catch (RuntimeException e) {
            if (contadores.marcarLogDisponivel(false)) {
                ouvinte.aoOcorrerFalha(FALHA_LOG_O_QUE, FALHA_LOG_FAZER, e);
            }
        }
    }

    /** Salva o alerta; em caso de falha devolve o alerta sem id (não salvo). */
    private Alerta salvar(Alerta novo) {
        contadores.alertaGerado(novo.tipo(), novo.severidade());
        try {
            Alerta salvo = repositorio.salvar(novo);
            contadores.alertaSalvo();
            contadores.marcarBancoDisponivel(true);
            return salvo;
        } catch (RuntimeException e) {
            contadores.alertaNaoSalvo();
            boolean estavaDisponivel = contadores.marcarBancoDisponivel(false);
            if (estavaDisponivel) {
                ouvinte.aoOcorrerFalha(FALHA_BANCO_O_QUE, FALHA_BANCO_FAZER, e);
            }
            return novo;
        }
    }

    /** Coluna "thread" do log: a thread que PRODUZIU a leitura (padrão sensor-&lt;ID&gt;). */
    static String nomeThreadDoSensor(LeituraTelemetria leitura) {
        return "sensor-" + leitura.sensorId();
    }
}
