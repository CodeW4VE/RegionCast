# RegionCast

[![build](https://github.com/CodeW4VE/RegionCast/actions/workflows/build.yml/badge.svg)](https://github.com/CodeW4VE/RegionCast/actions/workflows/build.yml)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%2B-brightgreen.svg)](https://fabricmc.net/)

Pull region files from another server's world into this one, picked off a map.

RegionCast shows you which regions changed since the last time you copied them, you click the ones
you want, and it brings terrain, points of interest and entities across during a controlled
restart. The last few updates stay undoable.

*[Leer en español](README_es.md)*

<!-- screenshot: the map with the region grid, docs/map.png -->

## What it is for

You run a second server that mirrors a build server: a creative copy, a testing world, a render
world, a place to show people a base without letting them into the real one. Keeping it up to date
means copying the world across, and the world is 8 GB, and 97% of it did not change.

On a real server the numbers look like this: 1812 overworld regions, 8.0 GB, and in a whole day of
play only 59 of them change. That is 407 MB. RegionCast copies those 59.

The idea is the same one behind TISUnion's `region_file_updater` plugin and the old `!!region`
commands, with one difference: you pick the regions on a map of the actual world instead of typing
coordinates and hoping you got the right file.

## How it works

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

## Chunks

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

## Backups

Every update sets aside the region files it is about to replace. The last **3** updates stay
undoable, and older ones are dropped automatically.

A backup is one *update*, not one region: you click a handful of regions, press Update, and later
decide that whole batch was a mistake. Restoring puts the files back with their original
timestamps and rewinds the ledger with them, so restored regions correctly read as out of date
again rather than claiming to match a source they no longer match.

<!-- screenshot: the backups screen, docs/backups.png -->

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

## Requirements

- Minecraft 1.21 or newer, Fabric, [Fabric API](https://modrinth.com/mod/fabric-api).
- Java 21 or newer.
- **Filesystem access to both worlds from the destination server.** This is the real requirement:
  the mod copies files, so the source world has to be visible to the destination process. A VPS or
  a dedicated machine, not a shared host like Aternos or the cheap end of Shockbyte where you only
  get your own folder.
- Optional but recommended: RCON on the source server, and something that restarts the destination
  server after it stops.

The client mod is optional. Without it everything still works through `/rc`; you just do not get
the map.

## Setup

**1. Install the jar** on the destination server (the mirror). Install it on your client too if
you want the map.

**2. Make the source world visible** to the destination server, read only. On Linux, a bind mount
next to the world folder:

```bash
mount --bind -o ro /path/to/source/world /path/to/mirror/smp_world
```

Add it to `/etc/fstab` with `nofail` so a missing source never stops the server from booting.
RegionCast never writes to the source, but mounting it read only means a bug in this mod cannot
either.

**3. Start the server once.** It writes `config/regioncast.properties`:

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

## Using it

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

On the map: **left click** to queue or unqueue whatever you are pointing at, **right click drag**
to pan, **scroll** to zoom. Zoomed out that is a region; zoomed in it is a chunk. Colours are red
for changed since the last copy, blue for never copied, gold for queued.

The queue survives a restart, so a server that went down for some other reason does not make you
click forty regions again.

## Things that will bite you

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
```

Needs JDK 21. The jar lands in `build/libs/`.

The `model` and `sync` packages contain no Minecraft classes on purpose, so the copying, the
ledger, the queue, the backups and the RCON protocol are all unit tested against temporary folders
rather than a 13 GB world. The one exception is `anvil`, which has to talk to Minecraft's own
region file code, and is tested against real region files instead. 125 tests, run in CI on every
push.

## License

MIT. See [LICENSE](LICENSE).

Part of [W4VE](https://github.com/CodeW4VE), Ware 4 Vanilla Experience.
