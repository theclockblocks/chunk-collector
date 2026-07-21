# Chunk TCG

A RuneLite plugin that combines a **chunk-locked account** (expanding 8×8 chunks from
Lumbridge) with a **trading card game** built from the drop tables of NPCs in your
unlocked chunks. Inspired by [OSRS TCG](https://github.com/Azderi/osrs-tcg),
[Region Locker](https://github.com/slaytostay/region-locker) and Archipelago-style
progression.

## How it works

- You start with two chunks unlocked: Lumbridge spawn and the goblin area east of
  the bridge (configurable). Goblins are unlocked at the start because you can punch them.
- **Discovery**: killing an NPC in an unlocked chunk registers it to that chunk and
  fetches its drop table from the OSRS Wiki (cached in `.runelite/chunk-tcg/`).
  Every item on discovered drop tables becomes a card slot in your album.
- **Cards**: every drop you receive earns that item's card. Rarity tiers
  (Common / Uncommon / Rare / Epic / Legendary) are derived from real drop rates.
  Duplicate drops stack.
- **Credits**: earned per kill in unlocked chunks (base + combat level / 10), with a
  bonus for brand-new cards. Sell your duplicate cards for credits in the Packs tab.
- **Packs**: spend credits on 5-card booster packs pulled (rarity-weighted) from the
  union of all your unlocked chunks' drop tables.
- **Unlocking chunks**: once your card set completion reaches the threshold
  (default 80%), you can buy any *frontier* chunk (adjacent to your unlocked area)
  for credits. The cost rises with each chunk purchased. Chunks with no NPCs never
  block you — empty set counts as complete.
- **Enforcement**: locked chunks are shaded in the scene and on the world map.
  Walking through locked chunks is allowed, but kills/loot there award nothing and
  are logged as violations.
- Instanced areas (raids, quest instances) are ignored entirely — no cards, no violations.
- Progress is saved **per character** (RuneLite profile config).

## Running it

Requires JDK 11 (installed via Temurin). From this folder:

```
gradlew.bat run
```

or double-click `run-chunk-tcg.cmd`. This starts a development RuneLite client with
the plugin loaded. If you use a Jagex account, follow
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)
to log in on the development client.

Then open the **Chunk TCG** side panel (card icon) — tabs: **Album**, **Packs**, **Chunks**.

## Notes / limits

- Drop tables come from wiki `{{DropsLine}}` templates; multi-level NPC pages share
  one table. Herb/rare-drop-table sub-tables aren't expanded (yet).
- Pack pulls of untradeable items may show without an icon until you actually
  receive one as a drop.
- The plugin can't physically stop you from walking anywhere — it's a challenge
  tracker, like all chunk-lock tooling.
