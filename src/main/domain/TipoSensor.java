package domain;

/** Tipos de sensor suportados. Os nomes batem com o CHECK da coluna alerta.tipo_sensor. */
public enum TipoSensor {
    TEMPERATURA("Temperatura", "°C"),
    VELOCIDADE("Velocidade", "km/h"),
    PRESSAO("Pressão", "bar"),
    UMIDADE("Umidade", "%");

    private final String nome;
    private final String unidade;

    TipoSensor(String nome, String unidade) {
        this.nome = nome;
        this.unidade = unidade;
    }

    /** Nome legível para telas e relatórios. */
    public String nome() {
        return nome;
    }

    /** Unidade de medida do valor lido. */
    public String unidade() {
        return unidade;
    }
}
