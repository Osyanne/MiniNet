package mininet.cliente;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import mininet.comun.Log;
import mininet.protocolo.Mensaje;
import mininet.protocolo.Tipo;

/**
 * Cliente MiniNet de consola. Uso:
 *
 *   java -cp out mininet.cliente.Cliente <ipServidor> <puerto> <nombre>
 *
 * Ej.: java -cp out mininet.cliente.Cliente 192.168.1.10 5000 cliente01
 *
 * Usa dos hilos: el principal lee comandos del teclado y el Receptor escucha al servidor.
 */
public class Cliente {

    private static final String AYUDA = """
            Comandos:
              lista                   participantes conectados
              msg <nombre> <texto>    mensaje individual (unicast)
              todos <texto>           difusion a todos los conectados
              unir <grupo>            unirse a un grupo
              grupo <grupo> <texto>   mensaje a un grupo (multicast logico)
              estado <nombre>         consultar si un participante esta activo
              ping <nombre>           medir latencia extremo a extremo
              rafaga <nombre> <cantidad> <bytes>
                                      medir throughput: envia <cantidad> bloques de <bytes> seguidos
              resumen                 metricas de esta sesion
              salir
            """;

    private static final int MAX_BLOQUES = 100_000;
    private static final int MAX_BYTES_BLOQUE = 65_536;

    private final String nombre;
    private final BufferedReader entrada;
    private final PrintWriter salida;
    private final Pendientes pendientes;
    private final AtomicInteger siguienteId = new AtomicInteger(1);
    private final ScheduledExecutorService latido = Executors.newSingleThreadScheduledExecutor(tarea -> {
        Thread hilo = new Thread(tarea, "latido");
        hilo.setDaemon(true);
        return hilo;
    });
    private volatile boolean saliendo;

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 3) {
            System.out.println("Uso: java -cp out mininet.cliente.Cliente <ipServidor> <puerto> <nombre>");
            return;
        }
        String nombre = args[2];
        Log.iniciar(nombre + ".log");
        try (Socket socket = new Socket(args[0], Integer.parseInt(args[1]))) {
            // Direccion y puerto local: sirven para ubicar esta conexion en Wireshark.
            Log.info("Conectado a " + socket.getRemoteSocketAddress() + " desde " + socket.getLocalSocketAddress());
            Cliente cliente = new Cliente(nombre, socket);
            if (cliente.registrarse()) {
                cliente.ejecutar();
            }
        } catch (ConnectException e) {
            Log.info("No se pudo conectar con " + args[0] + ":" + args[1] + " (" + e.getMessage() + ")");
        } catch (SocketTimeoutException e) {
            Log.info("El servidor no respondio al registro en " + Mensaje.LATIDO_TIMEOUT_MS / 1000 + " s");
        }
    }

    Cliente(String nombre, Socket socket) throws IOException {
        this.nombre = nombre;
        // Latido: el servidor contesta cada LATIDO, asi que si pasan LATIDO_TIMEOUT_MS sin recibir
        // nada, las lecturas lanzan SocketTimeoutException: el servidor ya no esta.
        socket.setSoTimeout(Mensaje.LATIDO_TIMEOUT_MS);
        this.entrada = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.salida = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        this.pendientes = new Pendientes(nombre);
    }

    String nombre() {
        return nombre;
    }

    boolean saliendo() {
        return saliendo;
    }

    /** Primer intercambio del protocolo: REGISTRO -> OK | ERROR, antes de arrancar el Receptor. */
    private boolean registrarse() throws IOException {
        enviar(new Mensaje(Tipo.REGISTRO, siguienteId.getAndIncrement(), nombre, Mensaje.SERVIDOR, ""));
        while (true) {
            String linea = entrada.readLine();
            if (linea == null) {
                Log.info("El servidor cerro la conexion durante el registro");
                return false;
            }
            Mensaje r = Mensaje.parsear(linea);
            if (r.tipo() == Tipo.OK || r.tipo() == Tipo.ERROR) {
                Log.evento(r.origen(), nombre, r.tipo(), r.id(), r.contenido());
                return r.tipo() == Tipo.OK;
            }
            // Un AVISO de otro participante puede adelantarse a la respuesta: se ignora.
        }
    }

    private void ejecutar() throws InterruptedException {
        latido.scheduleAtFixedRate(this::enviarLatido,
                Mensaje.LATIDO_INTERVALO_MS, Mensaje.LATIDO_INTERVALO_MS, TimeUnit.MILLISECONDS);
        Thread receptor = new Thread(new Receptor(this, entrada, pendientes), "receptor");
        receptor.setDaemon(true);
        receptor.start();

        System.out.print(AYUDA);
        Scanner teclado = new Scanner(System.in, charsetConsola());
        while (teclado.hasNextLine()) {
            String[] partes = teclado.nextLine().trim().split("\\s+", 2);
            String comando = partes[0].toLowerCase();
            String resto = partes.length > 1 ? partes[1] : "";
            String[] args = resto.split("\\s+", 2); // args[0] = destino o grupo
            String texto = args.length > 1 ? args[1] : "";
            switch (comando) {
                case "" -> { }
                case "lista" -> solicitar(Tipo.LISTAR, Mensaje.SERVIDOR, "");
                case "msg" -> solicitar(Tipo.MENSAJE, args[0], texto);
                case "todos" -> solicitar(Tipo.DIFUSION, Mensaje.TODOS, resto);
                case "unir" -> solicitar(Tipo.UNIR, args[0], "");
                case "grupo" -> solicitar(Tipo.GRUPO, args[0], texto);
                case "estado" -> solicitar(Tipo.ESTADO, args[0], "");
                case "ping" -> solicitar(Tipo.PING, args[0], "");
                case "rafaga" -> rafaga(args[0], texto);
                case "resumen" -> System.out.println(pendientes.resumen());
                case "ayuda" -> System.out.print(AYUDA);
                case "salir" -> {
                    salir(receptor);
                    return;
                }
                default -> System.out.println("Comando desconocido. Escriba 'ayuda'.");
            }
        }
        salir(receptor); // se cerro la entrada (Ctrl+Z / Ctrl+D)
    }

    /** Crea un mensaje nuevo, lo anota como pendiente de respuesta y lo envía. */
    private void solicitar(Tipo tipo, String destino, String contenido) {
        Mensaje m = new Mensaje(tipo, siguienteId.getAndIncrement(), nombre, destino, contenido);
        pendientes.registrar(m);
        enviar(m);
    }

    /**
     * Envía bloques de datos seguidos, sin esperar los ACK, para medir throughput. Pendientes
     * recibe las confirmaciones y registra el resultado cuando la ráfaga termina.
     */
    private void rafaga(String destino, String parametros) {
        int cantidad;
        int bytes;
        try {
            String[] p = parametros.split("\\s+");
            cantidad = Integer.parseInt(p[0]);
            bytes = Integer.parseInt(p[1]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            System.out.println("Uso: rafaga <nombre> <cantidad> <bytes>   (ej.: rafaga cliente02 1000 1000)");
            return;
        }
        if (cantidad < 1 || cantidad > MAX_BLOQUES || bytes < 1 || bytes > MAX_BYTES_BLOQUE) {
            System.out.println("Cantidad entre 1 y " + MAX_BLOQUES + ", bytes entre 1 y " + MAX_BYTES_BLOQUE);
            return;
        }
        if (pendientes.rafagaEnCurso()) {
            System.out.println("Ya hay una rafaga en curso; espere su resultado.");
            return;
        }
        String datos = "x".repeat(bytes);
        int primerId = siguienteId.getAndAdd(cantidad); // ids consecutivos reservados para la ráfaga
        Log.evento(nombre, destino, Tipo.RAFAGA, primerId, "INICIO " + cantidad + " bloques de " + bytes + " B");
        pendientes.iniciarRafaga(new Rafaga(nombre, destino, primerId, cantidad, bytes));
        for (int i = 0; i < cantidad; i++) {
            escribir(new Mensaje(Tipo.RAFAGA, primerId + i, nombre, destino, datos));
        }
    }

    /** Envía y registra en el log. */
    synchronized void enviar(Mensaje m) {
        escribir(m);
        Log.evento(nombre, m.destino(), m.tipo(), m.id(), "ENVIADO");
    }

    /**
     * Envía sin registrar: latidos y bloques de ráfaga, que son demasiados para el log.
     * synchronized: el hilo principal, el Receptor (ACK/PONG) y el latido usan el mismo socket.
     */
    synchronized void escribir(Mensaje m) {
        salida.println(m.serializar());
    }

    /** Sale cada 5 s: no va al log porque taparia los eventos importantes. En Wireshark si se ve. */
    private void enviarLatido() {
        escribir(new Mensaje(Tipo.LATIDO, 0, nombre, Mensaje.SERVIDOR, ""));
    }

    private void salir(Thread receptor) throws InterruptedException {
        saliendo = true;
        latido.shutdownNow();
        solicitar(Tipo.SALIR, Mensaje.SERVIDOR, "");
        receptor.join(2000); // espera el OK del servidor y el cierre de la conexion
        Log.info(pendientes.resumen());
    }

    /** Charset del teclado, para que los acentos escritos en la consola lleguen bien. */
    private static Charset charsetConsola() {
        return System.console() != null ? System.console().charset() : Charset.defaultCharset();
    }
}
