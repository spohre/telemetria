package app;

import domain.Severidade;
import domain.TipoSensor;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Contadores de uma execução, compartilhados por todos os consumidores.
 *
 * <p>Thread-safe sem locks: só tipos atômicos ({@link AtomicLong}, {@link LongAdder}) e uma
 * matriz tipo × severidade criada inteira no construtor (nunca muda de forma depois,
 * então pode ser lida por qualquer thread). Cada incremento é independente — não existe
 * operação que precise de dois contadores ao mesmo tempo, então não há lock a segurar.</p>
 */
public class ContadoresExecucao {

    private final AtomicLong processadas = new AtomicLong();
    private final AtomicLong alertasSalvos = new AtomicLong();
    private final AtomicLong alertasNaoSalvos = new AtomicLong();
    private final AtomicBoolean bancoDisponivel = new AtomicBoolean(true);
    private final AtomicBoolean logDisponivel = new AtomicBoolean(true);
    private final Map<TipoSensor, Map<Severidade, LongAdder>> alertasPorTipo = new EnumMap<>(TipoSensor.class);

    public ContadoresExecucao() {
        for (TipoSensor tipo : TipoSensor.values()) {
            Map<Severidade, LongAdder> porSeveridade = new EnumMap<>(Severidade.class);
            for (Severidade s : Severidade.values()) {
                porSeveridade.put(s, new LongAdder());
            }
            alertasPorTipo.put(tipo, porSeveridade);
        }
    }

    void leituraProcessada() {
        processadas.incrementAndGet();
    }

    void alertaGerado(TipoSensor tipo, Severidade severidade) {
        alertasPorTipo.get(tipo).get(severidade).increment();
    }

    void alertaSalvo() {
        alertasSalvos.incrementAndGet();
    }

    void alertaNaoSalvo() {
        alertasNaoSalvos.incrementAndGet();
    }

    /** Marca o estado do banco e devolve o estado anterior (para avisar só na mudança). */
    boolean marcarBancoDisponivel(boolean disponivel) {
        return bancoDisponivel.getAndSet(disponivel);
    }

    /** Marca o estado do log e devolve o estado anterior (para avisar só na mudança). */
    boolean marcarLogDisponivel(boolean disponivel) {
        return logDisponivel.getAndSet(disponivel);
    }

    public boolean logDisponivel() {
        return logDisponivel.get();
    }

    public long processadas() {
        return processadas.get();
    }

    public long alertasGerados() {
        return alertasSalvos.get() + alertasNaoSalvos.get();
    }

    public long alertasSalvos() {
        return alertasSalvos.get();
    }

    public long alertasNaoSalvos() {
        return alertasNaoSalvos.get();
    }

    public boolean bancoDisponivel() {
        return bancoDisponivel.get();
    }

    /** Cópia (fotografia) da matriz tipo × severidade, para o extrato. */
    public Map<TipoSensor, Map<Severidade, Long>> alertasPorTipo() {
        Map<TipoSensor, Map<Severidade, Long>> copia = new EnumMap<>(TipoSensor.class);
        alertasPorTipo.forEach((tipo, porSev) -> {
            Map<Severidade, Long> m = new EnumMap<>(Severidade.class);
            porSev.forEach((sev, n) -> m.put(sev, n.sum()));
            copia.put(tipo, m);
        });
        return copia;
    }
}
