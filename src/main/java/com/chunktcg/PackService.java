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
	 * Opens the one-time starter pack: picks a random starter mob, registers it
	 * to the starting zone, and (once its drop table arrives from the wiki)
	 * awards random item cards from it. onMobChosen runs immediately with the
	 * mob name; onItemCards runs later (on an OkHttp thread) with the pulls.
	 */
	public String openStarterPack(java.util.function.Consumer<List<PullResult>> onItemCards)
	{
		if (state.isStarterChosen())
		{
			return null;
		}
		List<String> mobs = new ArrayList<>();
		for (String mob : config.starterMobs().split(";"))
		{
			if (!mob.trim().isEmpty())
			{
				mobs.add(mob.trim());
			}
		}
		if (mobs.isEmpty())
		{
			mobs.add("Goblin");
		}
		String mob = mobs.get(random.nextInt(mobs.size()));

		state.setStarterChosen();
		state.discoverNpc(state.primaryZoneId(), mob);
		state.addCredits(config.starterCredits());

		drops.ensureFetched(mob, () ->
		{
			List<Drop> table = drops.get(mob);
			if (table == null || table.isEmpty())
			{
				// Mob with no drop table (looking at you, ducks) — report empty pulls
				onItemCards.accept(new ArrayList<>());
				return;
			}
			List<Drop> shuffled = new ArrayList<>(table);
			java.util.Collections.shuffle(shuffled, random);
			List<PullResult> pulls = new ArrayList<>();
			for (int i = 0; i < Math.min(config.starterItemCards(), shuffled.size()); i++)
			{
				Drop d = shuffled.get(i);
				int itemId = resolveItemId(d.getItemName());
				boolean isNew = state.awardCard(d.getItemName(), itemId);
				pulls.add(PullResult.item(d.getItemName(), itemId, d.tier(), isNew));
			}
			onItemCards.accept(pulls);
		});
		return mob;
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
