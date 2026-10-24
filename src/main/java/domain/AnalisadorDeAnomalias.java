package domain;

import java.util.Optional;

public class AnalisadorDeAnomalias {

    public Optional<Alerta> analisar(LeituraTelemetria leitura) {
        String motivo = null;

        if (leitura.tipo() == TipoSensor.TEMPERATURA && leitura.valor() > 80.0) {
            motivo = "Temperatura acima de 80.0";
        } else if (leitura.tipo() == TipoSensor.VELOCIDADE
                && (leitura.valor() < 0.0 || leitura.valor() > 120.0)) {
            motivo = "Velocidade fora da faixa [0.0; 120.0]";
        } else if (leitura.tipo() == TipoSensor.PRESSAO
                && (leitura.valor() < 1.0 || leitura.valor() > 8.0)) {
            motivo = "Pressão fora da faixa [1.0; 8.0]";
        }

        if (motivo == null) {
            return Optional.empty();
        }
        return Optional.of(new Alerta(null, leitura.sensorId(), leitura.tipo(),
                leitura.valor(), motivo, leitura.instante()));
    }
}