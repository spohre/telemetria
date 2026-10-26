package domain;

/**
 * Fonte dos valores medidos por um sensor. Cada sensor tem a sua instância,
 * usada só pela thread daquele sensor.
 */
@FunctionalInterface
public interface GeradorDeValores {

    double proximoValor();
}
