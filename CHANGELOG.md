# Changelog

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
