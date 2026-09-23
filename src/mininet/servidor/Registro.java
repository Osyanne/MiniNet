package mininet.servidor;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado compartido del servidor: quién está conectado y qué grupos existen.
 * Todos los hilos ManejadorCliente lo usan a la vez, por eso las colecciones son concurrentes.
 */
class Registro {

    private final Map<String, ManejadorCliente> participantes = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> grupos = new ConcurrentHashMap<>();

    /** @return false si el nombre ya está en uso. */
    boolean registrar(String nombre, ManejadorCliente manejador) {
        return participantes.putIfAbsent(nombre, manejador) == null;
    }

    void eliminar(String nombre, ManejadorCliente manejador) {
        participantes.remove(nombre, manejador);
        for (Set<String> miembros : grupos.values()) {
            miembros.remove(nombre);
        }
    }

    /** @return el manejador del participante, o null si no está conectado. */
    ManejadorCliente buscar(String nombre) {
        return participantes.get(nombre);
    }

    Collection<ManejadorCliente> todos() {
        return participantes.values();
    }

    /** Nombres de los participantes activos, en orden alfabético. */
    Set<String> nombres() {
        return new TreeSet<>(participantes.keySet());
    }

    void unir(String grupo, String nombre) {
        grupos.computeIfAbsent(grupo, g -> ConcurrentHashMap.newKeySet()).add(nombre);
    }

    Set<String> miembros(String grupo) {
        return grupos.getOrDefault(grupo, Set.of());
    }
}
