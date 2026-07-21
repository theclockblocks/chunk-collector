# Chunk TCG

**Packs are life.** A RuneLite plugin that turns an OSRS account into a zone-locked,
gacha-driven challenge run: the world is locked outside your starting zone, every item
is locked until you pull its card from a booster pack, and even *map expansion* comes
from pack pulls.

## The rules

1. **Fresh start.** Finish tutorial island, spawn in Lumbridge, dump your inventory,
   then enable the plugin. Nothing is unlocked — not even your bronze gear.
2. **Starter packs.** You open 5 free starter packs drawn from your starting zone's
   mobs (Goblin, Man, Giant spider by default) plus a curated basics pool (bronze
   tools, basic food, runes). Fate decides what kind of adventurer you become.
3. **Cards from packs only.** Drops never award cards. Killing an NPC in an unlocked
   zone *discovers* it — its whole drop table joins the pack pool — but the cards
   themselves only come from packs.
4. **Credits buy packs.** Kills in unlocked zones pay credits (base + combat level/10),
   level-ups pay 50 each, and duplicate cards can be sold. 100 credits = one 5-card pack.
5. **Items are locked** until you pull their card: grayed out in inventory/equipment/
   bank, Wield/Wear/Eat/Drink removed from menus, and shop **Buy is blocked**
   (bronzeman-style).
6. **Zones are locked** (64×64 map regions by default, 8×8 chunks for hardcore).
   Clicking NPCs, objects or ground items inside locked zones is cancelled outright.
   Walking through is allowed for traversal.
7. **Zone cards.** Every zone adjacent to your unlocked area is a card mixed into the
   pack pool (~7% per pull by default). Pull one and a random frontier zone unlocks.
   More zones → more frontier → more ways for fate to move you.

Rarity tiers (Common → Legendary) come from real OSRS Wiki drop rates, fetched live
and cached. Progress is saved per character; a **Reset run** toggle wipes it.

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

Chunk TCG stands on the shoulders of these excellent plugins — go star them:

- **[OSRS TCG](https://github.com/Azderi/osrs-tcg)** by Azderi (BSD-2-Clause) — the
  card-collecting concept: credits earned by playing, booster packs, rarity tiers,
  collection album. Chunk TCG's card economy is directly inspired by it.
- **[Region Locker](https://github.com/slaytostay/region-locker)** by slaytostay
  (BSD-2-Clause) — region/chunk-locked account tooling; the zone shading concept.
- **[Bronzeman Mode](https://github.com/sethrem/bronzeman)** by sethrem (MIT) — the
  item-unlock philosophy, unlock splash notification, and Grand Exchange restriction.
- **[Hold Your Ground](https://github.com/skeldoor/hold-your-ground)** by skeldoor
  (BSD-2-Clause) — click-blocking enforcement for boundary-restricted accounts.
- **[Chunk Picker](https://source-chunk.github.io/chunk-picker-v2/)** and the chunk-locked
  community (Limpwurt et al.) for the genre itself.
- **[Archipelago](https://archipelago.gg)** for the randomizer philosophy: progression
  gated behind randomized unlocks.
- Drop data: **[Old School RuneScape Wiki](https://oldschool.runescape.wiki)** (CC BY-NC-SA 3.0).
- Built from the [runelite/example-plugin](https://github.com/runelite/example-plugin)
  template (BSD-2-Clause).

This plugin is a fan-made challenge tracker for personal use. It injects no input,
automates nothing, and cannot fully prevent rule-breaking — it's a coach, not a referee.

## License

[BSD 2-Clause](LICENSE)
