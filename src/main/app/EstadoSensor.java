package app;

/** Estado de UM sensor dentro de uma execução (cada sensor pode ser ligado/desligado à parte). */
public enum EstadoSensor {
    /** A thread do sensor não está rodando (ainda não ligou, foi desligado ou o sistema parou). */
    DESLIGADO,
    /** Thread viva, produzindo leituras. */
    EXECUTANDO,
    /** Thread viva, mas esperando em pausa (não produz). */
    PAUSADO
}
