package com.chunktcg;

import com.chunktcg.overlay.CardToastOverlay;
import com.chunktcg.overlay.ChunkSceneOverlay;
import com.chunktcg.overlay.ChunkWorldMapOverlay;
import com.chunktcg.panel.ChunkTcgPanel;
import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
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
	private ChunkSceneOverlay sceneOverlay;

	@Inject
	private ChunkWorldMapOverlay worldMapOverlay;

	@Inject
	private CardToastOverlay toastOverlay;

	@Inject
	private ClientToolbar clientToolbar;

	private ChunkTcgPanel panel;
	private NavigationButton navButton;

	private volatile WorldPoint lastPlayerPos;
	private int lastChunk = -1;
	private int lastWarnedChunk = -1;

	@Override
	protected void startUp()
	{
		overlayManager.add(sceneOverlay);
		overlayManager.add(worldMapOverlay);
		overlayManager.add(toastOverlay);

		panel = new ChunkTcgPanel(state, drops, config, itemManager, zones,
			() -> lastPlayerPos, this::notifyZoneUnlocked);
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
		overlayManager.remove(sceneOverlay);
		overlayManager.remove(worldMapOverlay);
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
		if ("resetRun".equals(event.getKey()) && config.resetRun())
		{
			configManager.setConfiguration(ChunkTcgConfig.GROUP, "resetRun", false);
			if (state.isLoaded())
			{
				state.resetRun();
				seedAndPrefetch();
				clientThread.invoke(() -> message("Run reset! Zones, collection and tokens wiped."));
			}
		}
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
		// Only combat NPCs have collectable drops — skip fishing spots,
		// butterflies, quest NPCs etc.
		if (npc.getCombatLevel() <= 0)
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
		drops.ensureFetched(name, this::refreshPanel);

		for (ItemStack stack : event.getItems())
		{
			int canonicalId = itemManager.canonicalize(stack.getId());
			ItemComposition comp = itemManager.getItemComposition(canonicalId);
			String itemName = comp.getName();
			if (itemName == null || itemName.equalsIgnoreCase("null"))
			{
				continue;
			}
			if (state.collectItem(itemName, canonicalId))
			{
				RarityTier tier = drops.tierFor(itemName, state.allDiscoveredNpcs());
				int pts = state.pointsFor(tier);
				message("Collected: " + itemName + " [" + tier.getLabel() + ", +" + pts + " pts]");
				toastOverlay.push("Collected! +" + pts + " pts", itemName, canonicalId, tier);
			}
		}

		// A new item can complete claims in any zone whose log contains it
		for (int zoneId : state.getDiscovered().keySet())
		{
			int newClaims = state.evaluateZoneClaims(zoneId);
			if ((newClaims & TcgStateService.CLAIM_THRESHOLD) != 0)
			{
				message("★ Zone " + zones.describe(zoneId) + " hit its point threshold — +1 zone token! "
					+ "Choose your next zone in the panel.");
				toastOverlay.push("Zone token earned!", "Zone " + zones.describe(zoneId), -1, null);
			}
			if ((newClaims & TcgStateService.CLAIM_FULL) != 0)
			{
				message("★★ Zone " + zones.describe(zoneId) + " is 100% COMPLETE — bonus zone token!");
				toastOverlay.push("Zone 100% complete!", "Zone " + zones.describe(zoneId), -1, null);
			}
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

	/** Seed the starting zone's mobs and warm all discovered drop tables. */
	private void seedAndPrefetch()
	{
		int primary = state.primaryZoneId();
		for (String mob : config.starterMobs().split(";"))
		{
			if (!mob.trim().isEmpty())
			{
				state.discoverNpc(primary, mob.trim());
			}
		}
		for (String npc : state.allDiscoveredNpcs())
		{
			drops.ensureFetched(npc, this::refreshPanel);
		}
	}

	private void notifyZoneUnlocked(int zoneId)
	{
		clientThread.invoke(() -> message("Zone " + zones.describe(zoneId)
			+ " unlocked! Its mobs join your collection log as you sight them."));
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
