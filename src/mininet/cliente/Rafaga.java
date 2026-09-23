package mininet.cliente;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import mininet.protocolo.Mensaje;
import mininet.protocolo.Tipo;

/**
 * Una ráfaga: N bloques de S bytes de datos enviados seguidos a un participante para medir
 * throughput. Cada bloque viaja como RAFAGA y el destinatario lo confirma con un ACK.
 *
 *     throughput = bytes de datos confirmados / tiempo entre el primer envío y el último ACK
 *
 * Termina cuando todos los bloques tienen respuesta, o cuando pasan Pendientes.TIMEOUT_MS sin que
 * llegue ninguna: los bloques que faltan cuentan como perdidos. Solo se usa desde los métodos
 * synchronized de Pendientes.
 */
class Rafaga {

    private final String destino;
    private final int primerId; // los ids primerId .. primerId+cantidad-1 son de esta ráfaga
    private final int cantidad;
    private final int bytesPorBloque;
    private final long bytesEnSocket;
    private final long inicioNanos = System.nanoTime();
    private long ultimaRespuestaNanos = inicioNanos;
    private long ultimoAckNanos;
    private int confirmados;
    private int errores;
    private String primerError;
    private boolean finalizada;
    private String resumenCorto;

    Rafaga(String origen, String destino, int primerId, int cantidad, int bytesPorBloque) {
        this.destino = destino;
        this.primerId = primerId;
        this.cantidad = cantidad;
        this.bytesPorBloque = bytesPorBloque;
        this.bytesEnSocket = calcularBytesEnSocket(origen);
    }

    String destino() {
        return destino;
    }

    int primerId() {
        return primerId;
    }

    boolean contiene(int id) {
        return id >= primerId && id < primerId + cantidad;
    }

    boolean finalizada() {
        return finalizada;
    }

    /** @return true si con esta respuesta ya respondieron todos los bloques. */
    boolean respuesta(Mensaje r) {
        if (finalizada) {
            return false; // llegó tarde: ese bloque ya se contó como perdido
        }
        ultimaRespuestaNanos = System.nanoTime();
        if (r.tipo() == Tipo.ACK) {
            confirmados++;
            ultimoAckNanos = ultimaRespuestaNanos;
        } else if (r.tipo() == Tipo.ERROR) {
            errores++;
            if (primerError == null) {
                primerError = r.contenido();
            }
        }
        return confirmados + errores == cantidad;
    }

    boolean sinRespuestaHace(long ms) {
        return (System.nanoTime() - ultimaRespuestaNanos) / 1_000_000 >= ms;
    }

    /** Cierra la ráfaga y devuelve el resultado completo para el log. */
    String finalizar() {
        finalizada = true;
        long bytesDatos = (long) confirmados * bytesPorBloque;
        long datosEnviados = (long) cantidad * bytesPorBloque;
        StringBuilder texto = new StringBuilder(confirmados + "/" + cantidad + " bloques confirmados");
        if (confirmados > 0) {
            double segundos = (ultimoAckNanos - inicioNanos) / 1e9;
            double mbps = bytesDatos * 8 / segundos / 1e6;
            texto.append(String.format(Locale.ROOT, " en %.3f s: throughput %.2f Mbit/s (%.0f bloques/s, %d B de datos)",
                    segundos, mbps, confirmados / segundos, bytesDatos));
            resumenCorto = String.format(Locale.ROOT, "%.2f Mbit/s (%d/%d)", mbps, confirmados, cantidad);
        } else {
            resumenCorto = "sin confirmaciones (0/" + cantidad + ")";
        }
        texto.append(String.format(Locale.ROOT, "; %d B escritos en el socket, cabecera MiniNet +%.1f %%",
                bytesEnSocket, 100.0 * (bytesEnSocket - datosEnviados) / datosEnviados));
        int perdidos = cantidad - confirmados - errores;
        if (perdidos > 0) {
            texto.append("; perdidos ").append(perdidos);
        }
        if (errores > 0) {
            texto.append("; errores ").append(errores).append(" (").append(primerError).append(")");
        }
        return texto.toString();
    }

    /** Throughput y bloques confirmados, para el comando resumen. */
    String resumenCorto() {
        return resumenCorto;
    }

    /**
     * Bytes que la aplicación escribe en el socket para toda la ráfaga. Cada bloque es la línea
     * "RAFAGA|id|origen|destino|datos" más el fin de línea de println ("\r\n" en Windows).
     * TCP, IP y Ethernet agregan sus propias cabeceras encima: eso se ve en Wireshark.
     */
    private long calcularBytesEnSocket(String origen) {
        long fijo = (Tipo.RAFAGA.name() + origen + destino).getBytes(StandardCharsets.UTF_8).length
                + 4 // separadores '|'
                + bytesPorBloque
                + System.lineSeparator().length();
        long total = 0;
        for (int id = primerId; id < primerId + cantidad; id++) {
            total += fijo + String.valueOf(id).length();
        }
        return total;
    }
}
