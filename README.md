# RegionCast

[![build](https://github.com/CodeW4VE/RegionCast/actions/workflows/build.yml/badge.svg)](https://github.com/CodeW4VE/RegionCast/actions/workflows/build.yml)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-brightgreen.svg)](https://fabricmc.net/)

Keep a mirror server up to date by copying only the regions that changed, picked on a map.

*[Leer en español](README_es.md)*

<!-- screenshot: the map with the region grid, docs/map.png -->

- A map of the world where each tile is one region file: red changed since the last copy, blue
  never copied, gold queued. Zoom in to pick single chunks.
- Click what you want, press Update, and the server restarts with the new terrain, points of
  interest and entities.
- The last 3 updates can be undone.
- Copies a few hundred MB instead of the whole world: on a real server only 59 of 1812 regions
  (407 MB of 8 GB) changed in a day.

Useful for creative copies, test worlds, render worlds, or a place to show a base without
letting people into the real server.

## Installation

- Minecraft 1.21 / 1.21.1 or 26.3, Fabric and [Fabric API](https://modrinth.com/mod/fabric-api).
  Java 21 for 1.21; Java 25 and Fabric Loader 0.19.5+ for 26.3.
- The jar goes on the destination server (the mirror). On your client it is optional and adds the
  map; without it everything works through `/rc`.
- The destination server must be able to read the source world's files (a VPS or a dedicated
  machine; shared hosts like Aternos do not allow it). Mount it read only.
- Recommended: RCON on the source server, and something that restarts the mirror after it stops
  (an [MCDReforged plugin](mcdr/) is included).

Start the server once to create `config/regioncast.properties`, then set `source_world` and the
source's RCON. Full setup in [Setup in detail](#setup-in-detail).

## Usage

```
/rc                  open the map, or print the status if you cannot open one
/rc map              open the map (needs the client mod)
/rc status           what is out of date, what is queued, what backups exist
/rc here             queue the region you are standing in
/rc here chunk       queue only the chunk you are standing in
/rc add <dim> <x> <z>  queue one by index, dimension -1, 0 or 1
/rc scan             re-read the source
/rc list             what is queued
/rc clear            empty the queue
/rc run              copy it all, then restart
/rc backups          list the updates that can be taken back
/rc restore [n]      put one back, then restart
```

On the map: left click queues or unqueues, right click drag pans, scroll zooms. The queue survives
a restart.

<!-- modrinth:end -->

## Details

### How it works

- A map tile is 512 by 512 blocks. A region file is 512 by 512 blocks. So a tile **is** a region,
  no conversion anywhere, and the map is drawn from the chunks your client already has.
- Colours come from a **ledger**, not from comparing the two files. Comparing does not work: the
  copy gets a fresh timestamp, and the mirror rewrites its own region files the moment somebody
  walks around. The ledger records the timestamp the *source* file had when it was copied, and
  compares future source timestamps against that.
- Copying happens **during shutdown**. A mod lives inside the Minecraft process, so it cannot stop
  the server, copy, and start it again: it would be killing itself half way through the job. What
  it can do is copy in `SERVER_STOPPED`, when the world is saved and every region file is closed
  but the JVM is still alive. Something outside then starts the server again (see
  [MCDReforged plugin](#restarting-afterwards)).
- Before copying, the source server is told `save-off` and `save-all flush` over RCON, and
  `save-on` afterwards, with retries, because leaving somebody else's server with saving off is
  worse than anything this mod was trying to do.

### Chunks

Zoom in far enough and the grid switches from regions to chunks, and clicking picks a single
chunk. No mode button: at that zoom a region is wider than the screen, and zoomed out a chunk is a
pixel, so the gesture can only mean one thing at a time.

Loose chunks are copied differently to whole regions. A region is a file copy. A chunk is opened
out of the source's region file and written into ours, using Minecraft's own `RegionFile`, so the
result is byte for byte what the game would have written itself. What that buys you is taking one
build across without the 512 blocks of terrain it shares a file with.

What it costs:

- **Lighting and anything crossing a chunk border** arrives half done until the game recalculates
  it. It does, but it is visible for a moment, mostly in caves.
- **Slower per block**: every chunk is decompressed and recompressed, and the source region file
  is copied to a work folder first (`RegionFile` insists on opening its file for writing, and the
  source is supposed to be mounted read only). Still seconds, not minutes.
- **The source only timestamps whole region files**, so a chunk can only be judged against that.
  A change anywhere in a region marks every chunk you took out of it as out of date, even ones
  nobody touched. Wrong in the direction that tells you to look.

Copied chunks are painted green, red once the source moves on, gold while queued.

### Backups

Every update sets aside the region files it is about to replace. The last **3** updates stay
undoable, and older ones are dropped automatically.

A backup is one *update*, not one region: you click a handful of regions, press Update, and later
decide that whole batch was a mistake. Restoring puts the files back with their original
timestamps and rewinds the ledger with them, so restored regions correctly read as out of date
again rather than claiming to match a source they no longer match.

```
/rc backups          list what can be taken back
/rc restore          put the newest one back
/rc restore 2        put the second newest one back
```

Restoring takes the same route as an update: it is armed, the server restarts, and the files go
back while the world is closed.

Two things worth knowing:

- **Disk.** A batch of 60 regions costs roughly the size of those 60 regions, around 400 MB on a
  busy world. Three of those is over a gigabyte. Check you have the room, or lower `backup_keep`.
- **Restoring does not back up what it overwrites.** What a restore throws away is the copy
  RegionCast pulled from the source, and that is the one thing here you can always fetch again by
  pressing Update.

### Setup in detail

Get the jar from [Modrinth](https://modrinth.com/mod/regioncast) or
[GitHub Releases](https://github.com/CodeW4VE/RegionCast/releases).
When updating, keep `config/regioncast.properties`, the world and your backup directory.
The copy ledger, queued regions and chunks, and existing backups keep their format.

**Make the source world visible** to the destination server, read only. On Linux, a bind mount
next to the world folder:

```bash
mount --bind -o ro /path/to/source/world /path/to/mirror/smp_world
```

Add it to `/etc/fstab` with `nofail` so a missing source never stops the server from booting.
RegionCast never writes to the source, but mounting it read only means a bug in this mod cannot
either.

**Start the server once.** It writes `config/regioncast.properties`:

```properties
# path to the world folder to copy FROM, relative to the world folder or absolute
source_world=../../smp_world

# the source server's RCON, used for save-off before and save-on after
rcon_host=
rcon_port=25575
rcon_password=

# operator level needed to open the map and queue regions
permission_level=3

# refuse to run an update while other players are online here
require_empty=false

# how many updates stay undoable, 0 to never back anything up
backup_keep=3
backup_dir=../regioncast-backups
```

> The config goes in the **mods** config folder of the destination server. If you run MCDReforged
> with `working_directory: server`, that is `server/config/`, not the MCDR `config/` beside it.

### Restarting afterwards

The mod stops the server to copy, and prints one last line on the way out:

```
[RegionCast] RESTART_REQUESTED
```

Anything that watches for that can start the server again. A ready made
[MCDReforged](https://github.com/MCDReforged/MCDReforged) plugin is in [`mcdr/`](mcdr/): drop
`regioncast_restart.py` into your MCDR `plugins/` folder.

> If you use MCDReforged, you need it. When the server process ends on its own MCDR shuts itself
> down with it, and in a container that takes the whole container with it. The plugin cancels that
> the moment it sees the marker, which is while the server is still on its way down: by the time
> `on_server_stop` runs the decision has already been made.

### Common mistakes

- **Do not verify a copy by comparing md5 between the two worlds.** That is exactly the comparison
  the ledger exists to avoid, and it will tell you regions failed when they did not: the source
  keeps being played, and the mirror rewrites its own copy as soon as those chunks load. Verify by
  the log line per region (`copied 3 deleted 0`) and `/rc scan`.
- **Do not copy the jar over a running server.** Java loads classes lazily, so replacing the jar
  underneath a live server means any class it had not needed yet can never be read again. Stop the
  server, replace the jar, start it.
- **Nether maps look like a grey ceiling** if you draw them from the top down. RegionCast starts
  the scan at your own Y in dimensions with a ceiling, and redraws when you move more than 8 blocks
  vertically.

## Building

```bash
./gradlew build
python3 tools/multiversion.py
```

The base build needs JDK 21. Multiversion builds need JDK 21 and 25 and place both jars
in `build/multiversion/`.

The `model` and `sync` packages contain no Minecraft classes on purpose, so the copying, the
ledger, the queue, the backups and the RCON protocol are all unit tested against temporary folders
rather than a 13 GB world. The one exception is `anvil`, which has to talk to Minecraft's own
region file code, and is tested against real region files instead. 125 tests, run in CI on every
push.

## License

MIT. See [LICENSE](LICENSE).

Part of [W4VE](https://github.com/CodeW4VE), Ware 4 Vanilla Experience.
