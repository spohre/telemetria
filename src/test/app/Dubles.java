package app;

import domain.Alerta;
import domain.LeituraTelemetria;
import domain.RepositorioDeAlertas;
import domain.Severidade;
import domain.TipoSensor;
import infra.PersistenciaException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Dublês (fakes) compartilhados pelos testes do consumidor. Thread-safe. */
final class Dubles {

    private Dubles() {
    }

    /** Repositório em memória que pode ser "desligado" para simular banco fora do ar. */
    static class RepositorioFalso implements RepositorioDeAlertas {
        final List<Alerta> salvos = new CopyOnWriteArrayList<>();
        final AtomicBoolean fora = new AtomicBoolean(false);
        final AtomicLong proximoId = new AtomicLong(1);

        @Override
        public Alerta salvar(Alerta alerta) {
            if (fora.get()) {
                throw new PersistenciaException("Falha ao salvar alerta", new RuntimeException("conexão recusada"));
            }
            Alerta salvo = alerta.comId(proximoId.getAndIncrement());
            salvos.add(salvo);
            return salvo;
        }

        /** Filtra em memória, como o banco faria; guarda o último "desde" recebido. */
        @Override
        public List<Alerta> buscar(String sensorId, TipoSensor tipo, Severidade severidade, Instant desde) {
            ultimoDesde = desde;
            if (fora.get()) {
                throw new PersistenciaException("Falha ao buscar", new RuntimeException("conexão recusada"));
            }
            List<Alerta> r = new ArrayList<>();
            for (Alerta a : salvos) {
                if ((sensorId == null || sensorId.equals(a.sensorId()))
                        && (tipo == null || tipo == a.tipo())
                        && (severidade == null || severidade == a.severidade())
                        && (desde == null || !a.momentoLeitura().isBefore(desde))) {
                    r.add(0, a);
                }
            }
            return r;
        }

        volatile Instant ultimoDesde;
    }

    static class OuvinteFalso implements OuvinteTelemetria {
        record Processada(LeituraTelemetria leitura, Alerta alerta) { }
        record Falha(String oQue, String fazer, Throwable causa) { }

        final List<Processada> processadas = new CopyOnWriteArrayList<>();
        final List<Falha> falhas = new CopyOnWriteArrayList<>();

        @Override
        public void aoProcessarLeitura(LeituraTelemetria leitura, Alerta alerta) {
            processadas.add(new Processada(leitura, alerta));
        }

        @Override
        public void aoAtualizarEstatisticas(EstatisticasExecucao estatisticas) {
        }

        @Override
        public void aoOcorrerFalha(String oQueAconteceu, String oQueFazer, Throwable causa) {
            falhas.add(new Falha(oQueAconteceu, oQueFazer, causa));
        }
    }

}
