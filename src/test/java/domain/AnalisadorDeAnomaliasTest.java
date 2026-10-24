package domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AnalisadorDeAnomaliasTest {

    private static final Instant T = Instant.parse("2026-10-20T12:00:00Z");
    private final AnalisadorDeAnomalias analisador = new AnalisadorDeAnomalias();

    private LeituraTelemetria leitura(String id, TipoSensor tipo, double valor) {
        return new LeituraTelemetria(id, tipo, valor, T, 1L);
    }

    @Test
    void temperaturaAcimaDoLimiteGeraAlerta() {
        assertTrue(analisador.analisar(leitura("T1", TipoSensor.TEMPERATURA, 80.1)).isPresent());
    }

    @Test
    void temperaturaExatamenteNoLimiteNaoGeraAlerta() {
        assertTrue(analisador.analisar(leitura("T1", TipoSensor.TEMPERATURA, 80.0)).isEmpty());
    }

    @Test
    void velocidadeAbaixoDaFaixaGeraAlerta() {
        assertTrue(analisador.analisar(leitura("V1", TipoSensor.VELOCIDADE, -0.1)).isPresent());
    }

    @Test
    void velocidadeAcimaDaFaixaGeraAlerta() {
        assertTrue(analisador.analisar(leitura("V1", TipoSensor.VELOCIDADE, 120.1)).isPresent());
    }

    @Test
    void velocidadeNosLimitesDaFaixaNaoGeraAlerta() {
        assertTrue(analisador.analisar(leitura("V1", TipoSensor.VELOCIDADE, 0.0)).isEmpty());
        assertTrue(analisador.analisar(leitura("V1", TipoSensor.VELOCIDADE, 120.0)).isEmpty());
    }

    @Test
    void pressaoAbaixoDaFaixaGeraAlerta() {
        assertTrue(analisador.analisar(leitura("P1", TipoSensor.PRESSAO, 0.5)).isPresent());
    }

    @Test
    void pressaoAcimaDaFaixaGeraAlerta() {
        assertTrue(analisador.analisar(leitura("P1", TipoSensor.PRESSAO, 8.5)).isPresent());
    }

    @Test
    void pressaoNosLimitesDaFaixaNaoGeraAlerta() {
        assertTrue(analisador.analisar(leitura("P1", TipoSensor.PRESSAO, 1.0)).isEmpty());
        assertTrue(analisador.analisar(leitura("P1", TipoSensor.PRESSAO, 8.0)).isEmpty());
    }

    @Test
    void cadaTipoUsaSuaPropriaRegra() {
        // 100 é anômalo para temperatura, mas normal para velocidade
        assertTrue(analisador.analisar(leitura("T1", TipoSensor.TEMPERATURA, 100)).isPresent());
        assertTrue(analisador.analisar(leitura("V1", TipoSensor.VELOCIDADE, 100)).isEmpty());
    }

    @Test
    void alertaCarregaSensorValorEMotivoCorretos() {
        Optional<Alerta> resultado = analisador.analisar(leitura("T7", TipoSensor.TEMPERATURA, 95.0));

        Alerta alerta = resultado.orElseThrow();
        assertNull(alerta.id());
        assertEquals("T7", alerta.sensorId());
        assertEquals(TipoSensor.TEMPERATURA, alerta.tipo());
        assertEquals(95.0, alerta.valor());
        assertEquals(T, alerta.instante());
        assertFalse(alerta.motivo().isBlank());
    }

    @Test
    void leituraRejeitaSensorIdVazio() {
        assertThrows(IllegalArgumentException.class,
                () -> new LeituraTelemetria("  ", TipoSensor.TEMPERATURA, 10, T, 1L));
    }

    @Test
    void leituraRejeitaValorNaNOuInfinito() {
        assertThrows(IllegalArgumentException.class,
                () -> new LeituraTelemetria("T1", TipoSensor.TEMPERATURA, Double.NaN, T, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new LeituraTelemetria("T1", TipoSensor.TEMPERATURA, Double.POSITIVE_INFINITY, T, 1L));
    }
}