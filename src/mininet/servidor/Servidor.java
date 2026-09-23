package mininet.servidor;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import mininet.comun.Log;

/**
 * Servidor MiniNet. Uso:
 *
 *   java -cp out mininet.servidor.Servidor <puerto> [probabilidadPerdida] [retardoMs]
 *
 * Ej.: java -cp out mininet.servidor.Servidor 5000            (red "normal")
 *      java -cp out mininet.servidor.Servidor 5000 0.2 100    (20 % de perdida, +100 ms)
 */
public class Servidor {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Uso: java -cp out mininet.servidor.Servidor <puerto> [perdida 0-1] [retardoMs]");
            return;
        }
        int puerto = Integer.parseInt(args[0]);
        double perdida = args.length > 1 ? Double.parseDouble(args[1]) : 0.0;
        int retardo = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        Log.iniciar("servidor.log");
        Registro registro = new Registro();
        SimuladorRed red = new SimuladorRed(perdida, retardo);
        ExecutorService hilos = Executors.newCachedThreadPool();

        try (ServerSocket servidor = new ServerSocket(puerto)) { // escucha en todas las interfaces
            Log.info("Servidor MiniNet en el puerto " + puerto + " (" + red + ")");
            mostrarDirecciones(puerto);
            while (true) {
                Socket socket = servidor.accept();                       // espera un cliente
                hilos.execute(new ManejadorCliente(socket, registro, red)); // un hilo por cliente
            }
        }
    }

    /** Muestra las IPv4 de este equipo: a una de ellas deben conectarse los clientes. */
    private static void mostrarDirecciones(int puerto) throws SocketException {
        for (NetworkInterface interfaz : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!interfaz.isUp() || interfaz.isLoopback()) {
                continue;
            }
            for (InetAddress ip : Collections.list(interfaz.getInetAddresses())) {
                if (ip instanceof Inet4Address) {
                    Log.info("  " + ip.getHostAddress() + ":" + puerto + "  (" + interfaz.getDisplayName() + ")");
                }
            }
        }
    }
}
