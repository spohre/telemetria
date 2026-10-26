package app;

import app.ui.telas.JanelaPrincipal;
import app.ui.tema.Tema;
import infra.AlertaRepository;
import infra.FabricaDeConexoes;
import infra.FilaDeLeituras;
import infra.InicializadorBanco;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import javax.swing.SwingUtilities;

/**
 * Ponto de entrada da Central de Telemetria.
 *
 * <pre>
 *   mvn -q compile exec:java                                    → abre a interface
 *   mvn -q compile exec:java -Dexec.args="--console 10"         → roda 10 s sem janela
 *   mvn -q compile exec:java -Dexec.args="--console 20 --sensores 3 --intervalo 20 --consumidores 2"
 * </pre>
 *
 * <p>Opções do modo console (todas opcionais): {@code --sensores N} (por tipo, 1–10),
 * {@code --intervalo MS} (10–5000), {@code --consumidores N} (1–4) e {@code --fila N}
 * (capacidade da fila; padrão 1000 — menor força os sensores a esperar).</p>
 */
public final class Main {

    private static final int SEGUNDOS_PADRAO = 10;

    private Main() {
    }

    public static void main(String[] args) {
        FabricaDeConexoes fabrica = FabricaDeConexoes.doArquivoDeConfiguracao();
        RuntimeException falhaBanco = prepararBanco(fabrica);
        int capacidadeFila = FilaDeLeituras.CAPACIDADE_PADRAO;
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals("--fila")) {
                capacidadeFila = Integer.parseInt(args[i + 1]);
            }
        }
        ControladorTelemetria controlador = new ControladorTelemetria(new AlertaRepository(fabrica), fabrica.url(),
                Path.of(""), capacidadeFila, System.out, Clock.systemDefaultZone());

        if (args.length > 0 && args[0].equals("--console")) {
            rodarNoConsole(controlador, args);
        } else {
            SwingUtilities.invokeLater(() -> {      // toda a criação de tela na EDT
                Tema.instalar();
                JanelaPrincipal janela = new JanelaPrincipal(controlador);
                janela.setVisible(true);
                if (falhaBanco != null) {
                    janela.aoOcorrerFalha("O banco de dados não está disponível.",
                            "Verifique db.url em db.properties e se o banco está acessível. O sistema funciona, "
                                    + "mas os alertas não serão salvos nem aparecerão no Histórico.", falhaBanco);
                }
            });
        }
    }

    /**
     * Cria a tabela se preciso. Banco fora do ar não impede a execução: os alertas só não são salvos.
     *
     * @return a falha, ou {@code null} se o banco está pronto
     */
    private static RuntimeException prepararBanco(FabricaDeConexoes fabrica) {
        try {
            new InicializadorBanco(fabrica).criarEstrutura();
            System.out.println(FormatoMensagens.console(Instant.now(), FormatoMensagens.Nivel.INFO, "banco",
                    "Conectado: " + fabrica.url()));
            return null;
        } catch (RuntimeException e) {
            System.out.println(FormatoMensagens.console(Instant.now(), FormatoMensagens.Nivel.ERRO, "banco",
                    "Banco indisponível (" + e.getMessage() + "). Verifique db.url em db.properties. "
                            + "O sistema roda, mas os alertas não serão salvos."));
            return e;
        }
    }

    private static void rodarNoConsole(ControladorTelemetria controlador, String[] args) {
        int segundos = SEGUNDOS_PADRAO;
        ConfiguracaoSimulacao base = controlador.configuracao();
        int sensores = base.sensoresPorTipo();
        int intervalo = base.intervaloMs();
        int consumidores = base.consumidores();
        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--sensores" -> sensores = Integer.parseInt(args[++i]);
                    case "--intervalo" -> intervalo = Integer.parseInt(args[++i]);
                    case "--consumidores" -> consumidores = Integer.parseInt(args[++i]);
                    case "--fila" -> i++;   // já lida em main()
                    default -> segundos = Integer.parseInt(args[i]);
                }
            }
            controlador.aplicarConfiguracao(new ConfiguracaoSimulacao(sensores, intervalo, consumidores));
        } catch (RuntimeException e) {   // número inválido, opção sem valor ou fora da faixa
            System.out.println(FormatoMensagens.console(Instant.now(), FormatoMensagens.Nivel.ERRO, "sistema",
                    "Argumentos inválidos (" + e.getMessage() + "). Uso: --console [segundos] "
                            + "[--sensores 1-10] [--intervalo 10-5000] [--consumidores 1-4] [--fila N]"));
            System.exit(2);
            return;
        }

        // Ctrl+C também encerra de forma limpa (drena a fila, fecha o log, grava o extrato)
        Thread ganchoSaida = new Thread(() -> {
            if (controlador.fase() != FaseSistema.PARADO) {
                controlador.parar();
            }
        }, "encerramento");
        Runtime.getRuntime().addShutdownHook(ganchoSaida);

        controlador.iniciar();
        if (controlador.fase() == FaseSistema.PARADO) {   // não conseguiu iniciar (o motivo já foi impresso)
            Runtime.getRuntime().removeShutdownHook(ganchoSaida);
            System.exit(1);
            return;
        }
        try {
            Thread.sleep(segundos * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ExtratoExecucao extrato = controlador.parar();
        Runtime.getRuntime().removeShutdownHook(ganchoSaida);

        System.out.println();
        System.out.print(FormatoMensagens.extrato(extrato));
    }
}
