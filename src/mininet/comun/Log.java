package mininet.comun;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Registro de eventos de MiniNet. Cada línea sale por consola y se agrega a un archivo .log,
 * que es la evidencia para el informe. La hora lleva milisegundos y cada mensaje su id, para
 * poder cruzar una línea del log con el paquete correspondiente en Wireshark.
 */
public final class Log {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static PrintWriter archivo;

    private Log() {
    }

    public static synchronized void iniciar(String rutaArchivo) {
        try {
            archivo = new PrintWriter(new FileWriter(rutaArchivo, StandardCharsets.UTF_8, true), true);
        } catch (IOException e) {
            System.err.println("No se pudo abrir el log " + rutaArchivo + ": " + e.getMessage());
        }
    }

    /** Evento de comunicación. Ej.: 10:34:25.120 cliente01 -> cliente02 MENSAJE id=7 ENVIADO */
    public static void evento(String origen, String destino, Object tipo, int id, String resultado) {
        escribir(origen + " -> " + destino + " " + tipo + " id=" + id + " " + resultado);
    }

    /** Todo lo que no es una comunicación: arranque, conexiones TCP, errores. */
    public static void info(String texto) {
        escribir("[info] " + texto);
    }

    private static synchronized void escribir(String texto) {
        String linea = LocalTime.now().format(HORA) + " " + texto;
        System.out.println(linea);
        if (archivo != null) {
            archivo.println(linea);
        }
    }
}
