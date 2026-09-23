package mininet.servidor;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Condiciones artificiales para los experimentos: el servidor descarta una fracción de los
 * mensajes que reenvía entre clientes (pérdida) y/o los demora (retardo).
 *
 * Solo afecta al reenvío entre clientes (MENSAJE, DIFUSION, GRUPO, PING, ACK, PONG), no a las
 * respuestas propias del servidor (OK, ERROR). Como el ACK también pasa por el servidor, un
 * retardo de R ms suma unos 2R ms a la latencia ida y vuelta que mide el cliente.
 */
class SimuladorRed {

    private final double probabilidadPerdida; // 0.0 = nunca, 1.0 = siempre
    private final int retardoMs;

    SimuladorRed(double probabilidadPerdida, int retardoMs) {
        this.probabilidadPerdida = probabilidadPerdida;
        this.retardoMs = retardoMs;
    }

    /** @return true si este mensaje debe "perderse". */
    boolean descartar() {
        return ThreadLocalRandom.current().nextDouble() < probabilidadPerdida;
    }

    void retrasar() {
        if (retardoMs <= 0) {
            return;
        }
        try {
            Thread.sleep(retardoMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "perdida simulada=%.0f%%, retardo=%d ms",
                probabilidadPerdida * 100, retardoMs);
    }
}
