package mininet.cliente;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
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
              resumen                 metricas de esta sesion
              salir
            """;

    private final String nombre;
    private final BufferedReader entrada;
    private final PrintWriter salida;
    private final Pendientes pendientes;
    private final AtomicInteger siguienteId = new AtomicInteger(1);
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
        }
    }

    Cliente(String nombre, Socket socket) throws IOException {
        this.nombre = nombre;
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
                case "resumen" -> System.out.println(pendientes.resumen());
                case "ayuda" -> System.out.print(AYUDA);
                case "salir" -> {
                    salir(receptor);
                    return;
                }
                // TODO (grupo): "rafaga <nombre> <cantidad> <bytes>" para medir throughput:
                // enviar muchos MENSAJE seguidos y dividir los bytes confirmados por el tiempo total.
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

    /** synchronized: el hilo principal y el Receptor (al mandar ACK/PONG) usan el mismo socket. */
    synchronized void enviar(Mensaje m) {
        salida.println(m.serializar());
        Log.evento(nombre, m.destino(), m.tipo(), m.id(), "ENVIADO");
    }

    private void salir(Thread receptor) throws InterruptedException {
        saliendo = true;
        solicitar(Tipo.SALIR, Mensaje.SERVIDOR, "");
        receptor.join(2000); // espera el OK del servidor y el cierre de la conexion
        Log.info(pendientes.resumen());
    }

    /** Charset del teclado, para que los acentos escritos en la consola lleguen bien. */
    private static Charset charsetConsola() {
        return System.console() != null ? System.console().charset() : Charset.defaultCharset();
    }
}
