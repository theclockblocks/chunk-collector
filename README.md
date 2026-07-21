# Chunk Collector

A RuneLite plugin that turns an OSRS account into a **zone-locked collection-log
challenge**: the world is locked outside your starting zone, and the only way to
expand is to *complete the drop tables of the mobs that live in your zones*.

## The rules

1. **Fresh start.** Spawn in Lumbridge; only your starting zone (64×64 map region
   by default, 8×8 chunks for hardcore) is unlocked.
2. **Sighting builds the log.** Combat NPCs you see in unlocked zones register
   Pokédex-style — their full drop table (from the OSRS Wiki) becomes part of that
   zone's collection log.
3. **Drops score points.** Each drop-table item has a point value by rarity
   (Common 1, Uncommon 3, Rare 8, Epic 20, Legendary 50 — all configurable). The
   first time you receive an item as a drop in an unlocked zone, it's collected.
4. **Thresholds earn zone tokens.** Reach the zone's point threshold (default 80%
   of its total points) and you earn a **zone token**. 100%-ing a zone earns a
   **bonus token**. Completionism literally buys map.
5. **You choose where to go.** Spend tokens on any *frontier* zone (adjacent to
   your unlocked area) from the panel — expansion is earned, direction is yours.
6. **Enforcement.** Clicks on NPCs, objects and ground items inside locked zones
   are cancelled outright (walking through is allowed). Kills in locked zones are
   logged violations and award nothing.
7. **Visuals: install [Region Locker](https://runelite.net/plugin-hub/show/region-locker)**
   from the Plugin Hub. Chunk Collector automatically mirrors your unlocked and
   frontier zones into it, so its terrain-hugging borders and shading render your
   world (enable the GPU plugin for the gray-out effect). Chunk Collector draws
   no zone overlays of its own.

Progress is saved per character; a **Reset run** config toggle wipes it.

## Running it

Requires JDK 11. From this folder:

```
gradlew.bat run
```

or double-click `run-chunk-tcg.cmd`. This starts a development RuneLite client with the
plugin loaded. Jagex accounts: follow
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)
(launcher flag `--insecure-write-credentials`, then one login through the Jagex Launcher).

## Credits & inspiration

Chunk Collector stands on the shoulders of these excellent plugins — go star them:

- **[Region Locker](https://github.com/slaytostay/region-locker)** by slaytostay
  (BSD-2-Clause) — region/chunk-locked account tooling; the zone shading concept.
- **[Hold Your Ground](https://github.com/skeldoor/hold-your-ground)** by skeldoor
  (BSD-2-Clause) — click-blocking enforcement for boundary-restricted accounts.
- **[Bronzeman Mode](https://github.com/sethrem/bronzeman)** by sethrem (MIT) — the
  unlock-splash notification style.
- **[OSRS TCG](https://github.com/Azderi/osrs-tcg)** by Azderi (BSD-2-Clause) —
  an earlier iteration of this plugin was a full card-game hybrid inspired by it
  (preserved at git tag `v0.1-tcg`).
- **[Chunk Picker](https://source-chunk.github.io/chunk-picker-v2/)** and the chunk-locked
  community (Limpwurt et al.) for the genre itself.
- Drop data: **[Old School RuneScape Wiki](https://oldschool.runescape.wiki)** (CC BY-NC-SA 3.0).
- Built from the [runelite/example-plugin](https://github.com/runelite/example-plugin)
  template (BSD-2-Clause).

This plugin is a fan-made challenge tracker for personal use. It injects no input,
automates nothing, and cannot fully prevent rule-breaking — it's a coach, not a referee.

## License

[BSD 2-Clause](LICENSE)
