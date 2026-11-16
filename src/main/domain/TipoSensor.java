package domain;

/** Tipos de sensor suportados. Os nomes batem com o CHECK da coluna alerta.tipo_sensor. */
public enum TipoSensor {
    TEMPERATURA("Temperatura", "°C", "TEMP"),
    VELOCIDADE("Velocidade", "km/h", "VEL"),
    PRESSAO("Pressão", "bar", "PRES"),
    UMIDADE("Umidade", "%", "UMID");

    private final String nome;
    private final String unidade;
    private final String prefixo;

    TipoSensor(String nome, String unidade, String prefixo) {
        this.nome = nome;
        this.unidade = unidade;
        this.prefixo = prefixo;
    }

    /** Nome legível para telas e relatórios. */
    public String nome() {
        return nome;
    }

    /** Unidade de medida do valor lido. */
    public String unidade() {
        return unidade;
    }

    /** Prefixo do id dos sensores deste tipo (TEMP → TEMP-01, TEMP-02…). */
    public String prefixo() {
        return prefixo;
    }
}
