package infra;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Log de leituras em arquivo texto (UTF-8, modo append) — I/O com writer/stream.
 *
 * <p><b>Uma única thread escreve no arquivo.</b> Vários consumidores chamam
 * {@link #registrar(String)} ao mesmo tempo; a linha só entra numa {@link BlockingQueue}
 * interna (operação rápida e thread-safe) e a thread {@code log-escritor} é a única que
 * toca o {@link BufferedWriter}. Assim as linhas nunca se misturam e nenhum consumidor
 * segura lock enquanto o disco trabalha.</p>
 *
 * <ul>
 *   <li>O cabeçalho é escrito só se o arquivo ainda não existe ou está vazio — execuções
 *       seguintes continuam o mesmo arquivo.</li>
 *   <li>O writer é aberto já no construtor (um caminho inválido falha na hora, não depois)
 *       e fechado pela thread escritora com try-with-resources.</li>
 *   <li>O writer dá {@code flush()} sempre que a fila interna esvazia, então o arquivo
 *       acompanha a execução quase em tempo real sem um flush por linha.</li>
 *   <li>{@link #fechar()} coloca um marcador de fim <b>atrás</b> das linhas pendentes e espera
 *       a thread terminar: tudo o que foi registrado antes é escrito e o arquivo é fechado.</li>
 * </ul>
 *
 * <p>Ordem de uso no encerramento: primeiro parar os consumidores, depois {@code fechar()}.
 * A fila interna não tem limite de tamanho para que {@code registrar} nunca bloqueie nem
 * descarte linhas.</p>
 *
 * <p>Este pacote não conhece o formato das linhas: elas chegam prontas (geradas por
 * {@code app.FormatoMensagens}), e o cabeçalho é passado no construtor.</p>
 */
public class LogArquivo implements RegistroDeLeituras, AutoCloseable {

    public static final Path CAMINHO_PADRAO = Path.of("data", "leituras.log");

    /** Marcador de fim — comparado por identidade. */
    private static final String FIM = new String("<<fim>>");

    private final Path arquivo;
    private final BlockingQueue<String> pendentes = new LinkedBlockingQueue<>();
    private final AtomicBoolean fechado = new AtomicBoolean(false);
    private final AtomicLong linhasEscritas = new AtomicLong();
    private final AtomicReference<IOException> erro = new AtomicReference<>();
    private final Thread escritora;

    /**
     * Abre (ou cria) o arquivo e inicia a thread escritora.
     *
     * @param cabecalho primeira linha do arquivo, escrita só quando ele é novo ou vazio
     * @throws UncheckedIOException se o arquivo não puder ser criado/aberto
     */
    public LogArquivo(Path arquivo, String cabecalho) {
        this.arquivo = Objects.requireNonNull(arquivo, "arquivo").toAbsolutePath();
        Objects.requireNonNull(cabecalho, "cabecalho");
        BufferedWriter writer;
        try {
            Path pasta = this.arquivo.getParent();
            if (pasta != null) {
                Files.createDirectories(pasta);
            }
            boolean novoOuVazio = Files.notExists(this.arquivo) || Files.size(this.arquivo) == 0;
            writer = Files.newBufferedWriter(this.arquivo, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (novoOuVazio) {
                writer.write(cabecalho);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível abrir o log " + this.arquivo, e);
        }
        this.escritora = new Thread(() -> escrever(writer), "log-escritor");
        this.escritora.setDaemon(true);
        this.escritora.start();
    }

    /** Laço da thread escritora: única dona do writer. */
    private void escrever(BufferedWriter writer) {
        try (BufferedWriter w = writer) {
            while (true) {
                String linha = pendentes.take();
                if (linha == FIM) {
                    return;   // try-with-resources faz flush + close
                }
                w.write(linha);
                w.newLine();
                linhasEscritas.incrementAndGet();
                if (pendentes.isEmpty()) {
                    w.flush();
                }
            }
        } catch (IOException e) {
            erro.set(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Enfileira uma linha para escrita. Não bloqueia; seguro para várias threads.
     *
     * @throws IllegalStateException se o log já foi fechado
     */
    @Override
    public void registrar(String linha) {
        Objects.requireNonNull(linha, "linha");
        if (fechado.get()) {
            throw new IllegalStateException("Log de leituras já foi fechado");
        }
        IOException falha = erro.get();
        if (falha != null) {
            throw new UncheckedIOException("Log de leituras parou de gravar em " + arquivo, falha);
        }
        pendentes.add(linha);
    }

    /**
     * Escreve tudo o que estava pendente, fecha o arquivo e encerra a thread escritora.
     * Pode ser chamado mais de uma vez.
     */
    public void fechar() throws InterruptedException {
        if (fechado.compareAndSet(false, true)) {
            pendentes.add(FIM);
        }
        escritora.join();
    }

    @Override
    public void close() throws InterruptedException {
        fechar();
    }

    public Path arquivo() {
        return arquivo;
    }

    /** Linhas de leitura já gravadas pela thread escritora (sem o cabeçalho). */
    public long linhasEscritas() {
        return linhasEscritas.get();
    }

    /** Erro de I/O que fez a escrita parar, se houve. */
    public Optional<IOException> erro() {
        return Optional.ofNullable(erro.get());
    }
}
