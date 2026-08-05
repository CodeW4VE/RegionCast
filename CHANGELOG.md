# Changelog

## 1.0.1

Two things a live server found the hard way.

- **A source that cannot be read no longer looks like a source that lost everything.** A world
  folder holding no region file at all is now treated as unusable: no region is painted as gone,
  no update can be armed, and an update already armed stops on the way out with the queue intact.
  This is what a bind mount left pointing at a folder the backup plugin moved looks like from
  inside a container, and before this the empty scan read as "the source lost every region", which
  meant deleting them here.
- **The report says what was deleted.** Regions and chunks removed because the source no longer
  has them are counted apart from the ones that were copied, instead of all of it reading
  `4 region(s) updated`.
- The network channels carry their layout version in their name. A client mod older than the
  server now quietly gets no map and a message telling it to update, instead of being kicked with
  a decoder error every time the server sends state.
- 125 tests.

## 1.0.0

First public release.

- Pull region files from another server's world into this one, picking them off a map where one
  tile is one region file.
- Colours from a sync ledger: changed since the last copy, never copied, gone from the source,
  queued.
- Terrain, points of interest and entities always travel together.
- `save-off` and `save-all flush` on the source over RCON before copying, `save-on` afterwards
  with retries.
- Copying happens in `SERVER_STOPPED`, with a restart marker for a supervisor to pick up. An
  MCDReforged plugin to do that is included in `mcdr/`.
- The queue is written to disk on every change, so it survives a restart in order.
- **Backups**: every update sets aside the region files it replaces, the last 3 stay undoable via
  `/rc backups`, `/rc restore` or the Backups button on the map. Restoring rewinds the ledger too,
  so restored regions correctly read as out of date again.
- **Single chunks**: zoom in and the grid switches from regions to chunks, so a click takes one
  chunk instead of the whole file. Copied through Minecraft's own `RegionFile` at both ends, with
  its own ledger and its own colours on the map. `/rc here chunk` does the same from chat.
- Plain `/rc` opens the map now, and falls back to the status text for the console and for players
  without the client mod. `/rc status` still prints it on demand.
- Dimensions with a ceiling are mapped from the player's own Y, so the Nether is not a grey slab.
- 108 tests over the copier, scanner, ledger, queue, backups, chunk copying and RCON protocol.
