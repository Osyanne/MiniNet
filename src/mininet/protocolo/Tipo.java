package mininet.protocolo;

/**
 * Tipos de mensaje del protocolo MiniNet.
 *
 * Cliente -> Servidor:   REGISTRO, LISTAR, ESTADO, UNIR, SALIR
 * Cliente -> Cliente(s), siempre intermediado por el servidor:
 *                        MENSAJE (unicast), DIFUSION (a todos), GRUPO (a un grupo), PING,
 *                        RAFAGA (bloques para medir throughput) y sus confirmaciones ACK / PONG
 * Servidor -> Cliente:   OK, ERROR, AVISO
 * Cliente <-> Servidor:  LATIDO, cada 5 s, para detectar que el otro extremo desapareció
 */
public enum Tipo {
    REGISTRO, LISTAR, ESTADO, UNIR, SALIR,
    MENSAJE, DIFUSION, GRUPO, PING, RAFAGA,
    ACK, PONG,
    OK, ERROR, AVISO,
    LATIDO
}
