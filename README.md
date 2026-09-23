# MiniNet

Esqueleto del proyecto de Introducción a Redes (UTA, Software 3B). Un servidor y varios clientes
en Java que se comunican por **sockets TCP** con un **protocolo de texto propio**. Interfaz de consola.

## Estructura

```
src/mininet/
  protocolo/  Tipo.java              tipos de mensaje
              Mensaje.java           formato de la línea y parseo
  comun/      Log.java               registro de eventos (consola + archivo .log)
  servidor/   Servidor.java          acepta conexiones, un hilo por cliente
              ManejadorCliente.java  aplica el protocolo para un cliente
              Registro.java          participantes activos y grupos
              SimuladorRed.java      pérdida y retardo artificiales
  cliente/    Cliente.java           comandos de consola y envío
              Receptor.java          hilo que escucha al servidor y responde ACK/PONG
              Pendientes.java        timeouts, latencia y pérdida
```

## Compilar (JDK 17 o superior)

```
javac -encoding UTF-8 -d out -sourcepath src src/mininet/servidor/Servidor.java src/mininet/cliente/Cliente.java
```

## Ejecutar

```
java -cp out mininet.servidor.Servidor 5000
java -cp out mininet.cliente.Cliente <ip-del-servidor> 5000 cliente01
```

Al arrancar, el servidor imprime las IP del equipo: los clientes deben usar la de la red que
comparten (Wi-Fi o cable), no `127.0.0.1`, cuando están en otra computadora.

Para experimentos, el servidor acepta pérdida (0 a 1) y retardo en ms por cada reenvío:

```
java -cp out mininet.servidor.Servidor 5000 0.2 100
```

**Entre dos computadoras:** la primera vez que corre el servidor, Windows pregunta si permite
a Java usar la red; hay que aceptar para redes privadas. Si la red está marcada como pública o
el aviso no aparece, en el equipo del servidor (PowerShell como administrador):

```
New-NetFirewallRule -DisplayName "MiniNet" -Direction Inbound -Protocol TCP -LocalPort 5000 -Action Allow
```

## Protocolo

Cada mensaje es una línea de texto UTF-8: `TIPO|id|origen|destino|contenido`

| Tipo | Recorrido | destino | contenido | Respuesta |
|---|---|---|---|---|
| REGISTRO | C → S | `SERVIDOR` | — | `OK`, o `ERROR` si el nombre está en uso o no es válido |
| LISTAR | C → S | `SERVIDOR` | — | `OK` con `c1,c2,c3` |
| ESTADO | C → S | nombre | — | `OK` con `ACTIVO` o `NO_DISPONIBLE` |
| UNIR | C → S | grupo | — | `OK` |
| MENSAJE | A → S → B | B | texto | `ACK` de B, o `ERROR` si B no está |
| DIFUSION | A → S → todos | `*` | texto | `OK` con el nº de réplicas + un `ACK` por receptor |
| GRUPO | A → S → miembros | grupo | texto | `OK` con el nº de réplicas + un `ACK` por receptor |
| PING | A → S → B | B | — | `PONG` de B |
| RAFAGA | A → S → B | B | bloque de datos | `ACK` de B (ni el servidor ni B lo registran en el log) |
| SALIR | C → S | `SERVIDOR` | — | `OK`; luego el servidor cierra la conexión |
| AVISO | S → C | cliente | texto | — (alguien entró, salió o se cayó) |
| LATIDO | C → S, cada 5 s | `SERVIDOR` | — | `LATIDO` del servidor (no se registra en el log) |

Las respuestas repiten el `id` de la petición y el contenido de un `ACK` es el tipo que confirma.
Ejemplo de unicast:

```
A --MENSAJE|7|A|B|Hola--> S --MENSAJE|7|A|B|Hola--> B
A <--ACK|7|B|A|MENSAJE--- S <--ACK|7|B|A|MENSAJE--- B
```

- Si una confirmación no llega en 3 s (`Pendientes.TIMEOUT_MS`), el cliente registra `TIMEOUT`
  y la cuenta como pérdida. Si llega después, se registra como `TARDIO`.
- Si un cliente cierra sin `SALIR` o se corta su conexión, el servidor lo quita del registro,
  registra `CAIDA` con el motivo y envía un `AVISO` a los demás.
- **Latido:** un equipo que se desconecta de la red (cable o Wi-Fi) no cierra la conexión TCP, así
  que el otro extremo no se entera solo. Por eso el cliente envía `LATIDO` cada 5 s y el servidor
  lo devuelve. Si el servidor pasa 15 s sin recibir nada de un cliente, lo da por caído
  (`CAIDA (sin latido por 15 s)`). Si el cliente pasa 15 s sin recibir nada del servidor, se
  cierra. Los latidos no pasan por el simulador de red, así que las pruebas de pérdida no provocan
  caídas falsas. Los tiempos están en `Mensaje.LATIDO_INTERVALO_MS` y `LATIDO_TIMEOUT_MS`.
- El servidor reemplaza el campo `origen` por el nombre registrado: nadie puede hacerse pasar por otro.
- Difusión y grupo se resuelven en el servidor, que copia el mensaje por cada conexión TCP:
  es difusión/multicast **de aplicación**, no broadcast ni multicast IP.

## Log

Cada proceso escribe en consola y en `servidor.log` o `<nombre>.log`. Ejemplo real de un cliente:

```
22:30:24.213 c1 -> c2 MENSAJE id=3 ENVIADO
22:30:24.222 c2 -> c1 ACK id=3 9.63 ms
22:30:24.745 c1 -> * DIFUSION id=4 ENVIADO
22:30:24.747 SERVIDOR -> c1 OK id=4 1.50 ms 2
22:30:24.747 c2 -> c1 ACK id=4 1.97 ms
22:30:24.760 c3 -> c1 ACK id=4 14.79 ms
22:30:27.198 SERVIDOR -> c1 AVISO id=0 c3 se desconecto inesperadamente
```

El comando `resumen` muestra confirmaciones recibidas y esperadas, pérdida, latencia
(promedio, mínima y máxima) y el throughput de la última ráfaga.

## Medir throughput

`rafaga <nombre> <cantidad> <bytes>` envía `<cantidad>` bloques de `<bytes>` de datos seguidos,
sin esperar los ACK, y calcula:

```
throughput = bytes de datos confirmados / tiempo entre el primer envío y el último ACK
```

La ráfaga termina cuando todos los bloques tienen respuesta, o después de 3 s sin que llegue
ninguna; los bloques que faltan cuentan como perdidos. Ejemplo real en localhost:

```
23:12:41.527 c1 -> c2 RAFAGA id=1002 INICIO 10000 bloques de 1000 B
23:12:42.368 c1 -> c2 RAFAGA id=1002 FIN 10000/10000 bloques confirmados en 0.841 s: throughput 95.18 Mbit/s (11897 bloques/s, 10000000 B de datos); 10201002 B escritos en el socket, cabecera MiniNet +2.0 %
```

- Los bloques y sus ACK no van al log (serían miles de líneas y escribir en la consola frenaría
  la medición). Solo se registran el inicio y el resultado.
- "Bytes escritos en el socket" incluye la cabecera MiniNet (`RAFAGA|id|origen|destino|`) y el fin
  de línea (`\r\n` en Windows). TCP, IP y Ethernet agregan sus propias cabeceras encima: eso se
  ve en Wireshark y sirve para el análisis de encapsulamiento.
- La latencia y la pérdida del `resumen` no incluyen las ráfagas: los bloques en cola tienen
  demoras que no representan la latencia de la red.

## Pendiente para el grupo

- [x] **Latido** para detectar un equipo desconectado de la red.
- [ ] **Probar el latido entre dos computadoras:** desconectar el Wi-Fi de un cliente y ver
      `CAIDA (sin latido por 15 s)` en el servidor.
- [x] **Comando `rafaga`** para medir throughput.
- [ ] **Disponibilidad** en el resumen. Ver el TODO en `Pendientes.resumen()`.
- [ ] **Experimentos:** correr con distintas pérdidas y retardos y tabular los resultados.
- [ ] **Especificación del protocolo** en el informe; la tabla de arriba es el punto de partida.
- [ ] **Wireshark:** capturar entre dos equipos con el filtro `tcp.port == 5000` y buscar el `id`
      del log en el contenido (clic derecho → Seguir → Flujo TCP). Capturar en una sola
      computadora con Windows requiere Npcap con soporte de loopback. Los paquetes pequeños que
      aparecen cada 5 s aunque nadie escriba son los `LATIDO`.

### Datos útiles para la discusión

- La pérdida simulada se aplica en **cada** reenvío del servidor: al mensaje y a su ACK. Con
  `p = 0.3`, la pérdida extremo a extremo esperada es `1 - 0.7² ≈ 51 %`.
- El retardo también se aplica dos veces (ida y ACK). En Windows, `Thread.sleep(50)` dura unos
  61 ms por la resolución del temporizador; en las pruebas, 50 ms configurados dieron unos 122 ms
  de latencia ida y vuelta.
- El retardo se aplica mensaje por mensaje, así que actúa como un enlace lento: con `R` ms, una
  ráfaga no puede pasar de `1000/R` bloques por segundo. Con 5 ms el máximo teórico es 200
  bloques/s; se midieron 119, porque `sleep(5)` dura unos 8 ms en Windows.
- Con bloques chicos el throughput baja mucho (100 B: 5 Mbit/s; 1000 B: 95 a 182 Mbit/s) porque
  el costo por mensaje pesa más que los datos, y la cabecera MiniNet pasa de +2 % a +19 %.
- La primera ráfaga de una ejecución suele salir más lenta porque la JVM todavía está optimizando
  el código: conviene repetir cada medición varias veces y promediar.
