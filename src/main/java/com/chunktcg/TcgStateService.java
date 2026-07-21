package com.chunktcg;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * All per-character progression state: unlocked chunks, discovered NPCs,
 * card collection, credits, violations. Persisted to the RS profile config
 * so each character has its own run.
 */
@Slf4j
@Singleton
public class TcgStateService
{
	private static final String KEY_UNLOCKED = "unlockedChunksState";
	private static final String KEY_DISCOVERED = "discoveredNpcs";
	private static final String KEY_CARDS = "cards";
	private static final String KEY_CREDITS = "credits";
	private static final String KEY_VIOLATIONS = "violations";
	private static final String KEY_PURCHASES = "chunkPurchases";
	private static final String KEY_ZONE_MODE = "zoneMode";
	private static final String KEY_STARTER_PACKS = "starterPacksOpened";

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	@Inject
	private ChunkTcgConfig config;

	@Inject
	private WikiDropsService drops;

	@Inject
	private ZoneGrid zones;

	// Concurrent: mutated on the client thread, read from overlays and the Swing EDT
	@Getter
	private final Set<Integer> unlockedChunks = ConcurrentHashMap.newKeySet();

	/** chunkId -> NPC names discovered (killed) there. */
	@Getter
	private final Map<Integer, Set<String>> discovered = new ConcurrentHashMap<>();

	/** lower item name -> card. */
	@Getter
	private final Map<String, CardEntry> cards = new ConcurrentHashMap<>();

	@Getter
	private int credits;

	@Getter
	private int violations;

	@Getter
	private int chunkPurchases;

	@Getter
	private int starterPacksOpened;

	@Getter
	private boolean loaded;

	// ---- lifecycle ----

	public void load()
	{
		unlockedChunks.clear();
		discovered.clear();
		cards.clear();
		credits = 0;
		violations = 0;
		chunkPurchases = 0;

		// Zone ids are only meaningful at the granularity they were saved at —
		// only trust them when the saved mode marker matches the current setting
		String savedMode = configManager.getRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_ZONE_MODE);
		boolean modeChanged = !config.zoneSize().name().equals(savedMode);

		String unlockedJson = configManager.getRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_UNLOCKED);
		if (!modeChanged && unlockedJson != null && !unlockedJson.isEmpty())
		{
			Type t = new TypeToken<Set<Integer>>()
			{
			}.getType();
			Set<Integer> saved = gson.fromJson(unlockedJson, t);
			if (saved != null)
			{
				unlockedChunks.addAll(saved);
			}
		}
		if (unlockedChunks.isEmpty())
		{
			unlockedChunks.addAll(parseStartingAreas());
		}
		if (modeChanged)
		{
			log.debug("Zone size changed from {} to {} — unlocked zones reset", savedMode, config.zoneSize().name());
		}

		String discoveredJson = configManager.getRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_DISCOVERED);
		if (discoveredJson != null && !discoveredJson.isEmpty())
		{
			Type t = new TypeToken<Map<Integer, Set<String>>>()
			{
			}.getType();
			Map<Integer, Set<String>> saved = gson.fromJson(discoveredJson, t);
			if (saved != null)
			{
				for (Map.Entry<Integer, Set<String>> e : saved.entrySet())
				{
					Set<String> copy = ConcurrentHashMap.newKeySet();
					copy.addAll(e.getValue());
					discovered.put(e.getKey(), copy);
				}
			}
		}

		String cardsJson = configManager.getRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_CARDS);
		if (cardsJson != null && !cardsJson.isEmpty())
		{
			Type t = new TypeToken<Map<String, CardEntry>>()
			{
			}.getType();
			Map<String, CardEntry> saved = gson.fromJson(cardsJson, t);
			if (saved != null)
			{
				cards.putAll(saved);
			}
		}

		credits = getIntKey(KEY_CREDITS);
		violations = getIntKey(KEY_VIOLATIONS);
		chunkPurchases = getIntKey(KEY_PURCHASES);
		starterPacksOpened = getIntKey(KEY_STARTER_PACKS);
		loaded = true;
		log.debug("Loaded state: {} chunks, {} cards, {} credits", unlockedChunks.size(), cards.size(), credits);
	}

	public void unload()
	{
		loaded = false;
	}

	private int getIntKey(String key)
	{
		String v = configManager.getRSProfileConfiguration(ChunkTcgConfig.GROUP, key);
		try
		{
			return v == null ? 0 : Integer.parseInt(v);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private void save()
	{
		if (!loaded)
		{
			return;
		}
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_UNLOCKED, gson.toJson(unlockedChunks));
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_DISCOVERED, gson.toJson(discovered));
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_CARDS, gson.toJson(cards));
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_CREDITS, credits);
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_VIOLATIONS, violations);
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_PURCHASES, chunkPurchases);
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_ZONE_MODE, config.zoneSize().name());
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_STARTER_PACKS, starterPacksOpened);
	}

	public void incrementStarterPacks()
	{
		starterPacksOpened++;
		save();
	}

	public boolean starterComplete()
	{
		return starterPacksOpened >= config.starterPackCount();
	}

	/** Wipe this character's entire run and start fresh. */
	public void resetRun()
	{
		unlockedChunks.clear();
		discovered.clear();
		cards.clear();
		credits = 0;
		violations = 0;
		chunkPurchases = 0;
		starterPacksOpened = 0;
		unlockedChunks.addAll(parseStartingAreas());
		save();
		log.debug("Run reset");
	}

	/** Zone containing the first configured starting area — where starter mobs register. */
	public int primaryZoneId()
	{
		for (String pair : config.startingAreas().split(";"))
		{
			String[] xy = pair.trim().split(",");
			if (xy.length != 2)
			{
				continue;
			}
			try
			{
				return zones.fromWorld(Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim()));
			}
			catch (NumberFormatException e)
			{
				// try next pair
			}
		}
		return zones.fromWorld(3222, 3218);
	}

	private Set<Integer> parseStartingAreas()
	{
		Set<Integer> out = new HashSet<>();
		for (String pair : config.startingAreas().split(";"))
		{
			String[] xy = pair.trim().split(",");
			if (xy.length != 2)
			{
				continue;
			}
			try
			{
				out.add(zones.fromWorld(Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())));
			}
			catch (NumberFormatException e)
			{
				log.debug("Bad starting area entry: {}", pair);
			}
		}
		if (out.isEmpty())
		{
			// Lumbridge spawn as an absolute fallback
			out.add(zones.fromWorld(3222, 3218));
		}
		return out;
	}

	// ---- chunk state ----

	public boolean isUnlocked(int chunkId)
	{
		return unlockedChunks.contains(chunkId);
	}

	/** Locked chunks orthogonally adjacent to an unlocked chunk. */
	public Set<Integer> frontier()
	{
		Set<Integer> out = new TreeSet<>();
		for (int id : unlockedChunks)
		{
			int cx = ChunkCoord.cx(id);
			int cy = ChunkCoord.cy(id);
			int[][] neighbors = {{cx + 1, cy}, {cx - 1, cy}, {cx, cy + 1}, {cx, cy - 1}};
			for (int[] n : neighbors)
			{
				int nid = ChunkCoord.id(n[0], n[1]);
				if (!unlockedChunks.contains(nid))
				{
					out.add(nid);
				}
			}
		}
		return out;
	}

	// ---- collection ----

	public Set<String> allDiscoveredNpcs()
	{
		Set<String> out = new HashSet<>();
		for (Set<String> names : discovered.values())
		{
			out.addAll(names);
		}
		return out;
	}

	/** true if this NPC was newly discovered for the chunk. */
	public boolean discoverNpc(int chunkId, String npcName)
	{
		boolean added = discovered.computeIfAbsent(chunkId, k -> ConcurrentHashMap.newKeySet()).add(npcName);
		if (added)
		{
			save();
		}
		return added;
	}

	/** Award a card by item. Returns true if it's a brand new card. */
	public boolean awardCard(String itemName, int itemId)
	{
		String key = WikiDropsService.normalize(itemName);
		CardEntry entry = cards.get(key);
		boolean isNew = entry == null;
		if (isNew)
		{
			cards.put(key, new CardEntry(itemName, itemId, 1));
		}
		else
		{
			entry.setCount(entry.getCount() + 1);
			if (entry.getItemId() < 0 && itemId >= 0)
			{
				entry.setItemId(itemId);
			}
		}
		save();
		return isNew;
	}

	public void addCredits(int amount)
	{
		credits = Math.max(0, credits + amount);
		save();
	}

	public void addViolation()
	{
		violations++;
		save();
	}

	/** Sell every duplicate copy (count above 1). Returns credits gained. */
	public int sellAllDupes()
	{
		Collection<String> npcs = allDiscoveredNpcs();
		int gained = 0;
		for (CardEntry entry : cards.values())
		{
			int dupes = entry.getCount() - 1;
			if (dupes <= 0)
			{
				continue;
			}
			RarityTier tier = drops.tierFor(entry.getName(), npcs);
			gained += dupes * tier.getSellValue();
			entry.setCount(1);
		}
		if (gained > 0)
		{
			credits += gained;
			save();
		}
		return gained;
	}

	// ---- progression ----

	/** [ownedUnique, totalUnique] over the union of discovered NPCs' drop tables. */
	public int[] completion()
	{
		Map<String, Drop> union = drops.unionDrops(allDiscoveredNpcs());
		int owned = 0;
		for (String key : union.keySet())
		{
			if (cards.containsKey(key))
			{
				owned++;
			}
		}
		return new int[]{owned, union.size()};
	}

	/** Unlock a zone (from a zone card pull). */
	public void unlockZone(int chunkId)
	{
		if (unlockedChunks.add(chunkId))
		{
			chunkPurchases++;
			save();
		}
	}

	/** An item is usable if it was pulled from a pack or matches an always-unlocked prefix. */
	public boolean isItemUnlocked(String itemName)
	{
		if (itemName == null)
		{
			return true;
		}
		String key = WikiDropsService.normalize(itemName);
		if (cards.containsKey(key))
		{
			return true;
		}
		for (String prefix : config.alwaysUnlocked().split(";"))
		{
			String p = WikiDropsService.normalize(prefix);
			if (!p.isEmpty() && key.startsWith(p))
			{
				return true;
			}
		}
		return false;
	}

	/** Owned cards among a drop list, for per-NPC album progress. */
	public int ownedOf(List<Drop> dropList)
	{
		int owned = 0;
		for (Drop d : dropList)
		{
			if (cards.containsKey(WikiDropsService.normalize(d.getItemName())))
			{
				owned++;
			}
		}
		return owned;
	}
}
