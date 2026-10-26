package infra;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogArquivoTest {

    private static final String CABECALHO = "momento;sensor;tipo;valor;unidade;resultado;severidade;thread";

    @TempDir
    Path pasta;

    private List<String> linhas(Path arquivo) throws IOException {
        return Files.readAllLines(arquivo, StandardCharsets.UTF_8);
    }

    @Test
    void criaPastaEArquivoComCabecalho() throws Exception {
        Path arquivo = pasta.resolve("data").resolve("leituras.log");
        LogArquivo log = new LogArquivo(arquivo, CABECALHO);
        log.registrar("linha 1");
        log.fechar();

        assertEquals(List.of(CABECALHO, "linha 1"), linhas(arquivo));
        assertEquals(1, log.linhasEscritas());
    }

    @Test
    void cabecalhoSoUmaVezMesmoReabrindoOArquivo() throws Exception {
        Path arquivo = pasta.resolve("leituras.log");
        try (LogArquivo primeira = new LogArquivo(arquivo, CABECALHO)) {
            primeira.registrar("a");
            primeira.registrar("b");
        }
        try (LogArquivo segunda = new LogArquivo(arquivo, CABECALHO)) {
            segunda.registrar("c");
        }

        List<String> conteudo = linhas(arquivo);
        assertEquals(List.of(CABECALHO, "a", "b", "c"), conteudo, "modo append, cabeçalho único");
    }

    @Test
    void arquivoExistenteVazioRecebeCabecalho() throws Exception {
        Path arquivo = Files.createFile(pasta.resolve("vazio.log"));
        try (LogArquivo log = new LogArquivo(arquivo, CABECALHO)) {
            log.registrar("x");
        }
        assertEquals(List.of(CABECALHO, "x"), linhas(arquivo));
    }

    @Test
    void variasThreadsRegistrandoAoMesmoTempoNenhumaLinhaPerdidaOuMisturada() throws Exception {
        Path arquivo = pasta.resolve("concorrente.log");
        LogArquivo log = new LogArquivo(arquivo, CABECALHO);
        int threads = 8;
        int porThread = 2_000;
        CountDownLatch largada = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread th = new Thread(() -> {
                try {
                    largada.await();
                    for (int i = 0; i < porThread; i++) {
                        log.registrar("consumidor-" + id + ";" + i + ";Pressão;°C");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            ts.add(th);
            th.start();
        }
        largada.countDown();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (Thread th : ts) {
                th.join();
            }
            log.fechar();
        });

        List<String> conteudo = linhas(arquivo);
        int total = threads * porThread;
        assertEquals(total + 1, conteudo.size(), "N linhas + cabeçalho");
        assertEquals(CABECALHO, conteudo.get(0));
        assertEquals(total, new HashSet<>(conteudo.subList(1, conteudo.size())).size(), "todas distintas e inteiras");
        assertTrue(conteudo.stream().skip(1).allMatch(l -> l.matches("consumidor-\\d;\\d+;Pressão;°C")),
                "nenhuma linha intercalada com outra");
        assertEquals(total, log.linhasEscritas());
    }

    @Test
    void acentosEGrauCelsiusPreservadosEmUtf8() throws Exception {
        Path arquivo = pasta.resolve("utf8.log");
        String linha = "2026-10-15T19:46:02.431-03:00;TEMP-01;TEMPERATURA;92.50;°C;ANOMALA;ALTA;sensor-TEMP-01";
        try (LogArquivo log = new LogArquivo(arquivo, CABECALHO)) {
            log.registrar(linha);
            log.registrar("Pressão;Umidade;ç;ã;é");
        }

        List<String> conteudo = linhas(arquivo);
        assertEquals(linha, conteudo.get(1));
        assertEquals("Pressão;Umidade;ç;ã;é", conteudo.get(2));
        byte[] bytes = Files.readAllBytes(arquivo);
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("°C"));
    }

    @Test
    void fecharEscreveTudoQueAindaEstavaPendente() throws Exception {
        Path arquivo = pasta.resolve("drenar.log");
        LogArquivo log = new LogArquivo(arquivo, CABECALHO);
        for (int i = 0; i < 50_000; i++) {
            log.registrar("linha " + i);
        }
        log.fechar();   // sem esperar nada antes: fechar precisa drenar

        List<String> conteudo = linhas(arquivo);
        assertEquals(50_001, conteudo.size());
        assertEquals("linha 49999", conteudo.get(conteudo.size() - 1));
    }

    @Test
    void registrarDepoisDeFecharEhErroEFecharDuasVezesNaoEh() throws Exception {
        LogArquivo log = new LogArquivo(pasta.resolve("f.log"), CABECALHO);
        log.fechar();
        log.fechar();
        assertThrows(IllegalStateException.class, () -> log.registrar("tarde demais"));
    }

    @Test
    void caminhoInvalidoFalhaNaHora() throws Exception {
        Path arquivoComum = Files.createFile(pasta.resolve("nao-sou-pasta"));
        assertThrows(UncheckedIOException.class,
                () -> new LogArquivo(arquivoComum.resolve("leituras.log"), CABECALHO));
    }
}
