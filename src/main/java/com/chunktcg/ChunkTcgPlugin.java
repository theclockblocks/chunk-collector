package com.chunktcg;

import com.chunktcg.overlay.ChunkSceneOverlay;
import com.chunktcg.overlay.ChunkWorldMapOverlay;
import com.chunktcg.overlay.ItemLockOverlay;
import com.chunktcg.panel.ChunkTcgPanel;
import java.util.ArrayList;
import java.util.List;
import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Chunk TCG",
	description = "Chunk-locked progression from Lumbridge with a card collection built from your unlocked chunks' drop tables",
	tags = {"chunk", "tcg", "cards", "challenge"}
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
	private PackService packs;

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
	private ItemLockOverlay itemLockOverlay;

	@Inject
	private ClientToolbar clientToolbar;

	private ChunkTcgPanel panel;
	private NavigationButton navButton;

	private volatile WorldPoint lastPlayerPos;
	private int lastChunk = -1;
	private int lastWarnedChunk = -1;
	private final Map<Skill, Integer> lastLevels = new EnumMap<>(Skill.class);

	@Override
	protected void startUp()
	{
		overlayManager.add(sceneOverlay);
		overlayManager.add(worldMapOverlay);
		overlayManager.add(itemLockOverlay);

		panel = new ChunkTcgPanel(state, drops, packs, config, itemManager, zones,
			() -> lastPlayerPos, this::openStarterPack);
		navButton = NavigationButton.builder()
			.tooltip("Chunk TCG")
			.icon(buildIcon())
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			state.load();
			prefetchDiscovered();
		}
		panel.refresh();
		log.debug("Chunk TCG started");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(sceneOverlay);
		overlayManager.remove(worldMapOverlay);
		overlayManager.remove(itemLockOverlay);
		clientToolbar.removeNavigation(navButton);
		state.unload();
		panel = null;
		navButton = null;
		log.debug("Chunk TCG stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			if (!state.isLoaded())
			{
				state.load();
				prefetchDiscovered();
				refreshPanel();
			}
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			state.unload();
			lastChunk = -1;
			lastWarnedChunk = -1;
			lastLevels.clear();
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
				clientThread.invoke(() -> message("Run reset! Zones, cards and credits wiped — open a new starter pack."));
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
			prefetchDiscovered();
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
		refreshPanel();
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		Skill skill = event.getSkill();
		int real = client.getRealSkillLevel(skill);
		Integer prev = lastLevels.put(skill, real);
		// prev == null is the initial stat sync after login, not a level-up
		if (prev == null || real <= prev || !state.isLoaded())
		{
			return;
		}

		WorldPoint pos = lastPlayerPos;
		WorldView wv = client.getTopLevelWorldView();
		boolean inInstance = wv != null && wv.isInstance();
		if (pos != null && !inInstance && !state.isUnlocked(zones.fromWorld(pos)))
		{
			message("Level up in a locked zone — no credits earned.");
			return;
		}

		int gained = (real - prev) * config.levelUpCredits();
		if (gained > 0)
		{
			state.addCredits(gained);
			message("Level up! " + skill.getName() + " is now " + real + " — +" + gained + " credits.");
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
				+ " — no cards awarded. (" + state.getViolations() + " total)");
			refreshPanel();
			return;
		}

		if (state.discoverNpc(chunk, name))
		{
			message("Discovered " + name + " in zone " + zones.describe(chunk)
				+ " — its drop table joins the pack pool.");
		}
		drops.ensureFetched(name, this::refreshPanel);

		// Drops don't award cards — packs are life. Kills pay credits toward packs.
		int earned = config.killCredits() + npc.getCombatLevel() / 10;
		if (earned > 0)
		{
			state.addCredits(earned);
		}
		refreshPanel();
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!state.isLoaded() || !config.enforceItemLock())
		{
			return;
		}
		MenuEntry entry = event.getMenuEntry();
		if (isLockedItemOp(entry))
		{
			// left-click falls through to the next entry (usually Walk here)
			entry.setDeprioritized(true);
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!state.isLoaded() || !config.enforceItemLock())
		{
			return;
		}
		MenuEntry[] entries = event.getMenuEntries();
		List<MenuEntry> keep = new ArrayList<>(entries.length);
		boolean changed = false;
		for (MenuEntry entry : entries)
		{
			if (isLockedItemOp(entry))
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

	private boolean isLockedItemOp(MenuEntry entry)
	{
		int itemId = entry.getItemId();
		if (itemId <= 0)
		{
			return false;
		}
		String option = entry.getOption();
		if (option == null)
		{
			return false;
		}
		boolean blocked = false;
		for (String op : config.blockedOps().split(";"))
		{
			if (option.equalsIgnoreCase(op.trim()))
			{
				blocked = true;
				break;
			}
		}
		if (!blocked)
		{
			return false;
		}
		String name = itemManager.getItemComposition(itemId).getName();
		return !state.isItemUnlocked(name);
	}

	/**
	 * Opens the one-time starter pack. Called from the panel (EDT). Returns the
	 * revealed mob name, or null if a starter was already chosen.
	 */
	private String openStarterPack()
	{
		String mob = packs.openStarterPack(pulls ->
		{
			clientThread.invoke(() ->
			{
				if (pulls.isEmpty())
				{
					message("Unlucky — your starter mob has no drop table at all! "
						+ "Anything you kill in your zone still joins your card pool.");
					return;
				}
				for (PackService.PullResult pull : pulls)
				{
					message("Starter card: " + pull.getItemName() + " [" + pull.getTier().getLabel() + "]");
				}
			});
			refreshPanel();
		});
		if (mob != null)
		{
			clientThread.invoke(() -> message("Your starter pack reveals... " + mob
				+ "! Its drop table is now your card pool. Happy hunting."));
		}
		return mob;
	}

	private void prefetchDiscovered()
	{
		for (String npc : state.allDiscoveredNpcs())
		{
			drops.ensureFetched(npc, this::refreshPanel);
		}
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
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=b0ffb0>[Chunk TCG]</col> " + text, null);
	}

	private static BufferedImage buildIcon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// two overlapping cards
		g.setColor(new Color(70, 70, 80));
		g.fillRoundRect(1, 3, 8, 12, 2, 2);
		g.setColor(new Color(255, 176, 46));
		g.fillRoundRect(6, 1, 9, 13, 2, 2);
		g.setColor(new Color(40, 40, 45));
		g.setStroke(new BasicStroke(1));
		g.drawRoundRect(6, 1, 9, 13, 2, 2);
		g.fillRect(8, 4, 5, 3);
		g.dispose();
		return img;
	}

	@Provides
	ChunkTcgConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ChunkTcgConfig.class);
	}
}
