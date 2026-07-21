package com.chunktcg;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

/**
 * Curated "drop tables" for skilling nodes: trees, rocks and fishing spots
 * act like mobs — sight them in a zone, gather from them to tick their log.
 */
@Slf4j
@Singleton
public class NodeTables
{
	@Data
	private static class NodeDef
	{
		private String skill;
		private Map<String, String> items;
	}

	private static final Map<RarityTier, Double> TIER_RATES = new HashMap<>();

	static
	{
		TIER_RATES.put(RarityTier.COMMON, 1.0 / 4);
		TIER_RATES.put(RarityTier.UNCOMMON, 1.0 / 32);
		TIER_RATES.put(RarityTier.RARE, 1.0 / 128);
		TIER_RATES.put(RarityTier.EPIC, 1.0 / 1000);
		TIER_RATES.put(RarityTier.LEGENDARY, 1.0 / 5000);
	}

	@Inject
	private Gson gson;

	private Map<String, NodeDef> defs;
	private Map<String, List<Drop>> tables;

	public boolean isNode(String name)
	{
		return name != null && data().containsKey(normalize(name));
	}

	/** The gathering skill for a node, or null if not a node. */
	public Skill skillOf(String name)
	{
		NodeDef def = data().get(normalize(name));
		if (def == null)
		{
			return null;
		}
		try
		{
			return Skill.valueOf(def.getSkill());
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/** The node's curated table as Drops, or null if not a node. */
	public synchronized List<Drop> tableOf(String name)
	{
		String key = normalize(name);
		if (!data().containsKey(key))
		{
			return null;
		}
		if (tables == null)
		{
			tables = new HashMap<>();
		}
		return tables.computeIfAbsent(key, k ->
		{
			List<Drop> out = new ArrayList<>();
			for (Map.Entry<String, String> e : data().get(k).getItems().entrySet())
			{
				RarityTier tier;
				try
				{
					tier = RarityTier.valueOf(e.getValue());
				}
				catch (IllegalArgumentException ex)
				{
					tier = RarityTier.COMMON;
				}
				out.add(new Drop(e.getKey(), TIER_RATES.get(tier)));
			}
			return out;
		});
	}

	public boolean isGatheringSkill(Skill skill)
	{
		for (NodeDef def : data().values())
		{
			if (def.getSkill().equals(skill.name()))
			{
				return true;
			}
		}
		return false;
	}

	private static String normalize(String s)
	{
		return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
	}

	private synchronized Map<String, NodeDef> data()
	{
		if (defs == null)
		{
			try (InputStream in = NodeTables.class.getResourceAsStream("/node-tables.json"))
			{
				if (in == null)
				{
					defs = Collections.emptyMap();
				}
				else
				{
					Type t = new TypeToken<Map<String, NodeDef>>()
					{
					}.getType();
					Map<String, NodeDef> raw = gson.fromJson(
						new InputStreamReader(in, StandardCharsets.UTF_8), t);
					defs = new HashMap<>();
					for (Map.Entry<String, NodeDef> e : raw.entrySet())
					{
						defs.put(normalize(e.getKey()), e.getValue());
					}
				}
			}
			catch (Exception e)
			{
				log.debug("Failed loading node tables", e);
				defs = Collections.emptyMap();
			}
		}
		return defs;
	}
}
