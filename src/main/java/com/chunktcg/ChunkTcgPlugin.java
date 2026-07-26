package com.chunktcg;

import com.chunktcg.overlay.CardToastOverlay;
import com.chunktcg.panel.ChunkTcgPanel;
import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.Actor;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Chunk Collector",
	description = "Zone-locked progression: complete each zone's mob drop tables to earn tokens and choose your next zone",
	tags = {"chunk", "zone", "collection", "challenge"}
)
public class ChunkTcgPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ZoneGrid zones;

	@Inject
	private ChunkTcgConfig config;

	@Inject
	private TcgStateService state;

	@Inject
	private WikiDropsService drops;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private CardToastOverlay toastOverlay;

	@Inject
	private ChallengeData challengeData;

	@Inject
	private ClientToolbar clientToolbar;

	private ChunkTcgPanel panel;
	private NavigationButton navButton;

	private volatile WorldPoint lastPlayerPos;
	private int lastChunk = -1;
	private int lastWarnedChunk = -1;

	/**
	 * The last overworld zone the player stood in. Instances and off-map
	 * areas (the Groats' Farm bull pen, caves, minigame arenas) inherit this
	 * zone: fights there are allowed if it's unlocked, and their mobs join
	 * its collection log.
	 */
	private int lastGroundedZone = -1;

	private static class PendingLoot
	{
		final int zone;
		final String mob;
		final String itemName;
		final int itemId;

		PendingLoot(int zone, String mob, String itemName, int itemId)
		{
			this.zone = zone;
			this.mob = mob;
			this.itemName = itemName;
			this.itemId = itemId;
		}
	}

	/** Loot received before its mob's table downloaded — verified on arrival. */
	private final List<PendingLoot> pendingLoot =
		java.util.Collections.synchronizedList(new ArrayList<>());


	@Override
	protected void startUp()
	{
		overlayManager.add(toastOverlay);

		panel = new ChunkTcgPanel(state, drops, config, itemManager, zones, challengeData,
			() -> lastPlayerPos, this::notifyZoneUnlocked, this::resetFromPanel,
			this::notifyBonusTokens, this::notifyGoalComplete);
		navButton = NavigationButton.builder()
			.tooltip("Chunk Collector")
			.icon(buildIcon())
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			state.load();
			seedAndPrefetch();
		}
		panel.refresh();
		log.debug("Chunk Collector started");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(toastOverlay);
		clientToolbar.removeNavigation(navButton);
		state.unload();
		panel = null;
		navButton = null;
		log.debug("Chunk Collector stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			if (!state.isLoaded())
			{
				state.load();
				seedAndPrefetch();
				refreshPanel();
			}
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			state.unload();
			lastChunk = -1;
			lastWarnedChunk = -1;
			lastGroundedZone = -1;
			refreshPanel();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!ChunkTcgConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("resetConfirm".equals(event.getKey()))
		{
			String typed = config.resetConfirm();
			if (typed != null && typed.trim().equalsIgnoreCase("reset"))
			{
				configManager.setConfiguration(ChunkTcgConfig.GROUP, "resetConfirm", "");
				if (state.isLoaded())
				{
					state.resetRun();
					seedAndPrefetch();
					clientThread.invoke(() -> message(
						"Run reset! Zones, collection, tokens and locked threshold wiped."));
				}
			}
			else if (typed != null && !typed.trim().isEmpty())
			{
				configManager.setConfiguration(ChunkTcgConfig.GROUP, "resetConfirm", "");
				clientThread.invoke(() -> message("Reset not performed — type exactly: reset"));
			}
		}
		refreshPanel();
	}

	/** Testing helper: ::chunktoken grants a free zone token. */
	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!"chunktoken".equalsIgnoreCase(event.getCommand()) || !state.isLoaded()
			|| !config.enableTestCommands())
		{
			return;
		}
		state.addZoneTokens(1);
		message("TEST token granted — you now have " + state.getZoneTokens()
			+ ". Spend it in the Zones tab.");
		refreshPanel();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getLocalPlayer() == null)
		{
			return;
		}
		WorldPoint pos = client.getLocalPlayer().getWorldLocation();
		lastPlayerPos = pos;

		if (!state.isLoaded() && client.getGameState() == GameState.LOGGED_IN)
		{
			state.load();
			seedAndPrefetch();
			refreshPanel();
		}
		if (!state.isLoaded())
		{
			return;
		}

		WorldView wv = client.getTopLevelWorldView();
		boolean inInstance = wv != null && wv.isInstance();
		if (!inInstance && !isOffMap(pos))
		{
			lastGroundedZone = zones.fromWorld(pos);
		}

		int chunk = zones.fromWorld(pos);
		if (chunk == lastChunk)
		{
			return;
		}
		lastChunk = chunk;

		if (!inInstance && !isOffMap(pos) && !state.isUnlocked(chunk)
			&& config.warnOnEnterLocked() && chunk != lastWarnedChunk)
		{
			lastWarnedChunk = chunk;
			message("You are in a locked zone " + zones.describe(chunk)
				+ " — kills here won't count and are violations.");
		}
		// Sweep for mobs that wandered into (or spawned in) unlocked zones
		if (wv != null)
		{
			for (NPC npc : wv.npcs())
			{
				sightNpc(npc);
			}
		}
		refreshPanel();
	}

	/** Instance space and non-surface areas (caves, arenas) are off the map. */
	private static boolean isOffMap(WorldPoint p)
	{
		return p.getX() >= 6400 || p.getY() >= 4160;
	}

	/**
	 * The zone an actor at this location belongs to: its own zone on the
	 * surface, or the inherited last-stood overworld zone in instances and
	 * off-map areas (-1 when unknown).
	 */
	private int effectiveZoneOf(WorldPoint loc, boolean inInstance)
	{
		if (inInstance || isOffMap(loc))
		{
			return lastGroundedZone;
		}
		return zones.fromWorld(loc);
	}

	/**
	 * Sighting: a combat NPC appearing in an unlocked zone registers its drop
	 * table as part of that zone's collection log.
	 */
	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		sightNpc(event.getNpc());
	}

	private void sightNpc(NPC npc)
	{
		if (!state.isLoaded() || npc == null)
		{
			return;
		}
		// Only combat NPCs have collectable drops — skip fishing spots,
		// butterflies, quest NPCs etc.
		if (npc.getCombatLevel() <= 0)
		{
			return;
		}
		String rawName = npc.getName();
		WorldPoint loc = npc.getWorldLocation();
		if (rawName == null || rawName.isEmpty() || loc == null)
		{
			return;
		}
		final String name = drops.canonicalName(rawName);
		WorldView wv = client.getTopLevelWorldView();
		boolean inInstance = wv != null && wv.isInstance();
		final int zone = effectiveZoneOf(loc, inInstance);
		if (zone == -1 || !state.isUnlocked(zone))
		{
			return;
		}
		// Record which wiki variant this id belongs to (Goblin table 1 vs 2)
		final int npcId = npc.getId();
		if (state.recordSeenVersions(name, versionsSeen(name, npcId, loc)))
		{
			refreshPanel();
		}
		if (state.discoverNpc(zone, name))
		{
			drops.ensureFetched(name, () ->
			{
				// Table data may only now be available — map the id to its variant
				state.recordSeenVersions(name, versionsSeen(name, npcId, loc));
				List<Drop> table = state.effectiveTable(name);
				if (table != null && !table.isEmpty())
				{
					clientThread.invoke(() -> message("Sighted " + name
						+ " — its drop table joins zone " + zones.describe(zone) + "'s collection log."));
				}
				refreshPanel();
			});
			refreshPanel();
		}
	}

	/**
	 * Kill counts come from deaths, not loot — mobs that drop nothing (Giant
	 * spiders...) still count. A kill is yours if you and the mob were
	 * fighting each other when it died.
	 */
	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (!state.isLoaded() || !(event.getActor() instanceof NPC))
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || client.getLocalPlayer() == null)
		{
			return;
		}
		NPC npc = (NPC) event.getActor();
		String rawName = npc.getName();
		WorldPoint loc = npc.getWorldLocation();
		if (rawName == null || rawName.isEmpty() || loc == null)
		{
			return;
		}
		String name = drops.canonicalName(rawName);
		Actor npcTarget = npc.getInteracting();
		Actor playerTarget = client.getLocalPlayer().getInteracting();
		if (npcTarget != client.getLocalPlayer() && playerTarget != npc)
		{
			return;
		}
		int chunk = effectiveZoneOf(loc, wv.isInstance());
		if (chunk == -1 || !state.isUnlocked(chunk))
		{
			return;
		}
		state.discoverNpc(chunk, name);
		state.recordSeenVersions(name, versionsSeen(name, npc.getId(), loc));
		state.addKill(name);
		refreshPanel();
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (!state.isLoaded())
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();

		NPC npc = event.getNpc();
		String rawName = npc.getName();
		if (rawName == null || rawName.isEmpty())
		{
			return;
		}
		final String name = drops.canonicalName(rawName);
		WorldPoint loc = npc.getWorldLocation();
		if (loc == null)
		{
			return;
		}
		int chunk = effectiveZoneOf(loc, wv != null && wv.isInstance());
		if (chunk == -1)
		{
			return;
		}

		if (!state.isUnlocked(chunk))
		{
			state.addViolation();
			message("Violation! " + name + " was killed in locked zone " + zones.describe(chunk)
				+ " — the loot doesn't count. (" + state.getViolations() + " total)");
			refreshPanel();
			return;
		}

		state.discoverNpc(chunk, name);
		final int npcId = npc.getId();
		state.recordSeenVersions(name, versionsSeen(name, npcId, loc));
		drops.ensureFetched(name, () ->
		{
			// Map the id to its variant now that the page data exists
			state.recordSeenVersions(name, versionsSeen(name, npcId, loc));
			processPendingLoot();
			refreshPanel();
		});

		List<Drop> mobTable = state.effectiveTable(name);
		for (ItemStack stack : event.getItems())
		{
			int canonicalId = itemManager.canonicalize(stack.getId());
			ItemComposition comp = itemManager.getItemComposition(canonicalId);
			String itemName = comp.getName();
			if (itemName == null || itemName.equalsIgnoreCase("null"))
			{
				continue;
			}
			if (mobTable == null)
			{
				// Table still downloading — queue the drop and verify on arrival
				if (pendingLoot.size() < 200)
				{
					pendingLoot.add(new PendingLoot(chunk, name, itemName, canonicalId));
				}
				continue;
			}
			// Only track items actually on the mob's table (skip rare drop
			// table rolls, event items etc.)
			if (!isOnTable(mobTable, itemName))
			{
				continue;
			}
			creditCollection(chunk, name, itemName, canonicalId);
		}

		// A new item can complete claims in any zone whose log contains it
		for (int zoneId : state.getDiscovered().keySet())
		{
			announceClaims(zoneId, state.evaluateZoneClaims(zoneId));
		}
		refreshPanel();
	}

	/**
	 * Hold-your-ground style enforcement: clicks targeting NPCs, objects or
	 * ground items inside locked zones are cancelled outright. Walking through
	 * locked zones stays allowed.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!state.isLoaded() || !config.blockZoneInteractions())
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		WorldPoint target = null;
		MenuAction action = event.getMenuAction();
		NPC npc = event.getMenuEntry().getNpc();
		if (npc != null && isNpcAction(action))
		{
			target = npc.getWorldLocation();
		}
		else if (isSceneAction(action))
		{
			target = WorldPoint.fromScene(wv, event.getParam0(), event.getParam1(), wv.getPlane());
		}
		if (target == null)
		{
			return;
		}
		int targetZone = effectiveZoneOf(target, wv.isInstance());
		if (targetZone != -1 && !state.isUnlocked(targetZone))
		{
			event.consume();
			message("Blocked — that's in locked zone " + zones.describe(targetZone)
				+ ". Earn a token and unlock it first!");
		}
	}

	/** Hide locked-zone NPC menu options entirely (Examine stays). */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!state.isLoaded() || !config.blockZoneInteractions())
		{
			return;
		}
		MenuEntry entry = event.getMenuEntry();
		if (isLockedZoneNpcOp(entry))
		{
			entry.setDeprioritized(true);
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!state.isLoaded() || !config.blockZoneInteractions())
		{
			return;
		}
		MenuEntry[] entries = event.getMenuEntries();
		List<MenuEntry> keep = new ArrayList<>(entries.length);
		boolean changed = false;
		for (MenuEntry entry : entries)
		{
			if (isLockedZoneNpcOp(entry))
			{
				changed = true;
				continue;
			}
			keep.add(entry);
		}
		if (changed)
		{
			client.getMenu().setMenuEntries(keep.toArray(new MenuEntry[0]));
		}
	}

	private boolean isLockedZoneNpcOp(MenuEntry entry)
	{
		NPC npc = entry.getNpc();
		if (npc == null)
		{
			return false;
		}
		String option = entry.getOption();
		if (option == null || option.isEmpty() || option.equalsIgnoreCase("Examine"))
		{
			return false;
		}
		WorldPoint loc = npc.getWorldLocation();
		if (loc == null)
		{
			return false;
		}
		WorldView wv = client.getTopLevelWorldView();
		int zone = effectiveZoneOf(loc, wv != null && wv.isInstance());
		return zone != -1 && !state.isUnlocked(zone);
	}

	private static boolean isNpcAction(MenuAction action)
	{
		switch (action)
		{
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case WIDGET_TARGET_ON_NPC:
				return true;
			default:
				return false;
		}
	}

	private static boolean isSceneAction(MenuAction action)
	{
		switch (action)
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
			case WIDGET_TARGET_ON_GROUND_ITEM:
				return true;
			default:
				return false;
		}
	}

	/** Warm all discovered drop tables and mirror zones to Region Locker. */
	private void seedAndPrefetch()
	{
		for (String npc : state.allDiscoveredNpcs())
		{
			drops.ensureFetched(npc, () ->
			{
				mergeKnownAliases();
				refreshPanel();
			});
		}
		mergeKnownAliases();
		syncRegionLocker();
	}

	/**
	 * Collapse discovered mobs whose wiki pages redirect to another name
	 * ("Bull" -> "Brutus") into a single log entry. Idempotent and cheap.
	 */
	private void mergeKnownAliases()
	{
		boolean changed = false;
		for (String mob : state.allDiscoveredNpcs())
		{
			String canonical = drops.canonicalName(mob);
			if (!WikiDropsService.normalize(canonical).equals(WikiDropsService.normalize(mob)))
			{
				changed |= state.mergeMobAlias(mob, canonical);
			}
		}
		if (changed)
		{
			refreshPanel();
		}
	}

	/** Reset triggered from the panel's confirmed dialog (EDT). */
	private void resetFromPanel()
	{
		if (!state.isLoaded())
		{
			return;
		}
		state.resetRun();
		seedAndPrefetch();
		clientThread.invoke(() -> message("Run reset! Zones, collection, tokens and locked threshold wiped."));
	}

	/**
	 * The wiki versions this id maps to, minus any region-gated by overrides.
	 * The gate uses the NPC's actual map region (valid underground too);
	 * instance-space coords yield a garbage region, so gated versions simply
	 * never record inside instances — conservative and correct.
	 */
	private java.util.Set<String> versionsSeen(String name, int npcId, WorldPoint loc)
	{
		int rsRegion = ((loc.getX() >> 6) << 8) | (loc.getY() >> 6);
		return drops.filterVersionsForRegion(name, drops.versionsFor(name, npcId), rsRegion);
	}

	private static boolean isOnTable(List<Drop> table, String itemName)
	{
		for (Drop d : table)
		{
			if (d.getItemName().equalsIgnoreCase(itemName))
			{
				return true;
			}
		}
		return false;
	}

	/** Record a verified table drop, announce it, and re-evaluate zone claims. */
	private void creditCollection(int zone, String mob, String itemName, int itemId)
	{
		if (state.collectItem(mob, itemName, itemId))
		{
			// Rarity from THIS mob's table — the same item can differ per mob
			RarityTier tier = drops.tierFor(itemName, java.util.Collections.singleton(mob));
			int pts = state.pointsFor(tier);
			clientThread.invoke(() -> message("Collected from " + mob + ": " + itemName
				+ " [" + tier.getLabel() + ", +" + pts + " pts]"));
			toastOverlay.push("Collected! +" + pts + " pts", itemName, itemId, tier);
		}
	}

	/** Verify queued drops whose mob tables have since downloaded. */
	private void processPendingLoot()
	{
		List<PendingLoot> ready = new ArrayList<>();
		synchronized (pendingLoot)
		{
			for (java.util.Iterator<PendingLoot> it = pendingLoot.iterator(); it.hasNext(); )
			{
				PendingLoot p = it.next();
				if (state.effectiveTable(p.mob) != null)
				{
					ready.add(p);
					it.remove();
				}
			}
		}
		if (ready.isEmpty())
		{
			return;
		}
		for (PendingLoot p : ready)
		{
			List<Drop> table = state.effectiveTable(p.mob);
			if (table != null && isOnTable(table, p.itemName))
			{
				creditCollection(p.zone, p.mob, p.itemName, p.itemId);
			}
		}
		for (int zoneId : state.getDiscovered().keySet())
		{
			announceClaims(zoneId, state.evaluateZoneClaims(zoneId));
		}
		refreshPanel();
	}

	private void announceClaims(int zoneId, int newClaims)
	{
		// May be called from OkHttp threads (pending loot) — marshal chat to client thread
		if ((newClaims & TcgStateService.CLAIM_THRESHOLD) != 0)
		{
			clientThread.invoke(() -> message("★ Zone " + zones.describe(zoneId)
				+ " hit its point threshold — +1 zone token! Choose your next zone in the panel."));
			toastOverlay.push("Zone token earned!", "Zone " + zones.describe(zoneId), -1, null);
		}
		if ((newClaims & TcgStateService.CLAIM_FULL) != 0)
		{
			clientThread.invoke(() -> message("★★ Zone " + zones.describe(zoneId)
				+ " is 100% COMPLETE — bonus zone token!"));
			toastOverlay.push("Zone 100% complete!", "Zone " + zones.describe(zoneId), -1, null);
		}
	}

	private void notifyBonusTokens(int n)
	{
		clientThread.invoke(() -> message("★ Community challenges rewarded " + n
			+ " bonus zone token" + (n == 1 ? "" : "s") + "! (" + state.getZoneTokens() + " total)"));
		toastOverlay.push("Bonus zone token!", "Challenges completed", -1, null);
	}

	private void notifyGoalComplete()
	{
		clientThread.invoke(() -> message("★★★ RUN GOAL COMPLETE: " + state.effectiveGoal()
			+ " ★★★ What a run. Set a new goal with a fresh run, or keep expanding!"));
		toastOverlay.push("RUN GOAL COMPLETE!", state.effectiveGoal(), -1, null);
	}

	private void notifyZoneUnlocked(int zoneId)
	{
		clientThread.invoke(() -> message("Zone " + zones.describe(zoneId)
			+ " unlocked! Its mobs join your collection log as you sight them."));
		syncRegionLocker();
	}

	/**
	 * Mirror our unlocked zones into the Region Locker hub plugin's config so
	 * its (much prettier) shading renders them. 64x64 zones map 1:1 to RS map
	 * regions, which is exactly what Region Locker locks.
	 */
	private void syncRegionLocker()
	{
		if (!config.syncRegionLocker() || config.zoneSize() != ZoneSize.REGION_64 || !state.isLoaded())
		{
			return;
		}
		List<String> unlockedIds = new ArrayList<>();
		for (int zoneId : state.getUnlockedChunks())
		{
			unlockedIds.add(String.valueOf(zones.rsRegionId(zoneId)));
		}
		unlockedIds.sort(Comparator.naturalOrder());
		configManager.setConfiguration("regionlocker", "unlockedRegions", String.join(",", unlockedIds));

		// Frontier zones become Region Locker's "unlockable" regions, so its
		// terrain-following borders and map colours show where you can expand
		List<String> frontierIds = new ArrayList<>();
		for (int zoneId : state.frontier())
		{
			frontierIds.add(String.valueOf(zones.rsRegionId(zoneId)));
		}
		frontierIds.sort(Comparator.naturalOrder());
		configManager.setConfiguration("regionlocker", "unlockableRegions", String.join(",", frontierIds));
		log.debug("Synced {} unlocked + {} frontier regions to Region Locker",
			unlockedIds.size(), frontierIds.size());
	}

	private void refreshPanel()
	{
		ChunkTcgPanel p = panel;
		if (p != null)
		{
			p.refresh();
		}
	}

	private void message(String text)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=b0ffb0>[Chunk Collector]</col> " + text, null);
	}

	private static BufferedImage buildIcon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// map chunk with a checkmark
		g.setColor(new Color(70, 70, 80));
		g.fillRoundRect(1, 1, 14, 14, 3, 3);
		g.setColor(new Color(0, 255, 120));
		g.setStroke(new BasicStroke(2));
		g.drawLine(4, 8, 7, 11);
		g.drawLine(7, 11, 12, 4);
		g.dispose();
		return img;
	}

	@Provides
	ChunkTcgConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ChunkTcgConfig.class);
	}
}
