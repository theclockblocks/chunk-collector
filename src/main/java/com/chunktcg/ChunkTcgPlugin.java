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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.Actor;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.StatChanged;
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
	private NodeTables nodeTables;

	@Inject
	private ClientToolbar clientToolbar;

	private ChunkTcgPanel panel;
	private NavigationButton navButton;

	private volatile WorldPoint lastPlayerPos;
	private int lastChunk = -1;
	private int lastWarnedChunk = -1;

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

	// Skilling-node bookkeeping
	private final Map<Integer, String> nodeNameById = new HashMap<>();
	private final Map<Skill, Integer> lastXp = new EnumMap<>(Skill.class);
	private final Map<Integer, Integer> invSnapshot = new HashMap<>();
	private boolean invSnapshotValid;
	private Skill recentGatherSkill;
	private int recentGatherTick = -1000;
	/** The node the player last clicked (Tree vs Dead tree share tables). */
	private String lastGatherTarget;

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
			lastXp.clear();
			invSnapshot.clear();
			invSnapshotValid = false;
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

		int chunk = zones.fromWorld(pos);
		if (chunk == lastChunk)
		{
			return;
		}
		lastChunk = chunk;

		WorldView wv = client.getTopLevelWorldView();
		boolean inInstance = wv != null && wv.isInstance();
		if (!inInstance && !state.isUnlocked(chunk) && config.warnOnEnterLocked() && chunk != lastWarnedChunk)
		{
			lastWarnedChunk = chunk;
			message("You are in a locked zone " + zones.describe(chunk)
				+ " — kills here won't count and are violations.");
		}
		// Sweep for mobs that wandered into (or spawned in) unlocked zones
		if (wv != null && !inInstance)
		{
			for (NPC npc : wv.npcs())
			{
				sightNpc(npc);
			}
		}
		refreshPanel();
	}

	/**
	 * Sighting: a combat NPC appearing in an unlocked zone registers its drop
	 * table as part of that zone's collection log.
	 */
	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || wv.isInstance())
		{
			return;
		}
		sightNpc(event.getNpc());
	}

	private void sightNpc(NPC npc)
	{
		if (!state.isLoaded() || npc == null)
		{
			return;
		}
		// Only combat NPCs have collectable drops — except skilling nodes
		// (fishing spots are combat-0 NPCs with curated tables)
		if (npc.getCombatLevel() <= 0 && !nodeTables.isNode(npc.getName()))
		{
			return;
		}
		String name = npc.getName();
		WorldPoint loc = npc.getWorldLocation();
		if (name == null || name.isEmpty() || loc == null)
		{
			return;
		}
		int zone = zones.fromWorld(loc);
		if (!state.isUnlocked(zone))
		{
			return;
		}
		if (state.discoverNpc(zone, name))
		{
			drops.ensureFetched(name, () ->
			{
				List<Drop> table = drops.get(name);
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

	/** Sight skilling nodes (trees, rocks) like mobs when they appear in unlocked zones. */
	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (!state.isLoaded())
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || wv.isInstance())
		{
			return;
		}
		int id = event.getGameObject().getId();
		String name = nodeNameById.computeIfAbsent(id, i ->
		{
			String n = client.getObjectDefinition(i).getName();
			return nodeTables.isNode(n) ? n : "";
		});
		if (name.isEmpty())
		{
			return;
		}
		WorldPoint loc = event.getTile().getWorldLocation();
		int zone = zones.fromWorld(loc);
		if (!state.isUnlocked(zone))
		{
			return;
		}
		if (state.discoverNpc(zone, name))
		{
			message("Sighted " + name + " — gatherable in zone " + zones.describe(zone) + ".");
			refreshPanel();
		}
	}

	/** Track xp gains so inventory additions can be attributed to gathering. */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		int xp = event.getXp();
		Integer prev = lastXp.put(skill, xp);
		if (prev != null && xp > prev && nodeTables.isGatheringSkill(skill))
		{
			recentGatherSkill = skill;
			recentGatherTick = client.getTickCount();
		}
	}

	/** Inventory gains right after gathering xp tick the matching node's log. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		ItemContainer inv = client.getItemContainer(net.runelite.api.gameval.InventoryID.INV);
		if (inv == null || event.getItemContainer() != inv)
		{
			return;
		}
		Map<Integer, Integer> current = new HashMap<>();
		for (net.runelite.api.Item item : inv.getItems())
		{
			if (item.getId() >= 0)
			{
				current.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		Map<Integer, Integer> previous = new HashMap<>(invSnapshot);
		boolean wasValid = invSnapshotValid;
		invSnapshot.clear();
		invSnapshot.putAll(current);
		invSnapshotValid = true;
		if (!wasValid || !state.isLoaded())
		{
			return;
		}
		if (client.getTickCount() - recentGatherTick > 2 || recentGatherSkill == null)
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || wv.isInstance())
		{
			return;
		}
		WorldPoint pos = lastPlayerPos;
		if (pos == null)
		{
			return;
		}
		int zone = zones.fromWorld(pos);
		if (!state.isUnlocked(zone))
		{
			return;
		}
		java.util.Set<String> zoneMobs = state.getDiscovered().get(zone);
		if (zoneMobs == null)
		{
			return;
		}

		for (Map.Entry<Integer, Integer> e : current.entrySet())
		{
			int gained = e.getValue() - previous.getOrDefault(e.getKey(), 0);
			if (gained <= 0)
			{
				continue;
			}
			String itemName = itemManager.getItemComposition(e.getKey()).getName();
			String credited = null;
			// Prefer the node the player actually clicked
			if (lastGatherTarget != null && zoneMobs.contains(lastGatherTarget)
				&& nodeTables.skillOf(lastGatherTarget) == recentGatherSkill
				&& nodeHasItem(lastGatherTarget, itemName))
			{
				credited = lastGatherTarget;
			}
			if (credited == null)
			{
				for (String node : new java.util.TreeSet<>(zoneMobs))
				{
					if (nodeTables.isNode(node) && nodeTables.skillOf(node) == recentGatherSkill
						&& nodeHasItem(node, itemName))
					{
						credited = node;
						break;
					}
				}
			}
			if (credited == null)
			{
				continue;
			}
			state.addKill(zone, credited);
			if (state.collectItem(zone, credited, itemName, e.getKey()))
			{
				RarityTier tier = drops.tierFor(itemName, java.util.Collections.singleton(credited));
				int pts = state.pointsFor(tier);
				message("Gathered from " + credited + ": " + itemName
					+ " [" + tier.getLabel() + ", +" + pts + " pts]");
				toastOverlay.push("Collected! +" + pts + " pts", itemName, e.getKey(), tier);
				for (int zoneId : state.getDiscovered().keySet())
				{
					announceClaims(zoneId, state.evaluateZoneClaims(zoneId));
				}
			}
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
		if (wv == null || wv.isInstance() || client.getLocalPlayer() == null)
		{
			return;
		}
		NPC npc = (NPC) event.getActor();
		String name = npc.getName();
		WorldPoint loc = npc.getWorldLocation();
		if (name == null || name.isEmpty() || loc == null)
		{
			return;
		}
		Actor npcTarget = npc.getInteracting();
		Actor playerTarget = client.getLocalPlayer().getInteracting();
		if (npcTarget != client.getLocalPlayer() && playerTarget != npc)
		{
			return;
		}
		int chunk = zones.fromWorld(loc);
		if (!state.isUnlocked(chunk))
		{
			return;
		}
		state.discoverNpc(chunk, name);
		state.addKill(chunk, name);
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
		if (wv != null && wv.isInstance())
		{
			return;
		}

		NPC npc = event.getNpc();
		String name = npc.getName();
		if (name == null || name.isEmpty())
		{
			return;
		}
		WorldPoint loc = npc.getWorldLocation();
		if (loc == null)
		{
			return;
		}
		int chunk = zones.fromWorld(loc);

		if (!state.isUnlocked(chunk))
		{
			state.addViolation();
			message("Violation! " + name + " was killed in locked zone " + zones.describe(chunk)
				+ " — the loot doesn't count. (" + state.getViolations() + " total)");
			refreshPanel();
			return;
		}

		state.discoverNpc(chunk, name);
		drops.ensureFetched(name, () ->
		{
			processPendingLoot();
			refreshPanel();
		});

		List<Drop> mobTable = drops.get(name);
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
		if (wv == null || wv.isInstance())
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
		if (target != null && !state.isUnlocked(zones.fromWorld(target)))
		{
			event.consume();
			message("Blocked — that's in locked zone " + zones.describe(zones.fromWorld(target))
				+ ". Earn a token and unlock it first!");
			return;
		}

		// Remember which skilling node was clicked, so gathers credit the
		// right one when multiple nodes share a table (Tree vs Dead tree)
		if (npc != null && nodeTables.isNode(npc.getName()))
		{
			lastGatherTarget = npc.getName();
		}
		else if (isObjectAction(action))
		{
			int objectId = event.getMenuEntry().getIdentifier();
			String nodeName = nodeNameById.computeIfAbsent(objectId, i ->
			{
				String n = client.getObjectDefinition(i).getName();
				return nodeTables.isNode(n) ? n : "";
			});
			if (!nodeName.isEmpty())
			{
				lastGatherTarget = nodeName;
			}
		}
	}

	private static boolean isObjectAction(MenuAction action)
	{
		switch (action)
		{
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case WIDGET_TARGET_ON_GAME_OBJECT:
				return true;
			default:
				return false;
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
		return loc != null && !state.isUnlocked(zones.fromWorld(loc));
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
			drops.ensureFetched(npc, this::refreshPanel);
		}
		syncRegionLocker();
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

	private boolean nodeHasItem(String node, String itemName)
	{
		List<Drop> table = nodeTables.tableOf(node);
		return table != null && isOnTable(table, itemName);
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
		if (state.collectItem(zone, mob, itemName, itemId))
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
				if (drops.get(p.mob) != null)
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
			List<Drop> table = drops.get(p.mob);
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
