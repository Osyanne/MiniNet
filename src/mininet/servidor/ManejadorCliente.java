package mininet.servidor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import mininet.comun.Log;
import mininet.protocolo.Mensaje;
import mininet.protocolo.Tipo;

/**
 * Atiende a UN cliente en su propio hilo: lee sus mensajes línea por línea y aplica el
 * protocolo. Hay un ManejadorCliente por cada conexión TCP abierta.
 */
class ManejadorCliente implements Runnable {

    private static final String NOMBRE_VALIDO = "[A-Za-z0-9_-]+";

    private final Socket socket;
    private final Registro registro;
    private final SimuladorRed red;
    private PrintWriter salida;
    private String nombre; // null hasta que el cliente se registra
    private boolean salioOrdenadamente;
    private String motivoCaida = "conexion cerrada sin SALIR";

    ManejadorCliente(Socket socket, Registro registro, SimuladorRed red) {
        this.socket = socket;
        this.registro = registro;
        this.red = red;
    }

    String nombre() {
        return nombre;
    }

    @Override
    public void run() {
        String remoto = socket.getRemoteSocketAddress().toString();
        Log.info("Conexion TCP aceptada desde " + remoto);
        try {
            // Latido: si en LATIDO_TIMEOUT_MS no llega nada (ni siquiera un LATIDO), readLine lanza
            // SocketTimeoutException. Asi se detecta un equipo que desaparece sin cerrar la conexion
            // (cable o wifi desconectado), algo que TCP no avisa de inmediato.
            socket.setSoTimeout(Mensaje.LATIDO_TIMEOUT_MS);
            BufferedReader entrada = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            salida = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            String linea;
            while ((linea = entrada.readLine()) != null) {
                Mensaje m;
                try {
                    m = Mensaje.parsear(linea);
                } catch (IllegalArgumentException e) {
                    Log.info("Mensaje mal formado de " + remoto + ": " + linea);
                    enviar(new Mensaje(Tipo.ERROR, 0, Mensaje.SERVIDOR, "?", "mensaje mal formado"));
                    continue;
                }
                if (!procesar(m)) {
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            motivoCaida = "sin latido por " + Mensaje.LATIDO_TIMEOUT_MS / 1000 + " s";
        } catch (IOException e) {
            motivoCaida = "conexion interrumpida: " + e.getMessage();
        } finally {
            desconectar();
        }
    }

    /** Semántica de cada tipo de mensaje. @return false cuando el cliente pidió SALIR. */
    private boolean procesar(Mensaje m) {
        if (nombre == null && m.tipo() != Tipo.REGISTRO) {
            responder(m, Tipo.ERROR, "primero debe registrarse");
            return true;
        }
        switch (m.tipo()) {
            case REGISTRO -> registrar(m);
            case LISTAR -> responder(m, Tipo.OK, String.join(",", registro.nombres()));
            case ESTADO -> responder(m, Tipo.OK,
                    registro.buscar(m.destino()) != null ? "ACTIVO" : "NO_DISPONIBLE");
            case UNIR -> {
                registro.unir(m.destino(), nombre);
                responder(m, Tipo.OK, "unido a " + m.destino());
            }
            case MENSAJE, PING, RAFAGA, ACK, PONG -> reenviarA(m);
            case DIFUSION -> reenviarAVarios(m, registro.nombres());
            case GRUPO -> reenviarAVarios(m, registro.miembros(m.destino()));
            case SALIR -> {
                salioOrdenadamente = true;
                responder(m, Tipo.OK, "hasta luego");
                return false;
            }
            // Se devuelve para que el cliente tambien sepa que el servidor sigue ahi. No va al log:
            // llega cada 5 s por cliente y taparia los eventos importantes.
            case LATIDO -> enviar(m.responder(Tipo.LATIDO, Mensaje.SERVIDOR, ""));
            default -> responder(m, Tipo.ERROR, "tipo no permitido desde un cliente");
        }
        return true;
    }

    private void registrar(Mensaje m) {
        String solicitado = m.origen();
        if (nombre != null) {
            responder(m, Tipo.ERROR, "ya registrado como " + nombre);
        } else if (!solicitado.matches(NOMBRE_VALIDO) || solicitado.equals(Mensaje.SERVIDOR)) {
            responder(m, Tipo.ERROR, "nombre no valido (use letras, numeros, _ o -)");
        } else {
            nombre = solicitado; // antes de publicarlo en el registro, para que otros hilos lo vean
            if (!registro.registrar(nombre, this)) {
                nombre = null;
                responder(m, Tipo.ERROR, "el nombre " + solicitado + " ya esta en uso");
                return;
            }
            responder(m, Tipo.OK, "bienvenido " + nombre);
            avisarATodos(nombre + " se unio a MiniNet");
        }
    }

    /** Unicast lógico: entrega m a un único destinatario (MENSAJE, PING, RAFAGA y sus ACK/PONG). */
    private void reenviarA(Mensaje m) {
        ManejadorCliente receptor = registro.buscar(m.destino());
        if (receptor == null) {
            if (m.tipo() == Tipo.ACK || m.tipo() == Tipo.PONG) {
                // Quien esperaba la confirmacion ya se fue; no hay a quien avisar.
                registrarSiCorresponde(m, null, "DESCARTADO (destino ya no esta)");
            } else {
                responder(m, Tipo.ERROR, m.destino() + " no disponible");
            }
            return;
        }
        red.retrasar();
        entregar(receptor, m);
    }

    /**
     * Difusión y grupo: el servidor replica m a cada destinatario, uno por uno, por sus
     * conexiones TCP. Es difusión/multicast a nivel de aplicación, no broadcast/multicast IP.
     */
    private void reenviarAVarios(Mensaje m, Set<String> destinos) {
        red.retrasar();
        int replicas = 0;
        for (String destino : destinos) {
            ManejadorCliente receptor = registro.buscar(destino);
            if (receptor != null && receptor != this) {
                entregar(receptor, m);
                replicas++;
            }
        }
        // El contenido del OK es la cantidad de réplicas: el emisor espera ese número de ACK.
        responder(m, Tipo.OK, String.valueOf(replicas));
    }

    /** Escribe m en el socket del receptor, salvo que el simulador de red lo "pierda". */
    private void entregar(ManejadorCliente receptor, Mensaje m) {
        if (red.descartar()) {
            registrarSiCorresponde(m, receptor, "DESCARTADO (perdida simulada)");
            return;
        }
        // El servidor pone el origen real: un cliente no puede hacerse pasar por otro.
        receptor.enviar(new Mensaje(m.tipo(), m.id(), nombre, m.destino(), m.contenido()));
        registrarSiCorresponde(m, receptor, "REENVIADO");
    }

    /** Respuesta del servidor a quien envió m. Conserva el id para que el cliente la asocie. */
    private void responder(Mensaje m, Tipo tipo, String contenido) {
        enviar(m.responder(tipo, Mensaje.SERVIDOR, contenido));
        if (!m.esDeRafaga()) {
            String quien = nombre != null ? nombre : m.origen();
            Log.evento(quien, m.destino(), m.tipo(), m.id(), tipo + " " + contenido);
        }
    }

    /** Log de un reenvío. Los bloques de ráfaga y sus ACK no se registran: son miles. */
    private void registrarSiCorresponde(Mensaje m, ManejadorCliente receptor, String resultado) {
        if (!m.esDeRafaga()) {
            String destino = receptor != null ? receptor.nombre() : m.destino();
            Log.evento(nombre, destino, m.tipo(), m.id(), resultado);
        }
    }

    /** synchronized: varios hilos (uno por cada emisor) pueden escribirle a este cliente a la vez. */
    synchronized void enviar(Mensaje m) {
        salida.println(m.serializar());
    }

    private void avisarATodos(String texto) {
        for (ManejadorCliente otro : registro.todos()) {
            if (otro != this) {
                otro.enviar(new Mensaje(Tipo.AVISO, 0, Mensaje.SERVIDOR, otro.nombre(), texto));
            }
        }
    }

    private void desconectar() {
        try {
            socket.close();
        } catch (IOException ignorada) {
            // ya estaba cerrado
        }
        if (nombre == null) {
            Log.info("Conexion cerrada sin registro: " + socket.getRemoteSocketAddress());
            return;
        }
        registro.eliminar(nombre, this);
        Log.evento(nombre, Mensaje.SERVIDOR, "DESCONEXION", 0,
                salioOrdenadamente ? "SALIR" : "CAIDA (" + motivoCaida + ")");
        avisarATodos(nombre + (salioOrdenadamente ? " salio" : " se desconecto inesperadamente"));
    }
}
