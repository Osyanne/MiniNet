package mininet.protocolo;

/**
 * Un mensaje del protocolo MiniNet. Por la red viaja como UNA línea de texto (UTF-8):
 *
 *     TIPO|id|origen|destino|contenido\n
 *
 * Ej.: MENSAJE|7|cliente01|cliente02|Hola
 *      ACK|7|cliente02|cliente01|
 *
 * - id: número que pone quien origina el mensaje. Las respuestas (ACK, PONG, OK, ERROR) repiten
 *   el id de la petición: así el emisor sabe a qué responden y puede medir la latencia. También
 *   sirve para encontrar el mensaje en una captura de Wireshark.
 * - destino: nombre de un cliente, "*" (todos), el nombre de un grupo o "SERVIDOR".
 * - contenido: texto libre. Puede contener '|' porque es el último campo.
 */
public record Mensaje(Tipo tipo, int id, String origen, String destino, String contenido) {

    public static final String SERVIDOR = "SERVIDOR";
    public static final String TODOS = "*";

    /**
     * Temporización del latido: el cliente envía LATIDO al servidor cada LATIDO_INTERVALO_MS y el
     * servidor lo devuelve. Si un extremo pasa LATIDO_TIMEOUT_MS sin recibir nada (3 latidos
     * perdidos), da al otro por caído.
     */
    public static final int LATIDO_INTERVALO_MS = 5000;
    public static final int LATIDO_TIMEOUT_MS = 15000;

    public String serializar() {
        return tipo + "|" + id + "|" + origen + "|" + destino + "|" + contenido;
    }

    /** @throws IllegalArgumentException si la línea no cumple la sintaxis del protocolo. */
    public static Mensaje parsear(String linea) {
        String[] campos = linea.split("\\|", 5); // límite 5: los '|' del contenido no se cortan
        if (campos.length != 5) {
            throw new IllegalArgumentException("se esperaban 5 campos: " + linea);
        }
        return new Mensaje(Tipo.valueOf(campos[0]), Integer.parseInt(campos[1]),
                campos[2], campos[3], campos[4]);
    }

    /** Respuesta dirigida al origen de este mensaje, con el mismo id (ACK, PONG, OK, ERROR). */
    public Mensaje responder(Tipo tipo, String quienResponde, String contenido) {
        return new Mensaje(tipo, id, quienResponde, origen, contenido);
    }
}
