package com.chunktcg;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Maps world coordinates to lock zones at the configured granularity
 * (64x64 regions by default, 8x8 chunks for hardcore runs). Zone ids use
 * the same packing as ChunkCoord: (zx << 16) | zy of world coords >> shift.
 */
@Slf4j
@Singleton
public class ZoneGrid
{
	@Inject
	private ChunkTcgConfig config;

	@Inject
	private Gson gson;

	/** Community region nicknames from Chunk Picker, keyed by RS region id. */
	private Map<String, String> regionNames;

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

	/**
	 * Human name for a zone: the chunk-locked community's nickname for the map
	 * region (e.g. "Lumbridge Castle") when in 64x64 mode, coords otherwise.
	 */
	public String describe(int id)
	{
		if (config.zoneSize() == ZoneSize.REGION_64)
		{
			String name = names().get(String.valueOf(rsRegionId(id)));
			if (name != null && !name.isEmpty())
			{
				return name;
			}
		}
		return "(" + ChunkCoord.cx(id) + ", " + ChunkCoord.cy(id) + ")";
	}

	private synchronized Map<String, String> names()
	{
		if (regionNames == null)
		{
			try (InputStream in = ZoneGrid.class.getResourceAsStream("/region-names.json"))
			{
				if (in == null)
				{
					regionNames = Collections.emptyMap();
				}
				else
				{
					Type t = new TypeToken<Map<String, String>>()
					{
					}.getType();
					regionNames = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), t);
				}
			}
			catch (Exception e)
			{
				log.debug("Failed loading region names", e);
				regionNames = Collections.emptyMap();
			}
		}
		return regionNames;
	}

	/**
	 * RuneScape map-region id for a zone — only meaningful in REGION_64 mode,
	 * where zones correspond 1:1 to map regions (e.g. Lumbridge = 12850).
	 * Used to sync unlocks into the Region Locker plugin's config.
	 */
	public int rsRegionId(int id)
	{
		return (ChunkCoord.cx(id) << 8) | ChunkCoord.cy(id);
	}
}
