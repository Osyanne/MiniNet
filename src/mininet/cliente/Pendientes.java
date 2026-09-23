package mininet.cliente;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import mininet.comun.Log;
import mininet.protocolo.Mensaje;
import mininet.protocolo.Tipo;

/**
 * Mensajes enviados que esperan respuesta, y las métricas que salen de ellos:
 * - latencia: tiempo desde el envío hasta que llega el ACK/PONG del otro cliente (ida y vuelta);
 * - pérdida: ACK/PONG que no llegaron antes de TIMEOUT_MS;
 * - throughput: lo mide la Rafaga en curso, que se lleva aparte para no mezclar sus miles de
 *   bloques (y sus demoras por cola) con la latencia y la pérdida de los mensajes normales;
 * - disponibilidad: por participante, % de PING que obtuvieron PONG. Un PING sin respuesta
 *   (TIMEOUT) o rechazado porque el destino no está (ERROR) cuenta como "no disponible".
 */
class Pendientes {

    static final long TIMEOUT_MS = 3000;

    /** Un mensaje enviado y lo que va llegando de vuelta. */
    private static final class Pendiente {
        final Mensaje mensaje;
        final long enviadoNanos = System.nanoTime();
        int acksEsperados; // 1 en MENSAJE/PING; en DIFUSION/GRUPO lo dice el OK (-1 = aun no se sabe)
        int acksRecibidos;

        Pendiente(Mensaje mensaje) {
            this.mensaje = mensaje;
            this.acksEsperados = (mensaje.tipo() == Tipo.MENSAJE || mensaje.tipo() == Tipo.PING) ? 1 : -1;
        }

        boolean completo() {
            return acksEsperados >= 0 && acksRecibidos >= acksEsperados;
        }
    }

    /** PING enviados a un participante y cuántos obtuvieron PONG. */
    private static final class Sondeos {
        int enviados;
        int respondidos;
    }

    private final String nombre;
    private final Map<Integer, Pendiente> pendientes = new HashMap<>();
    private final Map<String, Sondeos> sondeos = new TreeMap<>(); // por participante, en orden

    // Métricas de la sesión. Esperados/recibidos solo cuentan mensajes ya cerrados.
    private int totalEsperados;
    private int totalRecibidos;
    private int latencias;
    private double sumaMs;
    private double minMs = Double.MAX_VALUE;
    private double maxMs;

    private Rafaga rafaga; // la ráfaga en curso, o la última (para ignorar sus ACK tardíos)

    Pendientes(String nombre) {
        this.nombre = nombre;
        ScheduledExecutorService revisor = Executors.newSingleThreadScheduledExecutor(tarea -> {
            Thread hilo = new Thread(tarea, "revisor-timeouts");
            hilo.setDaemon(true);
            return hilo;
        });
        revisor.scheduleAtFixedRate(this::revisarTimeouts, 1, 1, TimeUnit.SECONDS);
    }

    synchronized void registrar(Mensaje m) {
        pendientes.put(m.id(), new Pendiente(m));
    }

    synchronized boolean rafagaEnCurso() {
        return rafaga != null && !rafaga.finalizada();
    }

    synchronized void iniciarRafaga(Rafaga nueva) {
        rafaga = nueva;
    }

    /** Llegó una respuesta (ACK, PONG, OK o ERROR) con el id de un mensaje que enviamos. */
    synchronized void respuesta(Mensaje r) {
        if (rafaga != null && rafaga.contiene(r.id())) {
            if (rafaga.respuesta(r)) {
                finalizarRafaga();
            }
            return;
        }
        Pendiente p = pendientes.get(r.id());
        if (p == null) {
            Log.evento(r.origen(), nombre, r.tipo(), r.id(), "TARDIO (llego despues del timeout)");
            return;
        }
        double ms = (System.nanoTime() - p.enviadoNanos) / 1_000_000.0;
        switch (r.tipo()) {
            case ACK, PONG -> {
                p.acksRecibidos++;
                latencias++;
                sumaMs += ms;
                minMs = Math.min(minMs, ms);
                maxMs = Math.max(maxMs, ms);
                Log.evento(r.origen(), nombre, r.tipo(), r.id(), formatoMs(ms));
            }
            case OK -> {
                Log.evento(r.origen(), nombre, r.tipo(), r.id(), formatoMs(ms) + " " + r.contenido());
                if (p.mensaje.tipo() == Tipo.DIFUSION || p.mensaje.tipo() == Tipo.GRUPO) {
                    p.acksEsperados = Integer.parseInt(r.contenido()); // réplicas hechas por el servidor
                } else {
                    pendientes.remove(r.id()); // LISTAR, ESTADO, UNIR, SALIR: no esperan nada más
                    return;
                }
            }
            case ERROR -> {
                Log.evento(r.origen(), nombre, r.tipo(), r.id(), r.contenido());
                if (p.mensaje.tipo() == Tipo.PING) {
                    contarSondeo(p.mensaje.destino(), false); // el destino no está conectado
                }
                pendientes.remove(r.id());
                return;
            }
            default -> {
                return;
            }
        }
        if (p.completo()) {
            cerrar(p);
            pendientes.remove(r.id());
        }
    }

    synchronized String resumen() {
        int perdidos = totalEsperados - totalRecibidos;
        double perdida = totalEsperados == 0 ? 0 : 100.0 * perdidos / totalEsperados;
        String latencia = latencias == 0 ? "sin datos" : String.format(Locale.ROOT,
                "prom %.2f / min %.2f / max %.2f ms", sumaMs / latencias, minMs, maxMs);
        String throughput = rafaga != null && rafaga.finalizada() ? rafaga.resumenCorto() : "ninguna";
        String disponibilidad = sondeos.isEmpty() ? "sin datos (use ping)" : sondeos.entrySet().stream()
                .map(e -> String.format(Locale.ROOT, "%s %d/%d (%.1f %%)", e.getKey(),
                        e.getValue().respondidos, e.getValue().enviados,
                        100.0 * e.getValue().respondidos / e.getValue().enviados))
                .collect(Collectors.joining(", "));
        return String.format(Locale.ROOT,
                "Resumen: %d/%d confirmaciones recibidas, perdida %.1f %%, latencia %s, %d en espera"
                        + " | ultima rafaga %s | disponibilidad %s",
                totalRecibidos, totalEsperados, perdida, latencia, pendientes.size(), throughput, disponibilidad);
    }

    private synchronized void revisarTimeouts() {
        if (rafagaEnCurso() && rafaga.sinRespuestaHace(TIMEOUT_MS)) {
            finalizarRafaga();
        }
        long ahora = System.nanoTime();
        Iterator<Pendiente> it = pendientes.values().iterator();
        while (it.hasNext()) {
            Pendiente p = it.next();
            if ((ahora - p.enviadoNanos) / 1_000_000 < TIMEOUT_MS) {
                continue;
            }
            String detalle = p.acksEsperados < 0 ? "sin respuesta del servidor"
                    : p.acksRecibidos + "/" + p.acksEsperados + " confirmaciones";
            Log.evento(nombre, p.mensaje.destino(), p.mensaje.tipo(), p.mensaje.id(), "TIMEOUT " + detalle);
            cerrar(p);
            it.remove();
        }
    }

    private void finalizarRafaga() {
        Log.evento(nombre, rafaga.destino(), Tipo.RAFAGA, rafaga.primerId(), "FIN " + rafaga.finalizar());
    }

    private void cerrar(Pendiente p) {
        totalEsperados += Math.max(p.acksEsperados, 0);
        totalRecibidos += p.acksRecibidos;
        if (p.mensaje.tipo() == Tipo.PING) {
            contarSondeo(p.mensaje.destino(), p.acksRecibidos > 0);
        }
    }

    private void contarSondeo(String destino, boolean respondio) {
        Sondeos s = sondeos.computeIfAbsent(destino, d -> new Sondeos());
        s.enviados++;
        if (respondio) {
            s.respondidos++;
        }
    }

    private static String formatoMs(double ms) {
        return String.format(Locale.ROOT, "%.2f ms", ms);
    }
}
