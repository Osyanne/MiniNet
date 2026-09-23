package mininet.protocolo;

/**
 * Tipos de mensaje del protocolo MiniNet.
 *
 * Cliente -> Servidor:   REGISTRO, LISTAR, ESTADO, UNIR, SALIR
 * Cliente -> Cliente(s), siempre intermediado por el servidor:
 *                        MENSAJE (unicast), DIFUSION (a todos), GRUPO (a un grupo), PING
 *                        y sus confirmaciones ACK / PONG
 * Servidor -> Cliente:   OK, ERROR, AVISO
 */
public enum Tipo {
    REGISTRO, LISTAR, ESTADO, UNIR, SALIR,
    MENSAJE, DIFUSION, GRUPO, PING,
    ACK, PONG,
    OK, ERROR, AVISO
}
