package com.chunktcg;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;

/**
 * Maps world coordinates to lock zones at the configured granularity
 * (64x64 regions by default, 8x8 chunks for hardcore runs). Zone ids use
 * the same packing as ChunkCoord: (zx << 16) | zy of world coords >> shift.
 */
@Singleton
public class ZoneGrid
{
	@Inject
	private ChunkTcgConfig config;

	public int shift()
	{
		return config.zoneSize().getShift();
	}

	public int sizeTiles()
	{
		return 1 << shift();
	}

	public int fromWorld(WorldPoint wp)
	{
		return fromWorld(wp.getX(), wp.getY());
	}

	public int fromWorld(int worldX, int worldY)
	{
		return ChunkCoord.id(worldX >> shift(), worldY >> shift());
	}

	/** South-west world tile of the zone. */
	public int baseX(int id)
	{
		return ChunkCoord.cx(id) << shift();
	}

	public int baseY(int id)
	{
		return ChunkCoord.cy(id) << shift();
	}

	public int centerX(int id)
	{
		return baseX(id) + sizeTiles() / 2;
	}

	public int centerY(int id)
	{
		return baseY(id) + sizeTiles() / 2;
	}

	public String describe(int id)
	{
		return "(" + ChunkCoord.cx(id) + ", " + ChunkCoord.cy(id) + ")";
	}
}
