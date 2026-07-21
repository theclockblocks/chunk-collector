package com.chunktcg;

import java.awt.Color;
import net.runelite.client.config.Alpha;
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
		description = "Overlay appearance",
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
		keyName = "starterMobs",
		name = "Starting zone mobs",
		description = "Killable NPCs in your starting zone, separated by semicolons. Seeded into the collection log at the start of a run (sighting discovers everything else).",
		section = progression,
		position = 2
	)
	default String starterMobs()
	{
		return "Goblin;Man;Giant spider";
	}

	@ConfigItem(
		keyName = "thresholdPercent",
		name = "Zone threshold",
		description = "Percentage of a zone's total collection points needed to earn its zone token (spend tokens to unlock a frontier zone of your choice)",
		section = progression,
		position = 3
	)
	@Range(min = 1, max = 100)
	@Units(Units.PERCENT)
	default int thresholdPercent()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "blockZoneInteractions",
		name = "Block locked-zone interactions",
		description = "Clicks on NPCs, objects and ground items inside locked zones are cancelled outright (hold-your-ground style). Walking through locked zones stays allowed.",
		section = progression,
		position = 4
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
		position = 5
	)
	default boolean warnOnEnterLocked()
	{
		return true;
	}

	@ConfigItem(
		keyName = "resetRun",
		name = "Reset run",
		description = "DANGER: turning this on wipes this character's entire run — zones, collection, tokens — and starts fresh. The toggle switches itself back off.",
		section = progression,
		position = 6
	)
	default boolean resetRun()
	{
		return false;
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

	@Alpha
	@ConfigItem(
		keyName = "lockedColor",
		name = "Locked zone shade",
		description = "Fill colour for locked zones in the scene and on the world map",
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
		description = "Border colour for zones adjacent to your unlocked area (candidates for unlocking)",
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
		description = "Border colour drawn around your unlocked zones",
		section = display,
		position = 2
	)
	default Color unlockedBorderColor()
	{
		return new Color(0, 255, 120, 140);
	}

	@ConfigItem(
		keyName = "shadeScene",
		name = "Shade locked zones in scene",
		description = "Draw a translucent shade over locked zones in the 3D scene",
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
		description = "Shade locked zones on the world map",
		section = display,
		position = 4
	)
	default boolean showWorldMap()
	{
		return true;
	}
}
