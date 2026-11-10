package domain;

import static org.junit.jupiter.api.Assertions.*;

import domain.CicloDeEpisodios.Fase;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Ciclo de episódios por eventos discretos — NORMAL → AQUECENDO → CRISE → ESFRIANDO → NORMAL —
 * sem threads. Com {@link Random} de semente fixa os resultados são sempre os mesmos.
 */
class CicloDeEpisodiosTest {

    private static final ParametrosDeEpisodios PADRAO = ParametrosDeEpisodios.padrao();
    private static final int PASSOS = 400_000;   // ~3.700 episódios com os parâmetros padrão

    /**
     * Avança o ciclo e devolve os episódios na ordem em que começaram, conferindo a cada passo
     * que a fase e o episódio em andamento batem com as datas do episódio.
     */
    private static List<EpisodioDeAnomalia> episodios(CicloDeEpisodios ciclo, int passos) {
        List<EpisodioDeAnomalia> r = new ArrayList<>();
        for (int i = 1; i <= passos; i++) {
            Optional<EpisodioDeAnomalia> crise = ciclo.avancar();
            assertEquals(i, ciclo.passo(), "o relógio simulado avança 1 passo por chamada");
            Optional<EpisodioDeAnomalia> andamento = ciclo.episodioEmAndamento();
            if (andamento.isEmpty()) {
                assertEquals(Fase.NORMAL, ciclo.fase(), "passo " + i);
                assertTrue(crise.isEmpty());
                continue;
            }
            EpisodioDeAnomalia e = andamento.get();
            if (r.isEmpty() || !r.get(r.size() - 1).equals(e)) {
                assertEquals(i, e.inicioAquecimento(), "um episódio aparece a partir do início do aquecimento");
                r.add(e);
            }
            Fase esperada = i < e.inicio() ? Fase.AQUECENDO : i < e.fim() ? Fase.CRISE : Fase.ESFRIANDO;
            assertEquals(esperada, ciclo.fase(), e + " no passo " + i);
            assertEquals(esperada == Fase.CRISE, crise.isPresent(), "avancar() só devolve o episódio na crise");
            assertTrue(i < e.fimEsfriamento());
        }
        return r;
    }

    private static CicloDeEpisodios ciclo(TipoSensor tipo, long semente) {
        return new CicloDeEpisodios(tipo, PADRAO, new Random(semente));
    }

    @Test
    void fasesVemEmOrdemEComDuracoesDentroDasFaixas() {
        List<EpisodioDeAnomalia> eps = episodios(ciclo(TipoSensor.TEMPERATURA, 1), PASSOS);
        assertTrue(eps.size() > 1000, "episódios: " + eps.size());
        boolean viuMinima = false;
        boolean viuMaxima = false;
        for (EpisodioDeAnomalia e : eps) {
            assertTrue(e.inicioAquecimento() <= e.inicio() && e.inicio() < e.fim() && e.fim() <= e.fimEsfriamento());
            assertTrue(e.aquecimento() >= PADRAO.aquecimentoMinimo() && e.aquecimento() <= PADRAO.aquecimentoMaximo(), e.toString());
            assertTrue(e.duracao() >= PADRAO.duracaoMinima() && e.duracao() <= PADRAO.duracaoMaxima(), e.toString());
            assertTrue(e.esfriamento() >= PADRAO.esfriamentoMinimo() && e.esfriamento() <= PADRAO.esfriamentoMaximo(), e.toString());
            viuMinima |= e.duracao() == PADRAO.duracaoMinima();
            viuMaxima |= e.duracao() == PADRAO.duracaoMaxima();
        }
        assertTrue(viuMinima && viuMaxima, "a duração uniforme alcança os dois extremos");
    }

    @Test
    void episodiosNuncaSeSobrepoemEHaPeloMenosUmaLeituraNormalEntreEles() {
        List<EpisodioDeAnomalia> eps = episodios(ciclo(TipoSensor.PRESSAO, 2), PASSOS);
        for (int i = 1; i < eps.size(); i++) {
            assertTrue(eps.get(i).inicioAquecimento() > eps.get(i - 1).fimEsfriamento(),
                    eps.get(i - 1) + " seguido de " + eps.get(i));
        }
    }

    @Test
    void mesmaSementeGeraMesmaSequenciaDeEpisodios() {
        assertEquals(episodios(ciclo(TipoSensor.UMIDADE, 42), 50_000),
                episodios(ciclo(TipoSensor.UMIDADE, 42), 50_000));
        assertNotEquals(episodios(ciclo(TipoSensor.UMIDADE, 42), 50_000),
                episodios(ciclo(TipoSensor.UMIDADE, 43), 50_000), "sementes diferentes, episódios diferentes");
    }

    @Test
    void tempoNormalEntreEpisodiosFicaPertoDaMediaConfigurada() {
        List<EpisodioDeAnomalia> eps = episodios(ciclo(TipoSensor.TEMPERATURA, 3), PASSOS);
        double soma = 0;
        for (int i = 1; i < eps.size(); i++) {
            soma += eps.get(i).inicioAquecimento() - eps.get(i - 1).fimEsfriamento();
        }
        double media = soma / (eps.size() - 1);
        // tolerância de 5 %: o intervalo exponencial é arredondado para passos inteiros (≥ 1)
        assertEquals(PADRAO.mediaEntreEpisodios(), media, PADRAO.mediaEntreEpisodios() * 0.05, "média = " + media);
    }

    @Test
    void mediaMenorGeraEpisodiosMaisFrequentes() {
        CicloDeEpisodios frequente = new CicloDeEpisodios(TipoSensor.TEMPERATURA,
                PADRAO.comMediaEntreEpisodios(5), new Random(4));
        assertTrue(episodios(frequente, 50_000).size() > 2 * episodios(ciclo(TipoSensor.TEMPERATURA, 4), 50_000).size());
    }

    @Test
    void severidadesSeguemOsPesos() {
        List<EpisodioDeAnomalia> eps = episodios(ciclo(TipoSensor.TEMPERATURA, 5), PASSOS);
        Map<Severidade, Integer> contagem = new EnumMap<>(Severidade.class);
        eps.forEach(e -> contagem.merge(e.severidadeAlvo(), 1, Integer::sum));
        double total = PADRAO.pesosSeveridade().values().stream().mapToDouble(Double::doubleValue).sum();
        for (Severidade s : Severidade.values()) {
            double esperado = PADRAO.pesosSeveridade().get(s) / total;
            double obtido = contagem.getOrDefault(s, 0) / (double) eps.size();
            assertEquals(esperado, obtido, 0.03, s + ": " + obtido);
        }
    }

    @Test
    void fracaoDeEpisodiosQuePassamDoLimiteSegueAProbabilidadeDeCrise() {
        List<EpisodioDeAnomalia> eps = episodios(ciclo(TipoSensor.PRESSAO, 10), PASSOS);
        double cruzam = eps.stream().filter(EpisodioDeAnomalia::passaDoLimite).count() / (double) eps.size();
        assertEquals(PADRAO.probabilidadeDeCrise(), cruzam, 0.03, "fração que passa do limite = " + cruzam);
    }

    @Test
    void velocidadeSoTemEpisodiosAcima() {
        episodios(ciclo(TipoSensor.VELOCIDADE, 6), PASSOS)
                .forEach(e -> assertEquals(EpisodioDeAnomalia.Direcao.ACIMA, e.direcao()));
    }

    @Test
    void temperaturaSobeNaMaioriaDosEpisodios() {
        List<EpisodioDeAnomalia> eps = episodios(ciclo(TipoSensor.TEMPERATURA, 7), PASSOS);
        double acima = eps.stream().filter(e -> e.direcao() == EpisodioDeAnomalia.Direcao.ACIMA).count() / (double) eps.size();
        assertEquals(0.8, acima, 0.03, "fração acima = " + acima);
    }

    @Test
    void semRampasACriseComecaNoMesmoPassoDoAquecimento() {
        ParametrosDeEpisodios semRampas = new ParametrosDeEpisodios(PADRAO.mediaEntreEpisodios(), 0, 0,
                PADRAO.duracaoMinima(), PADRAO.duracaoMaxima(), 0, 0, PADRAO.probabilidadeDeCrise(),
                PADRAO.pesosSeveridade(), PADRAO.probabilidadeAcima());
        for (EpisodioDeAnomalia e : episodios(new CicloDeEpisodios(TipoSensor.UMIDADE, semRampas, new Random(12)), 50_000)) {
            assertEquals(e.inicioAquecimento(), e.inicio());
            assertEquals(e.fim(), e.fimEsfriamento());
        }
    }

    @Test
    void antesDeAvancarNaoHaEpisodioNemPasso() {
        CicloDeEpisodios c = ciclo(TipoSensor.UMIDADE, 8);
        assertEquals(0, c.passo());
        assertEquals(Fase.NORMAL, c.fase());
        assertTrue(c.episodioAtual().isEmpty());
        assertTrue(c.episodioEmAndamento().isEmpty());
        assertEquals(0, c.episodiosIniciados());
    }

    @Test
    void contaEpisodiosIniciados() {
        CicloDeEpisodios c = ciclo(TipoSensor.UMIDADE, 9);
        assertEquals(episodios(c, 30_000).size(), c.episodiosIniciados());
    }

    @Test
    void episodioValidaAsDatasDasFases() {
        var sev = Severidade.BAIXA;
        var acima = EpisodioDeAnomalia.Direcao.ACIMA;
        assertThrows(IllegalArgumentException.class, () -> new EpisodioDeAnomalia(5, 5, sev, acima), "crise vazia");
        assertThrows(IllegalArgumentException.class, () -> new EpisodioDeAnomalia(6, 5, 8, 10, sev, acima, true),
                "aquecimento depois da crise");
        assertThrows(IllegalArgumentException.class, () -> new EpisodioDeAnomalia(2, 5, 8, 7, sev, acima, true),
                "esfriamento antes do fim da crise");
        EpisodioDeAnomalia e = new EpisodioDeAnomalia(2, 5, 8, 12, sev, EpisodioDeAnomalia.Direcao.ABAIXO, false);
        assertEquals(3, e.aquecimento());
        assertEquals(3, e.duracao());
        assertEquals(4, e.esfriamento());
        assertTrue(e.contem(5) && e.contem(7), "contem() é só a crise");
        assertFalse(e.contem(4) || e.contem(8), "intervalo [inicio, fim)");
        assertFalse(e.passaDoLimite());
        EpisodioDeAnomalia simples = new EpisodioDeAnomalia(5, 8, sev, acima);
        assertEquals(5, simples.inicioAquecimento());
        assertEquals(8, simples.fimEsfriamento());
        assertTrue(simples.passaDoLimite());
    }
}
