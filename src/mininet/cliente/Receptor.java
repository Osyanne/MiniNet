package mininet.cliente;

import java.io.BufferedReader;
import java.io.IOException;

import mininet.comun.Log;
import mininet.protocolo.Mensaje;
import mininet.protocolo.Tipo;

/**
 * Hilo que escucha al servidor todo el tiempo, mientras el hilo principal espera comandos.
 * Muestra lo que llega, confirma cada mensaje recibido (ACK/PONG) y pasa las respuestas a
 * Pendientes para medir latencia y pérdida.
 */
class Receptor implements Runnable {

    private final Cliente cliente;
    private final BufferedReader entrada;
    private final Pendientes pendientes;

    Receptor(Cliente cliente, BufferedReader entrada, Pendientes pendientes) {
        this.cliente = cliente;
        this.entrada = entrada;
        this.pendientes = pendientes;
    }

    @Override
    public void run() {
        try {
            String linea;
            while ((linea = entrada.readLine()) != null) {
                try {
                    procesar(Mensaje.parsear(linea));
                } catch (IllegalArgumentException e) {
                    Log.info("Mensaje mal formado del servidor: " + linea);
                }
            }
        } catch (IOException e) {
            // Conexion cortada; se informa abajo.
        }
        if (!cliente.saliendo()) {
            Log.info("Se perdio la conexion con el servidor");
            System.exit(1);
        }
    }

    private void procesar(Mensaje m) {
        switch (m.tipo()) {
            case MENSAJE, DIFUSION, GRUPO -> {
                Log.evento(m.origen(), cliente.nombre(), m.tipo(), m.id(), "RECIBIDO");
                System.out.println("   [" + etiqueta(m) + "] " + m.origen() + ": " + m.contenido());
                cliente.enviar(m.responder(Tipo.ACK, cliente.nombre(), ""));
            }
            case PING -> {
                Log.evento(m.origen(), cliente.nombre(), m.tipo(), m.id(), "RECIBIDO");
                cliente.enviar(m.responder(Tipo.PONG, cliente.nombre(), ""));
            }
            case ACK, PONG, OK, ERROR -> pendientes.respuesta(m);
            case AVISO -> Log.evento(m.origen(), cliente.nombre(), m.tipo(), m.id(), m.contenido());
            default -> Log.info("Tipo de mensaje inesperado: " + m.tipo());
        }
    }

    private static String etiqueta(Mensaje m) {
        return switch (m.tipo()) {
            case DIFUSION -> "a todos";
            case GRUPO -> "grupo " + m.destino();
            default -> "privado";
        };
    }
}
