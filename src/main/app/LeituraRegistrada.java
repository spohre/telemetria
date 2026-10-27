package app;

import domain.LeituraTelemetria;
import domain.Severidade;

import java.util.Objects;

/**
 * Uma leitura já processada, como ficou registrada no log: a medição e, se ela gerou
 * alerta, a severidade. Usada para redesenhar o histórico de uma execução que já terminou.
 *
 * @param severidade severidade do alerta, ou {@code null} quando a leitura estava dentro da faixa
 */
public record LeituraRegistrada(LeituraTelemetria leitura, Severidade severidade) {

    public LeituraRegistrada {
        Objects.requireNonNull(leitura, "leitura");
    }
}
