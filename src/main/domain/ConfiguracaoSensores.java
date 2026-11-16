package domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuração dos sensores: quais sensores existem, a faixa aceita de cada um, a faixa
 * padrão de cada tipo e o intervalo entre leituras.
 *
 * <ul>
 *   <li>A <b>faixa padrão</b> de um tipo só decide como os sensores <i>novos</i> nascem;
 *       mudar o padrão não altera os sensores que já existem.</li>
 *   <li>Cada sensor tem a <b>própria faixa</b>, usada pelo {@link AnalisadorDeAnomalias} e
 *       pelo gerador de valores daquele sensor.</li>
 *   <li>Os sensores ficam na ordem em que foram criados.</li>
 * </ul>
 *
 * <p>Imutável: toda alteração ({@code com...}) devolve uma configuração nova e deixa a
 * original intacta. Por isso pode ser lida por várias threads sem lock. Valida tudo no
 * construtor, com mensagens prontas para mostrar na tela.</p>
 *
 * <p>{@link #padrao()}: 4 sensores (TEMP-01, VEL-01, PRES-01, UMID-01), 200 ms, faixas
 * padrão do projeto ({@link RegraDeAnomalia#padraoDo}).</p>
 */
public record ConfiguracaoSensores(int intervaloMs,
                                   Map<TipoSensor, RegraDeAnomalia> padroes,
                                   List<SensorConfigurado> sensores) {

    public static final int INTERVALO_PADRAO_MS = 200;
    public static final int INTERVALO_MINIMO_MS = 10;
    public static final int INTERVALO_MAXIMO_MS = 5000;

    public ConfiguracaoSensores {
        if (intervaloMs < INTERVALO_MINIMO_MS || intervaloMs > INTERVALO_MAXIMO_MS) {
            throw new IllegalArgumentException("O intervalo entre leituras deve ficar entre "
                    + INTERVALO_MINIMO_MS + " e " + INTERVALO_MAXIMO_MS + " ms");
        }
        Objects.requireNonNull(padroes, "padroes");
        Objects.requireNonNull(sensores, "sensores");

        EnumMap<TipoSensor, RegraDeAnomalia> copiaPadroes = new EnumMap<>(TipoSensor.class);
        padroes.forEach((tipo, regra) -> {
            Objects.requireNonNull(regra, "padrão de " + tipo);
            if (regra.tipo() != tipo) {
                throw new IllegalArgumentException("A faixa padrão de " + tipo.nome()
                        + " está registrada com o tipo " + regra.tipo().nome());
            }
            copiaPadroes.put(tipo, regra);
        });
        for (TipoSensor tipo : TipoSensor.values()) {
            if (!copiaPadroes.containsKey(tipo)) {
                throw new IllegalArgumentException("Falta a faixa padrão de " + tipo.nome());
            }
        }

        if (sensores.isEmpty()) {
            throw new IllegalArgumentException("A configuração precisa de pelo menos um sensor");
        }
        Set<String> ids = new HashSet<>();
        for (SensorConfigurado s : sensores) {
            Objects.requireNonNull(s, "sensor");
            if (!ids.add(s.id())) {
                throw new IllegalArgumentException("Há dois sensores com o id " + s.id());
            }
        }

        padroes = Collections.unmodifiableMap(copiaPadroes);   // ordem do enum
        sensores = List.copyOf(sensores);
    }

    /** 4 sensores, um de cada tipo, 200 ms, faixas padrão do projeto. */
    public static ConfiguracaoSensores padrao() {
        List<SensorConfigurado> sensores = new ArrayList<>();
        for (TipoSensor tipo : TipoSensor.values()) {
            sensores.add(new SensorConfigurado(idDe(tipo, 1), RegraDeAnomalia.padraoDo(tipo)));
        }
        return new ConfiguracaoSensores(INTERVALO_PADRAO_MS, RegraDeAnomalia.padroes(), sensores);
    }

    // ---------------------------------------------------------------- consultas

    /** Faixa padrão do tipo: a que os sensores novos desse tipo recebem. */
    public RegraDeAnomalia padraoDo(TipoSensor tipo) {
        return padroes.get(Objects.requireNonNull(tipo, "tipo"));
    }

    /** Sensores de um tipo, na ordem de criação. Lista imutável. */
    public List<SensorConfigurado> sensoresDo(TipoSensor tipo) {
        return sensores.stream().filter(s -> s.tipo() == tipo).toList();
    }

    /** Faixa de um sensor, ou vazio se não existe sensor com esse id. */
    public Optional<RegraDeAnomalia> regraDo(String sensorId) {
        return sensores.stream().filter(s -> s.id().equals(sensorId)).map(SensorConfigurado::regra).findFirst();
    }

    /** Id que o próximo sensor do tipo vai receber: maior número já usado + 1 (TEMP-03 depois de TEMP-02). */
    public String proximoIdDo(TipoSensor tipo) {
        Pattern formato = Pattern.compile(Pattern.quote(tipo.prefixo()) + "-(\\d+)");
        int maior = 0;
        for (SensorConfigurado s : sensores) {
            Matcher m = formato.matcher(s.id());
            if (m.matches()) {
                maior = Math.max(maior, Integer.parseInt(m.group(1)));
            }
        }
        return idDe(tipo, maior + 1);
    }

    // ---------------------------------------------------------------- alterações (sempre uma cópia nova)

    /** Acrescenta um sensor do tipo, com o próximo id livre e a faixa padrão vigente do tipo. */
    public ConfiguracaoSensores comSensorNovo(TipoSensor tipo) {
        List<SensorConfigurado> novos = new ArrayList<>(sensores);
        novos.add(new SensorConfigurado(proximoIdDo(tipo), padraoDo(tipo)));
        return new ConfiguracaoSensores(intervaloMs, padroes, novos);
    }

    /** Muda a faixa de um sensor; os outros sensores e o padrão do tipo não mudam. */
    public ConfiguracaoSensores comFaixaDoSensor(String sensorId, double minimo, double maximo) {
        List<SensorConfigurado> novos = new ArrayList<>(sensores.size());
        boolean achou = false;
        for (SensorConfigurado s : sensores) {
            if (s.id().equals(sensorId)) {
                novos.add(s.comFaixa(minimo, maximo));
                achou = true;
            } else {
                novos.add(s);
            }
        }
        if (!achou) {
            throw new IllegalArgumentException("O sensor " + sensorId + " não existe na configuração");
        }
        return new ConfiguracaoSensores(intervaloMs, padroes, novos);
    }

    /**
     * Tira um sensor da configuração; os outros (e a ordem deles) não mudam. Pode deixar um tipo
     * sem sensores (o padrão do tipo continua, para sensores novos), mas não a configuração inteira.
     */
    public ConfiguracaoSensores semSensor(String sensorId) {
        List<SensorConfigurado> restantes = new ArrayList<>(sensores);
        if (!restantes.removeIf(s -> s.id().equals(sensorId))) {
            throw new IllegalArgumentException("O sensor " + sensorId + " não existe na configuração");
        }
        if (restantes.isEmpty()) {
            throw new IllegalArgumentException("Não é possível remover " + sensorId
                    + ": a configuração precisa de pelo menos um sensor");
        }
        return new ConfiguracaoSensores(intervaloMs, padroes, restantes);
    }

    /** Muda a faixa padrão do tipo. Só vale para sensores criados depois; os existentes não mudam. */
    public ConfiguracaoSensores comFaixaPadrao(TipoSensor tipo, double minimo, double maximo) {
        EnumMap<TipoSensor, RegraDeAnomalia> novos = new EnumMap<>(padroes);
        novos.put(tipo, new RegraDeAnomalia(tipo, minimo, maximo));
        return new ConfiguracaoSensores(intervaloMs, novos, sensores);
    }

    /** Muda o intervalo entre leituras (o mesmo para todos os sensores). */
    public ConfiguracaoSensores comIntervalo(int novoIntervaloMs) {
        return new ConfiguracaoSensores(novoIntervaloMs, padroes, sensores);
    }

    /** Limite de {@link #comQuantidadePorTipo} (o mesmo que o modo console sempre aceitou). */
    public static final int MAXIMO_POR_TIPO = 10;

    /**
     * Exatamente {@code n} sensores de cada tipo, agrupados por tipo: mantém os primeiros que já
     * existem (com as faixas deles) e cria os que faltam com a faixa padrão vigente. Usado pelo
     * modo console ({@code --sensores N}) e pelos testes.
     */
    public ConfiguracaoSensores comQuantidadePorTipo(int n) {
        if (n < 1 || n > MAXIMO_POR_TIPO) {
            throw new IllegalArgumentException("Sensores por tipo deve ficar entre 1 e " + MAXIMO_POR_TIPO);
        }
        ConfiguracaoSensores c = this;
        for (TipoSensor tipo : TipoSensor.values()) {
            while (c.sensoresDo(tipo).size() < n) {
                c = c.comSensorNovo(tipo);
            }
        }
        List<SensorConfigurado> agrupados = new ArrayList<>();
        for (TipoSensor tipo : TipoSensor.values()) {
            agrupados.addAll(c.sensoresDo(tipo).subList(0, n));
        }
        return new ConfiguracaoSensores(intervaloMs, padroes, agrupados);
    }

    private static String idDe(TipoSensor tipo, int numero) {
        return String.format("%s-%02d", tipo.prefixo(), numero);
    }
}
