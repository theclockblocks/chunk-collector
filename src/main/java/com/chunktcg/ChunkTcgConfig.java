package com.chunktcg;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(ChunkTcgConfig.GROUP)
public interface ChunkTcgConfig extends Config
{
	String GROUP = "chunktcg";

	@ConfigSection(
		name = "Progression",
		description = "Rules for unlocking new chunks",
		position = 0
	)
	String progression = "progression";

	@ConfigSection(
		name = "Economy",
		description = "Credits, packs and card values",
		position = 1
	)
	String economy = "economy";

	@ConfigSection(
		name = "Display",
		description = "Overlay appearance",
		position = 2
	)
	String display = "display";

	@ConfigItem(
		keyName = "zoneSize",
		name = "Zone size",
		description = "Granularity of locked areas. 64x64 regions unlock whole zones like the Region Locker plugin; 8x8 chunks are the hardcore chunk-locked experience. Changing this resets your unlocked zones (cards and credits are kept).",
		section = progression,
		position = 0
	)
	default ZoneSize zoneSize()
	{
		return ZoneSize.REGION_64;
	}

	@ConfigItem(
		keyName = "startingAreas",
		name = "Starting areas",
		description = "World tiles whose containing zones start unlocked, as x,y pairs separated by semicolons. Default covers Lumbridge (spawn + goblins east of the bridge).",
		section = progression,
		position = 1
	)
	default String startingAreas()
	{
		return "3222,3218;3245,3230";
	}

	@ConfigItem(
		keyName = "starterMobs",
		name = "Starting zone mobs",
		description = "Killable NPCs in your starting zone, separated by semicolons. All of them are auto-discovered when you begin, seeding the pack pool.",
		section = progression,
		position = 2
	)
	default String starterMobs()
	{
		return "Goblin;Man;Giant spider";
	}

	@ConfigItem(
		keyName = "starterPackCount",
		name = "Starter packs",
		description = "Number of free packs opened at the start of a run. Enough cards that you aren't locked out of everything; after that the normal economy takes over.",
		section = progression,
		position = 3
	)
	@Range(min = 0, max = 20)
	default int starterPackCount()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "starterBasics",
		name = "Starter basics pool",
		description = "Curated items mixed into STARTER packs only (separated by semicolons), so early pulls include usable gear, tools and food — nothing is free anymore, not even bronze.",
		section = progression,
		position = 4
	)
	default String starterBasics()
	{
		return "Bronze sword;Bronze axe;Bronze pickaxe;Bronze dagger;Wooden shield;Shortbow;Bronze arrow;"
			+ "Small fishing net;Tinderbox;Hammer;Chisel;Knife;Bucket;Pot;Bread;Shrimps;Cooked meat;"
			+ "Air rune;Mind rune;Water rune;Earth rune;Fire rune";
	}

	@ConfigItem(
		keyName = "starterCredits",
		name = "Starter credits",
		description = "Credits granted when you begin a run",
		section = progression,
		position = 5
	)
	default int starterCredits()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "alwaysUnlocked",
		name = "Always-unlocked items",
		description = "Item name prefixes that are usable without a card, separated by semicolons. Empty by default — even tutorial island bronze is locked until pulled.",
		section = progression,
		position = 6
	)
	default String alwaysUnlocked()
	{
		return "";
	}

	@ConfigItem(
		keyName = "enforceItemLock",
		name = "Lock items without cards",
		description = "Gray out items you haven't pulled from a pack and remove their action menu options. Packs are life.",
		section = progression,
		position = 7
	)
	default boolean enforceItemLock()
	{
		return true;
	}

	@ConfigItem(
		keyName = "allowedLockedOps",
		name = "Allowed on locked items",
		description = "The ONLY menu actions locked items keep, separated by semicolons. Everything else (Take, Use, Wield, Eat...) is removed from menus entirely.",
		section = progression,
		position = 8
	)
	default String allowedLockedOps()
	{
		return "Examine;Drop;Destroy;Deposit";
	}

	@ConfigItem(
		keyName = "blockZoneInteractions",
		name = "Block locked-zone interactions",
		description = "Clicks on NPCs, objects and ground items inside locked zones are cancelled outright (hold-your-ground style). Walking through locked zones stays allowed.",
		section = progression,
		position = 9
	)
	default boolean blockZoneInteractions()
	{
		return true;
	}

	@ConfigItem(
		keyName = "blockShopBuying",
		name = "Block buying locked items",
		description = "Bronzeman-style: shop and Grand Exchange purchases of items you haven't pulled are cancelled",
		section = progression,
		position = 10
	)
	default boolean blockShopBuying()
	{
		return true;
	}

	@ConfigItem(
		keyName = "warnOnEnterLocked",
		name = "Warn entering locked zone",
		description = "Show a chat warning when you walk into a locked zone",
		section = progression,
		position = 11
	)
	default boolean warnOnEnterLocked()
	{
		return true;
	}

	@ConfigItem(
		keyName = "resetRun",
		name = "Reset run",
		description = "DANGER: turning this on wipes this character's entire Chunk TCG run — zones, cards, credits, starter packs — and starts fresh. The toggle switches itself back off.",
		section = progression,
		position = 12
	)
	default boolean resetRun()
	{
		return false;
	}

	@ConfigItem(
		keyName = "packCost",
		name = "Pack cost",
		description = "Credit cost of one booster pack",
		section = economy,
		position = 0
	)
	default int packCost()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "packSize",
		name = "Cards per pack",
		description = "Number of cards pulled per booster pack",
		section = economy,
		position = 1
	)
	@Range(min = 1, max = 10)
	default int packSize()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "killCredits",
		name = "Credits per kill",
		description = "Base credits earned per NPC killed in an unlocked chunk (plus combat level / 10)",
		section = economy,
		position = 2
	)
	default int killCredits()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "zoneCardWeight",
		name = "Zone card weight",
		description = "Pull weight of zone cards relative to the rarity weights (Common 60, Uncommon 25, Rare 10, Epic 4, Legendary 1). At 8, roughly 1 in 14 cards is a zone unlock.",
		section = economy,
		position = 3
	)
	@Range(min = 0, max = 100)
	default int zoneCardWeight()
	{
		return 8;
	}

	@ConfigItem(
		keyName = "levelUpCredits",
		name = "Credits per level-up",
		description = "Credits earned each time you gain a skill level while in an unlocked zone. Two levels = one pack early on, and skilling (woodcutting!) becomes a pack grind.",
		section = economy,
		position = 4
	)
	default int levelUpCredits()
	{
		return 50;
	}

	@Alpha
	@ConfigItem(
		keyName = "lockedColor",
		name = "Locked chunk shade",
		description = "Fill colour for locked chunks in the scene and on the world map",
		section = display,
		position = 0
	)
	default Color lockedColor()
	{
		return new Color(0, 0, 0, 110);
	}

	@Alpha
	@ConfigItem(
		keyName = "frontierColor",
		name = "Frontier border",
		description = "Border colour for chunks adjacent to your unlocked area (candidates for unlocking)",
		section = display,
		position = 1
	)
	default Color frontierColor()
	{
		return new Color(255, 200, 0, 200);
	}

	@Alpha
	@ConfigItem(
		keyName = "unlockedBorderColor",
		name = "Unlocked border",
		description = "Border colour drawn around your unlocked chunks",
		section = display,
		position = 2
	)
	default Color unlockedBorderColor()
	{
		return new Color(0, 255, 120, 140);
	}

	@ConfigItem(
		keyName = "shadeScene",
		name = "Shade locked chunks in scene",
		description = "Draw a translucent shade over locked chunks in the 3D scene",
		section = display,
		position = 3
	)
	default boolean shadeScene()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showWorldMap",
		name = "Shade world map",
		description = "Shade locked chunks on the world map",
		section = display,
		position = 4
	)
	default boolean showWorldMap()
	{
		return true;
	}
}
