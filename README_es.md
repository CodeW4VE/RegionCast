# RegionCast

[![build](https://github.com/CodeW4VE/RegionCast/actions/workflows/build.yml/badge.svg)](https://github.com/CodeW4VE/RegionCast/actions/workflows/build.yml)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-brightgreen.svg)](https://fabricmc.net/)

Trae region files del mundo de otro servidor a este, eligiéndolos en un mapa.

RegionCast te muestra qué regiones cambiaron desde la última copia, vos hacés click en las que
querés, y las trae (terreno, points of interest y entidades juntos) durante un reinicio
controlado. Las últimas actualizaciones se pueden deshacer.

*[Read in English](README.md)*

<!-- screenshot: el mapa con la cuadrícula de regiones, docs/map.png -->

## Para qué sirve

Tenés un segundo servidor que espeja al principal: una copia en creativo, un mundo de pruebas, uno
para renderizar, un sitio donde enseñar una base sin dejar entrar a nadie a la de verdad.
Mantenerlo al día significa copiar el mundo, el mundo pesa 8 GB, y el 97% no cambió.

En un servidor real los números son estos: 1812 regiones en el overworld, 8,0 GB, y en un día
entero de juego solo cambian 59. Eso son 407 MB. RegionCast copia esas 59.

La idea es la misma del plugin `region_file_updater` de TISUnion y de los viejos comandos
`!!region`, con una diferencia: elegís las regiones sobre un mapa del mundo real en vez de teclear
coordenadas y confiar en que acertaste el archivo.

## Cómo funciona

- Un tile del mapa mide 512 por 512 bloques. Un region file mide 512 por 512 bloques. Así que un
  tile **es** una región, sin conversión en ninguna parte, y el mapa se dibuja con los chunks que
  tu cliente ya tiene.
- Los colores salen de un **ledger**, no de comparar los dos archivos. Comparar no funciona: la
  copia se lleva una fecha nueva, y el mirror reescribe sus propios region files en cuanto alguien
  camina por ahí. El ledger anota la fecha que tenía el archivo del *origen* al copiarlo, y compara
  las fechas futuras del origen contra esa.
- La copia ocurre **durante el apagado**. Un mod vive dentro del proceso de Minecraft, así que no
  puede parar el servidor, copiar y arrancarlo de nuevo: se estaría matando a mitad del trabajo. Lo
  que sí puede es copiar en `SERVER_STOPPED`, cuando el mundo ya está guardado y todos los region
  files cerrados, pero la JVM sigue viva. Algo de fuera vuelve a arrancar el servidor (ver
  [reiniciar después](#reiniciar-despues)).
- Antes de copiar se le manda `save-off` y `save-all flush` al servidor de origen por RCON, y
  `save-on` al terminar, con reintentos, porque dejar el servidor de otro sin guardar es peor que
  cualquier cosa que este mod intentara hacer.

## Backups

Cada actualización aparta los region files que va a reemplazar. Las últimas **3** se pueden
deshacer, y las más viejas se borran solas.

Un backup es una *actualización*, no una región: hacés click en unas cuantas regiones, le das a
Update, y más tarde decidís que esa tanda entera fue un error. Restaurar devuelve los archivos con
sus fechas originales y rebobina el ledger con ellos, así las regiones restauradas vuelven a
figurar como desactualizadas en vez de decir que coinciden con un origen con el que ya no
coinciden.

<!-- screenshot: la pantalla de backups, docs/backups.png -->

```
/rc backups          lista lo que se puede deshacer
/rc restore          devuelve la más reciente
/rc restore 2        devuelve la segunda más reciente
```

Restaurar toma el mismo camino que una actualización: se arma, el servidor reinicia, y los
archivos vuelven a su sitio con el mundo cerrado.

Dos cosas que conviene saber:

- **El disco.** Una tanda de 60 regiones cuesta más o menos lo que pesan esas 60 regiones, unos
  400 MB en un mundo con movimiento. Tres de esas pasan del giga. Mirá que tengas sitio, o bajá
  `backup_keep`.
- **Restaurar no hace backup de lo que sobreescribe.** Lo que tira una restauración es la copia que
  RegionCast trajo del origen, y es justo lo único de todo esto que siempre podés volver a traer
  dándole a Update.

## Requisitos

- Minecraft 1.21 o superior, Fabric, [Fabric API](https://modrinth.com/mod/fabric-api).
- Java 21 o superior.
- **Acceso al sistema de archivos de los dos mundos desde el servidor destino.** Este es el
  requisito de verdad: el mod copia archivos, así que el mundo de origen tiene que ser visible para
  el proceso del destino. Un VPS o una máquina dedicada, no un host compartido tipo Aternos ni la
  gama barata de Shockbyte, donde solo tenés tu carpeta.
- Opcional pero recomendado: RCON en el servidor de origen, y algo que reinicie el servidor destino
  cuando se pare.

El mod cliente es opcional. Sin él todo funciona igual por `/rc`; lo único que te falta es el mapa.

## Instalación

**1. Poné el jar** en el servidor destino (el mirror). Ponelo también en tu cliente si querés el
mapa.

**2. Hacé visible el mundo de origen** para el servidor destino, en solo lectura. En Linux, un bind
mount al lado de la carpeta del mundo:

```bash
mount --bind -o ro /ruta/al/mundo/origen /ruta/al/mirror/smp_world
```

Metelo en `/etc/fstab` con `nofail` para que un origen ausente nunca impida arrancar el servidor.
RegionCast nunca escribe en el origen, pero montarlo en solo lectura significa que un bug de este
mod tampoco podría.

**3. Arrancá el servidor una vez.** Escribe `config/regioncast.properties`:

```properties
# ruta a la carpeta del mundo del que copiar, relativa a la del mundo o absoluta
source_world=../../smp_world

# el RCON del servidor de origen, para el save-off antes y el save-on después
rcon_host=
rcon_port=25575
rcon_password=

# nivel de operador necesario para abrir el mapa y encolar regiones
permission_level=3

# no dejar actualizar si hay otros jugadores conectados aquí
require_empty=false

# cuántas actualizaciones se pueden deshacer, 0 para no hacer backup nunca
backup_keep=3
backup_dir=../regioncast-backups
```

> El config va en la carpeta de config de **mods** del servidor destino. Si usás MCDReforged con
> `working_directory: server`, eso es `server/config/`, no el `config/` de MCDR que está al lado.

### Reiniciar después

El mod para el servidor para copiar, y escribe una última línea al salir:

```
[RegionCast] RESTART_REQUESTED
```

Cualquier cosa que vigile esa línea puede volver a arrancar el servidor. En [`mcdr/`](mcdr/) hay un
plugin de [MCDReforged](https://github.com/MCDReforged/MCDReforged) listo: copiá
`regioncast_restart.py` a tu carpeta `plugins/` de MCDR.

> Si usás MCDReforged, lo necesitás. Cuando el proceso del servidor termina por su cuenta, MCDR se
> apaga con él, y dentro de un container eso se lleva el container entero por delante. El plugin
> cancela eso en cuanto ve el marcador, que es mientras el servidor todavía se está apagando: para
> cuando corre `on_server_stop` la decisión ya está tomada.

## Uso

```
/rc                    qué está desactualizado, qué hay en cola, qué backups hay
/rc map                abre el mapa (necesita el mod cliente)
/rc here               encola la región en la que estás parado
/rc add <dim> <x> <z>  encola una por índice, dimensión -1, 0 o 1
/rc scan               relee el origen
/rc list               qué hay en cola
/rc clear              vacía la cola
/rc run                copia todo, y reinicia
/rc backups            lista las actualizaciones que se pueden deshacer
/rc restore [n]        devuelve una, y reinicia
```

En el mapa: **click izquierdo** para encolar o desencolar una región, **click derecho arrastrando**
para moverte, **rueda** para el zoom. Los colores son rojo si cambió desde la última copia, azul si
nunca se copió, dorado si está en cola.

La cola sobrevive a un reinicio, así que si el servidor se cae por otro motivo no te toca volver a
hacer click en cuarenta regiones.

## Cosas que te van a morder

- **No verifiques una copia comparando md5 entre los dos mundos.** Es exactamente la comparación
  que el ledger existe para evitar, y te va a decir que fallaron regiones que no fallaron: en el
  origen se sigue jugando, y el mirror reescribe su propia copia en cuanto se cargan esos chunks.
  Verificá por la línea de log de cada región (`copied 3 deleted 0`) y con `/rc scan`.
- **No copies el jar encima del servidor corriendo.** Java carga las clases de forma perezosa, así
  que reemplazar el jar por debajo de un servidor vivo hace que cualquier clase que todavía no
  hiciera falta ya no se pueda leer nunca. Pará el servidor, reemplazá el jar, arrancá.
- **Los mapas del Nether se ven como un techo gris** si los dibujás de arriba abajo. RegionCast
  arranca el escaneo a tu propia Y en las dimensiones con techo, y redibuja cuando te movés más de
  8 bloques en vertical.

## Compilar

```bash
./gradlew build
```

Necesita JDK 21. El jar sale en `build/libs/`.

Los paquetes `model` y `sync` no tienen ni una clase de Minecraft, a propósito, así que la copia,
el ledger, la cola, los backups y el protocolo RCON se testean todos contra carpetas temporales en
vez de contra un mundo de 13 GB. 87 tests, que corren en CI en cada push.

## Licencia

MIT. Ver [LICENSE](LICENSE).

Parte de [W4VE](https://github.com/CodeW4VE), Ware 4 Vanilla Experience.
