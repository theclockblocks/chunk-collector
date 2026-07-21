package com.chunktcg;

import net.runelite.api.coords.WorldPoint;

/**
 * Helpers for 8x8 map chunks. A chunk id packs chunk coords (worldX >> 3, worldY >> 3)
 * into a single int: (cx << 16) | cy. Locks apply across all planes.
 */
public final class ChunkCoord
{
	public static final int CHUNK_SIZE = 8;

	private ChunkCoord()
	{
	}

	public static int id(int chunkX, int chunkY)
	{
		return (chunkX << 16) | (chunkY & 0xFFFF);
	}

	public static int cx(int id)
	{
		return id >>> 16;
	}

	public static int cy(int id)
	{
		return id & 0xFFFF;
	}

	public static int fromWorld(WorldPoint wp)
	{
		return id(wp.getX() >> 3, wp.getY() >> 3);
	}

	public static int fromWorld(int worldX, int worldY)
	{
		return id(worldX >> 3, worldY >> 3);
	}

	/** South-west world tile of the chunk. */
	public static int baseX(int id)
	{
		return cx(id) << 3;
	}

	public static int baseY(int id)
	{
		return cy(id) << 3;
	}

	public static String describe(int id)
	{
		return "(" + cx(id) + ", " + cy(id) + ")";
	}
}
