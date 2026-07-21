package com.chunktcg;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
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
 * All per-character progression state: unlocked zones, discovered NPCs,
 * collected drop-table items, zone tokens. Persisted to the RS profile
 * config so each character has its own run.
 */
@Slf4j
@Singleton
public class TcgStateService
{
	private static final String KEY_UNLOCKED = "unlockedChunksState";
	private static final String KEY_DISCOVERED = "discoveredNpcs";
	private static final String KEY_COLLECTED = "cards";
	private static final String KEY_VIOLATIONS = "violations";
	private static final String KEY_TOKENS = "zoneTokens";
	private static final String KEY_CLAIMS = "zoneClaims";
	private static final String KEY_ZONE_MODE = "zoneMode";
	private static final String KEY_LOCKED_THRESHOLD = "lockedThreshold";
	private static final String KEY_KILLS = "killCounts";
	private static final String KEY_LOCKED_GOAL = "lockedGoal";
	private static final String KEY_GOAL_COMPLETE = "goalComplete";
	private static final String KEY_CHALLENGES_DONE = "challengesDone";
	private static final String KEY_CHALLENGE_TOKENS = "challengeTokensGranted";

	/** Claim bitmask per zone. */
	public static final int CLAIM_THRESHOLD = 1;
	public static final int CLAIM_FULL = 2;

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

	/** chunkId -> NPC names discovered (sighted/killed) there. */
	@Getter
	private final Map<Integer, Set<String>> discovered = new ConcurrentHashMap<>();

	/**
	 * Collection is PER ZONE AND MOB: keyed "zoneId|mobname|itemname".
	 * Bones from a castle goblin tick neither the cow's slot nor the slot of
	 * a goblin living in another zone.
	 */
	@Getter
	private final Map<String, CardEntry> collected = new ConcurrentHashMap<>();

	/** "zoneId|mobname" -> counted kills (in unlocked zones only). */
	@Getter
	private final Map<String, Integer> killCounts = new ConcurrentHashMap<>();

	/** zoneId -> claim bitmask (threshold reached / 100% completed). */
	@Getter
	private final Map<Integer, Integer> zoneClaims = new ConcurrentHashMap<>();

	@Getter
	private int zoneTokens;

	@Getter
	private int violations;

	/** Threshold % frozen at the first logged drop; 0 = not locked yet. */
	@Getter
	private int lockedThreshold;

	/** Run goal frozen at the first logged drop; null = not locked yet. */
	private String lockedGoal;

	@Getter
	private boolean goalComplete;

	/** "zoneId|challenge name" of ticked community challenges. */
	@Getter
	private final Set<String> completedChallenges = ConcurrentHashMap.newKeySet();

	@Getter
	private int challengeTokensGranted;

	@Getter
	private boolean loaded;

	// ---- lifecycle ----

	public void load()
	{
		unlockedChunks.clear();
		discovered.clear();
		collected.clear();
		zoneClaims.clear();
		zoneTokens = 0;
		violations = 0;

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
		if (!modeChanged && discoveredJson != null && !discoveredJson.isEmpty())
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

		String collectedJson = configManager.getRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_COLLECTED);
		if (collectedJson != null && !collectedJson.isEmpty())
		{
			Type t = new TypeToken<Map<String, CardEntry>>()
			{
			}.getType();
			Map<String, CardEntry> saved = gson.fromJson(collectedJson, t);
			if (saved != null)
			{
				// Only zone-scoped keys ("zone|mob|item") are valid; drop legacy formats
				for (Map.Entry<String, CardEntry> e : saved.entrySet())
				{
					if (e.getKey().split("\\|").length == 3)
					{
						collected.put(e.getKey(), e.getValue());
					}
				}
			}
		}

		String killsJson = configManager.getRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_KILLS);
		if (killsJson != null && !killsJson.isEmpty())
		{
			Type t = new TypeToken<Map<String, Integer>>()
			{
			}.getType();
			Map<String, Integer> saved = gson.fromJson(killsJson, t);
			if (saved != null)
			{
				// Only zone-scoped keys ("zone|mob") are valid
				for (Map.Entry<String, Integer> e : saved.entrySet())
				{
					if (e.getKey().contains("|"))
					{
						killCounts.put(e.getKey(), e.getValue());
					}
				}
			}
		}

		String claimsJson = configManager.getRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_CLAIMS);
		if (!modeChanged && claimsJson != null && !claimsJson.isEmpty())
		{
			Type t = new TypeToken<Map<Integer, Integer>>()
			{
			}.getType();
			Map<Integer, Integer> saved = gson.fromJson(claimsJson, t);
			if (saved != null)
			{
				zoneClaims.putAll(saved);
			}
		}

		String challengesJson = configManager.getRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_CHALLENGES_DONE);
		if (!modeChanged && challengesJson != null && !challengesJson.isEmpty())
		{
			Type t = new TypeToken<Set<String>>()
			{
			}.getType();
			Set<String> saved = gson.fromJson(challengesJson, t);
			if (saved != null)
			{
				completedChallenges.addAll(saved);
			}
		}

		zoneTokens = getIntKey(KEY_TOKENS);
		violations = getIntKey(KEY_VIOLATIONS);
		lockedThreshold = getIntKey(KEY_LOCKED_THRESHOLD);
		challengeTokensGranted = getIntKey(KEY_CHALLENGE_TOKENS);
		lockedGoal = configManager.getRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_LOCKED_GOAL);
		goalComplete = Boolean.parseBoolean(
			configManager.getRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_GOAL_COMPLETE));
		loaded = true;
		log.debug("Loaded state: {} zones, {} items collected, {} tokens",
			unlockedChunks.size(), collected.size(), zoneTokens);
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
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_COLLECTED, gson.toJson(collected));
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_CLAIMS, gson.toJson(zoneClaims));
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_KILLS, gson.toJson(killCounts));
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_TOKENS, zoneTokens);
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_VIOLATIONS, violations);
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_LOCKED_THRESHOLD, lockedThreshold);
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_LOCKED_GOAL,
			lockedGoal == null ? "" : lockedGoal);
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_GOAL_COMPLETE, goalComplete);
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_CHALLENGES_DONE,
			gson.toJson(completedChallenges));
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_CHALLENGE_TOKENS, challengeTokensGranted);
		configManager.setRSProfileConfiguration(ChunkTcgConfig.GROUP, KEY_ZONE_MODE, config.zoneSize().name());
	}

	/**
	 * The threshold in force for this run: the config value until the first
	 * drop is logged, frozen from then on until a reset.
	 */
	public int effectiveThresholdPercent()
	{
		return lockedThreshold > 0 ? lockedThreshold : config.thresholdPercent();
	}

	public boolean isThresholdLocked()
	{
		return lockedThreshold > 0;
	}

	/** The run goal in force: config value until the first drop, frozen after. */
	public String effectiveGoal()
	{
		if (lockedThreshold > 0)
		{
			return lockedGoal == null ? "" : lockedGoal;
		}
		String g = config.runGoal();
		return g == null ? "" : g.trim();
	}

	public void markGoalComplete()
	{
		goalComplete = true;
		save();
	}

	/**
	 * Toggle a community challenge. Returns the number of bonus tokens newly
	 * granted (0 on untick or below the next multiple).
	 */
	public int toggleChallenge(int zoneId, String challengeName)
	{
		String key = zoneId + "|" + challengeName;
		if (!completedChallenges.remove(key))
		{
			completedChallenges.add(key);
		}
		int granted = 0;
		int per = config.challengesPerToken();
		if (per > 0)
		{
			int earnedTotal = completedChallenges.size() / per;
			if (earnedTotal > challengeTokensGranted)
			{
				granted = earnedTotal - challengeTokensGranted;
				challengeTokensGranted = earnedTotal;
				zoneTokens += granted;
			}
		}
		save();
		return granted;
	}

	public boolean isChallengeDone(int zoneId, String challengeName)
	{
		return completedChallenges.contains(zoneId + "|" + challengeName);
	}

	/** Wipe this character's entire run and start fresh. */
	public void resetRun()
	{
		unlockedChunks.clear();
		discovered.clear();
		collected.clear();
		zoneClaims.clear();
		killCounts.clear();
		completedChallenges.clear();
		zoneTokens = 0;
		violations = 0;
		lockedThreshold = 0;
		challengeTokensGranted = 0;
		lockedGoal = null;
		goalComplete = false;
		unlockedChunks.addAll(parseStartingAreas());
		save();
		log.debug("Run reset");
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

	// ---- zone state ----

	public boolean isUnlocked(int chunkId)
	{
		return unlockedChunks.contains(chunkId);
	}

	/** Locked zones orthogonally adjacent to an unlocked zone. */
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

	/** Spend a zone token to unlock a frontier zone. Returns null on success, else a reason. */
	public String tryUnlock(int chunkId)
	{
		if (isUnlocked(chunkId))
		{
			return "Zone already unlocked";
		}
		if (!frontier().contains(chunkId))
		{
			return "Zone is not adjacent to your unlocked area";
		}
		if (zoneTokens <= 0)
		{
			return "No zone tokens — hit a zone's point threshold to earn one";
		}
		zoneTokens--;
		unlockedChunks.add(chunkId);
		save();
		return null;
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

	/** true if this NPC was newly discovered for the zone. */
	public boolean discoverNpc(int chunkId, String npcName)
	{
		boolean added = discovered.computeIfAbsent(chunkId, k -> ConcurrentHashMap.newKeySet()).add(npcName);
		if (added)
		{
			save();
		}
		return added;
	}

	private static String collectionKey(int zoneId, String mobName, String itemName)
	{
		return zoneId + "|" + WikiDropsService.normalize(mobName)
			+ "|" + WikiDropsService.normalize(itemName);
	}

	/** Record a drop collected from this mob IN this zone. Returns true if new. */
	public boolean collectItem(int zoneId, String mobName, String itemName, int itemId)
	{
		// The first logged drop freezes the run's threshold and goal — no goalpost-moving
		if (lockedThreshold == 0)
		{
			lockedThreshold = config.thresholdPercent();
			lockedGoal = config.runGoal() == null ? "" : config.runGoal().trim();
		}
		String key = collectionKey(zoneId, mobName, itemName);
		CardEntry entry = collected.get(key);
		boolean isNew = entry == null;
		if (isNew)
		{
			collected.put(key, new CardEntry(itemName, itemId, 1));
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

	public boolean isCollected(int zoneId, String mobName, String itemName)
	{
		return collected.containsKey(collectionKey(zoneId, mobName, itemName));
	}

	public CardEntry getCollectedEntry(int zoneId, String mobName, String itemName)
	{
		return collected.get(collectionKey(zoneId, mobName, itemName));
	}

	/** Count a kill toward the mob's kc in that zone (unlocked-zone kills only). */
	public void addKill(int zoneId, String mobName)
	{
		killCounts.merge(zoneId + "|" + WikiDropsService.normalize(mobName), 1, Integer::sum);
		save();
	}

	public int killCount(int zoneId, String mobName)
	{
		Integer kc = killCounts.get(zoneId + "|" + WikiDropsService.normalize(mobName));
		return kc == null ? 0 : kc;
	}

	public void addViolation()
	{
		violations++;
		save();
	}

	/** Grant tokens directly (used by the ::chunktoken test command). */
	public void addZoneTokens(int n)
	{
		zoneTokens += n;
		save();
	}

	// ---- points & tokens ----

	public int pointsFor(RarityTier tier)
	{
		switch (tier)
		{
			case UNCOMMON:
				return config.pointsUncommon();
			case RARE:
				return config.pointsRare();
			case EPIC:
				return config.pointsEpic();
			case LEGENDARY:
				return config.pointsLegendary();
			case COMMON:
			default:
				return config.pointsCommon();
		}
	}

	/**
	 * [earnedPoints, totalPoints] for a zone: each discovered mob's drop table
	 * is its own checklist — the same item on two mobs' tables counts twice.
	 */
	public int[] zonePoints(int chunkId)
	{
		Set<String> npcs = discovered.get(chunkId);
		if (npcs == null || npcs.isEmpty())
		{
			return new int[]{0, 0};
		}
		int earned = 0;
		int total = 0;
		for (String mob : npcs)
		{
			List<Drop> table = drops.get(mob);
			if (table == null)
			{
				continue;
			}
			for (Drop d : table)
			{
				int pts = pointsFor(d.tier());
				total += pts;
				if (isCollected(chunkId, mob, d.getItemName()))
				{
					earned += pts;
				}
			}
		}
		return new int[]{earned, total};
	}

	public int claimsOf(int chunkId)
	{
		Integer c = zoneClaims.get(chunkId);
		return c == null ? 0 : c;
	}

	/**
	 * Re-evaluate a zone's threshold / 100% claims after collecting something.
	 * Returns a bitmask of NEWLY earned claims (each grants one zone token).
	 */
	public int evaluateZoneClaims(int chunkId)
	{
		int[] pts = zonePoints(chunkId);
		if (pts[1] == 0)
		{
			return 0;
		}
		int claims = claimsOf(chunkId);
		int newClaims = 0;
		if ((claims & CLAIM_THRESHOLD) == 0 && pts[0] * 100 >= pts[1] * effectiveThresholdPercent())
		{
			newClaims |= CLAIM_THRESHOLD;
		}
		if ((claims & CLAIM_FULL) == 0 && pts[0] >= pts[1])
		{
			newClaims |= CLAIM_FULL;
		}
		if (newClaims != 0)
		{
			zoneClaims.put(chunkId, claims | newClaims);
			zoneTokens += Integer.bitCount(newClaims);
			save();
		}
		return newClaims;
	}

	/** Owned collection entries among a mob's drop list, for one zone. */
	public int ownedOf(int zoneId, String mobName, List<Drop> dropList)
	{
		int owned = 0;
		for (Drop d : dropList)
		{
			if (isCollected(zoneId, mobName, d.getItemName()))
			{
				owned++;
			}
		}
		return owned;
	}
}
