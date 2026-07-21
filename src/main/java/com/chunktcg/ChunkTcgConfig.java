package com.chunktcg;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(ChunkTcgConfig.GROUP)
public interface ChunkTcgConfig extends Config
{
	String GROUP = "chunktcg";

	@ConfigSection(
		name = "Progression",
		description = "Zones, thresholds and enforcement",
		position = 0
	)
	String progression = "progression";

	@ConfigSection(
		name = "Points",
		description = "Collection points per item rarity",
		position = 1
	)
	String points = "points";

	@ConfigSection(
		name = "Display",
		description = "Zone rendering — install the Region Locker hub plugin for visuals",
		position = 2
	)
	String display = "display";

	@ConfigItem(
		keyName = "zoneSize",
		name = "Zone size",
		description = "Granularity of locked areas. 64x64 regions unlock whole zones like the Region Locker plugin; 8x8 chunks are the hardcore chunk-locked experience. Changing this resets your unlocked zones (collection is kept).",
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
		description = "World tiles whose containing zones start unlocked, as x,y pairs separated by semicolons. Default covers Lumbridge.",
		section = progression,
		position = 1
	)
	default String startingAreas()
	{
		return "3222,3218;3245,3230";
	}

	@ConfigItem(
		keyName = "runGoal",
		name = "Run goal",
		description = "Your win condition, in your own words (e.g. 'Quest Cape', 'Defeat Jad', '25 zones'). Set it BEFORE your first drop — it locks in for the whole run and shows on the panel. Mark it complete from the Zones tab when you get there.",
		section = progression,
		position = 2
	)
	default String runGoal()
	{
		return "";
	}

	@ConfigItem(
		keyName = "challengesPerToken",
		name = "Challenges per bonus token",
		description = "Completing this many community challenges earns a bonus zone token. 0 disables challenge tokens.",
		section = progression,
		position = 3
	)
	@Range(min = 0, max = 50)
	default int challengesPerToken()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "thresholdPercent",
		name = "Zone threshold",
		description = "Percentage of a zone's total collection points needed to earn its zone token. Set this BEFORE your first drop — it locks in for the whole run once the first item is logged.",
		section = progression,
		position = 4
	)
	@Range(min = 1, max = 100)
	@Units(Units.PERCENT)
	default int thresholdPercent()
	{
		return 80;
	}

	@ConfigItem(
		keyName = "blockZoneInteractions",
		name = "Block locked-zone interactions",
		description = "Clicks on NPCs, objects and ground items inside locked zones are cancelled outright (hold-your-ground style). Walking through locked zones stays allowed.",
		section = progression,
		position = 5
	)
	default boolean blockZoneInteractions()
	{
		return true;
	}

	@ConfigItem(
		keyName = "warnOnEnterLocked",
		name = "Warn entering locked zone",
		description = "Show a chat warning when you walk into a locked zone",
		section = progression,
		position = 6
	)
	default boolean warnOnEnterLocked()
	{
		return true;
	}

	@ConfigItem(
		keyName = "resetConfirm",
		name = "Reset run (type: reset)",
		description = "DANGER: type the word reset into this field to wipe this character's entire run — zones, collection, tokens, locked threshold and goal. The field clears itself afterwards.",
		section = progression,
		position = 7
	)
	default String resetConfirm()
	{
		return "";
	}

	@ConfigItem(
		keyName = "pointsCommon",
		name = "Common",
		description = "Points for collecting a Common drop",
		section = points,
		position = 0
	)
	default int pointsCommon()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "pointsUncommon",
		name = "Uncommon",
		description = "Points for collecting an Uncommon drop",
		section = points,
		position = 1
	)
	default int pointsUncommon()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "pointsRare",
		name = "Rare",
		description = "Points for collecting a Rare drop",
		section = points,
		position = 2
	)
	default int pointsRare()
	{
		return 8;
	}

	@ConfigItem(
		keyName = "pointsEpic",
		name = "Epic",
		description = "Points for collecting an Epic drop",
		section = points,
		position = 3
	)
	default int pointsEpic()
	{
		return 20;
	}

	@ConfigItem(
		keyName = "pointsLegendary",
		name = "Legendary",
		description = "Points for collecting a Legendary drop",
		section = points,
		position = 4
	)
	default int pointsLegendary()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "syncRegionLocker",
		name = "Sync to Region Locker",
		description = "Write unlocked zones and frontier zones into the Region Locker plugin's region lists, so its terrain-hugging borders and shading render your world. Requires 64x64 zone size and the Region Locker hub plugin installed. WARNING: overwrites Region Locker's own region lists.",
		section = display,
		position = 0
	)
	default boolean syncRegionLocker()
	{
		return true;
	}
}
