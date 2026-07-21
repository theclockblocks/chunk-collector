package com.chunktcg;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;

/**
 * Booster packs. The pull pool is the union of drop tables of every NPC
 * discovered in unlocked chunks, weighted by rarity tier.
 */
@Singleton
public class PackService
{
	@Value
	public static class PullResult
	{
		String itemName;
		int itemId;
		RarityTier tier;
		boolean isNew;
		/** When set, this pull was a zone card: the zone is now unlocked. */
		boolean zoneCard;

		static PullResult item(String name, int itemId, RarityTier tier, boolean isNew)
		{
			return new PullResult(name, itemId, tier, isNew, false);
		}

		static PullResult zone(String description)
		{
			return new PullResult(description, -1, RarityTier.LEGENDARY, true, true);
		}
	}

	private final Random random = new Random();

	@Inject
	private TcgStateService state;

	@Inject
	private WikiDropsService drops;

	@Inject
	private ChunkTcgConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ZoneGrid zones;

	public Map<String, Drop> pool()
	{
		return drops.unionDrops(state.allDiscoveredNpcs());
	}

	/**
	 * Opens a pack if affordable and the pool is non-empty. Zone cards for
	 * frontier zones are part of the pool — pulling one unlocks that zone on
	 * the spot. Returns null if the pack can't be opened.
	 */
	public List<PullResult> openPack()
	{
		Map<String, Drop> pool = pool();
		if (pool.isEmpty() || state.getCredits() < config.packCost())
		{
			return null;
		}

		Map<RarityTier, List<Drop>> byTier = new EnumMap<>(RarityTier.class);
		for (Drop d : pool.values())
		{
			byTier.computeIfAbsent(d.tier(), t -> new ArrayList<>()).add(d);
		}

		state.addCredits(-config.packCost());

		List<PullResult> results = new ArrayList<>();
		for (int i = 0; i < config.packSize(); i++)
		{
			List<Integer> frontier = new ArrayList<>(state.frontier());
			if (!frontier.isEmpty() && rollZoneCard(byTier))
			{
				int zoneId = frontier.get(random.nextInt(frontier.size()));
				state.unlockZone(zoneId);
				results.add(PullResult.zone("Zone " + zones.describe(zoneId) + " unlocked!"));
				continue;
			}
			RarityTier tier = rollTier(byTier);
			List<Drop> candidates = byTier.get(tier);
			Drop pulled = candidates.get(random.nextInt(candidates.size()));
			int itemId = resolveItemId(pulled.getItemName());
			boolean isNew = state.awardCard(pulled.getItemName(), itemId);
			results.add(PullResult.item(pulled.getItemName(), itemId, tier, isNew));
		}
		return results;
	}

	private boolean rollZoneCard(Map<RarityTier, List<Drop>> byTier)
	{
		int tierWeight = 0;
		for (RarityTier t : byTier.keySet())
		{
			tierWeight += t.getPullWeight();
		}
		int zoneWeight = config.zoneCardWeight();
		if (zoneWeight <= 0)
		{
			return false;
		}
		return random.nextInt(tierWeight + zoneWeight) < zoneWeight;
	}

	/** Approximate percent chance for a single pulled card to be a zone card. */
	public int zoneCardPercent()
	{
		Map<String, Drop> pool = pool();
		int tierWeight = 0;
		java.util.Set<RarityTier> present = java.util.EnumSet.noneOf(RarityTier.class);
		for (Drop d : pool.values())
		{
			present.add(d.tier());
		}
		for (RarityTier t : present)
		{
			tierWeight += t.getPullWeight();
		}
		int zoneWeight = config.zoneCardWeight();
		return tierWeight + zoneWeight == 0 ? 0 : 100 * zoneWeight / (tierWeight + zoneWeight);
	}

	/**
	 * Opens one of the free starter packs. The starter pool is the union of the
	 * starting zone mobs' drop tables plus the curated basics list — no zone
	 * cards. The first pack also discovers all starting mobs and grants the
	 * starter credits. Returns null once all starter packs are used up.
	 */
	public List<PullResult> openStarterPack()
	{
		if (state.starterComplete())
		{
			return null;
		}
		if (state.getStarterPacksOpened() == 0)
		{
			int zone = state.primaryZoneId();
			for (String mob : config.starterMobs().split(";"))
			{
				if (!mob.trim().isEmpty())
				{
					state.discoverNpc(zone, mob.trim());
					drops.ensureFetched(mob.trim(), () ->
					{
					});
				}
			}
			state.addCredits(config.starterCredits());
		}

		Map<String, Drop> pool = new java.util.HashMap<>(drops.unionDrops(state.allDiscoveredNpcs()));
		for (String basic : config.starterBasics().split(";"))
		{
			String b = basic.trim();
			if (b.isEmpty())
			{
				continue;
			}
			pool.putIfAbsent(WikiDropsService.normalize(b), new Drop(b, 1.0 / 8));
		}
		if (pool.isEmpty())
		{
			return null;
		}

		Map<RarityTier, List<Drop>> byTier = new EnumMap<>(RarityTier.class);
		for (Drop d : pool.values())
		{
			byTier.computeIfAbsent(d.tier(), t -> new ArrayList<>()).add(d);
		}

		List<PullResult> results = new ArrayList<>();
		for (int i = 0; i < config.packSize(); i++)
		{
			RarityTier tier = rollTier(byTier);
			List<Drop> candidates = byTier.get(tier);
			Drop pulled = candidates.get(random.nextInt(candidates.size()));
			int itemId = resolveItemId(pulled.getItemName());
			boolean isNew = state.awardCard(pulled.getItemName(), itemId);
			results.add(PullResult.item(pulled.getItemName(), itemId, tier, isNew));
		}
		state.incrementStarterPacks();
		return results;
	}

	private RarityTier rollTier(Map<RarityTier, List<Drop>> byTier)
	{
		int totalWeight = 0;
		for (RarityTier t : byTier.keySet())
		{
			totalWeight += t.getPullWeight();
		}
		int roll = random.nextInt(totalWeight);
		for (RarityTier t : RarityTier.values())
		{
			if (!byTier.containsKey(t))
			{
				continue;
			}
			roll -= t.getPullWeight();
			if (roll < 0)
			{
				return t;
			}
		}
		return RarityTier.COMMON;
	}

	private int resolveItemId(String itemName)
	{
		List<ItemPrice> matches = itemManager.search(itemName);
		for (ItemPrice p : matches)
		{
			if (p.getName().equalsIgnoreCase(itemName))
			{
				return p.getId();
			}
		}
		return -1;
	}
}
