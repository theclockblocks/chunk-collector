package com.chunktcg.panel;

import com.chunktcg.CardEntry;
import com.chunktcg.ChallengeData;
import com.chunktcg.ChunkTcgConfig;
import com.chunktcg.Drop;
import com.chunktcg.RarityTier;
import com.chunktcg.TcgStateService;
import com.chunktcg.WikiDropsService;
import com.chunktcg.ZoneGrid;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.swing.JCheckBox;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.item.ItemPrice;

public class ChunkTcgPanel extends PluginPanel
{
	private final TcgStateService state;
	private final WikiDropsService drops;
	private final ChunkTcgConfig config;
	private final ItemManager itemManager;
	private final ZoneGrid zones;
	private final ChallengeData challenges;
	private final Supplier<WorldPoint> playerPos;
	private final Consumer<Integer> unlockNotifier;
	private final Runnable resetAction;
	private final IntConsumer bonusTokenNotifier;
	private final Runnable goalCompleteNotifier;

	private final JPanel collectionContent = new JPanel();
	private final JPanel tasksContent = new JPanel();
	private final JPanel zonesContent = new JPanel();
	private final JPanel display = new JPanel();

	/** Per-zone expand/collapse toggles; absent = default (current zone open). */
	private final Map<Integer, Boolean> zoneExpanded = new HashMap<>();
	private final Map<Integer, Boolean> taskZoneExpanded = new HashMap<>();

	/** item name -> resolved item id for ghost icons (-1 = unresolvable). */
	private final Map<String, Integer> itemIdCache = new HashMap<>();

	public ChunkTcgPanel(TcgStateService state, WikiDropsService drops, ChunkTcgConfig config,
		ItemManager itemManager, ZoneGrid zones, ChallengeData challenges,
		Supplier<WorldPoint> playerPos, Consumer<Integer> unlockNotifier, Runnable resetAction,
		IntConsumer bonusTokenNotifier, Runnable goalCompleteNotifier)
	{
		super(false);
		this.state = state;
		this.drops = drops;
		this.config = config;
		this.itemManager = itemManager;
		this.zones = zones;
		this.challenges = challenges;
		this.playerPos = playerPos;
		this.unlockNotifier = unlockNotifier;
		this.resetAction = resetAction;
		this.bonusTokenNotifier = bonusTokenNotifier;
		this.goalCompleteNotifier = goalCompleteNotifier;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		collectionContent.setLayout(new BoxLayout(collectionContent, BoxLayout.Y_AXIS));
		tasksContent.setLayout(new BoxLayout(tasksContent, BoxLayout.Y_AXIS));
		zonesContent.setLayout(new BoxLayout(zonesContent, BoxLayout.Y_AXIS));

		MaterialTabGroup tabGroup = new MaterialTabGroup(display);
		MaterialTab collectionTab = new MaterialTab("Log", tabGroup, wrapScroll(collectionContent));
		MaterialTab tasksTab = new MaterialTab("Tasks", tabGroup, wrapScroll(tasksContent));
		MaterialTab zonesTab = new MaterialTab("Zones", tabGroup, wrapScroll(zonesContent));
		tabGroup.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
		tabGroup.addTab(collectionTab);
		tabGroup.addTab(tasksTab);
		tabGroup.addTab(zonesTab);
		tabGroup.select(collectionTab);

		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		refresh();
	}

	/**
	 * Panel that always matches the scroll viewport's width, so content wraps
	 * and shrinks instead of being clipped off the right edge of the sidebar.
	 */
	private static class ViewportWidthPanel extends JPanel implements Scrollable
	{
		ViewportWidthPanel()
		{
			super(new BorderLayout());
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 64;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private JScrollPane wrapScroll(JPanel content)
	{
		ViewportWidthPanel holder = new ViewportWidthPanel();
		holder.setBackground(ColorScheme.DARK_GRAY_COLOR);
		holder.add(content, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(holder);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		return scroll;
	}

	/** Rebuild all tabs. Safe to call from any thread. */
	public void refresh()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refresh);
			return;
		}
		rebuildCollection();
		rebuildTasks();
		rebuildZones();
		revalidate();
		repaint();
	}

	// ---- Tasks (community challenges) ----

	private void rebuildTasks()
	{
		tasksContent.removeAll();
		if (!state.isLoaded())
		{
			tasksContent.add(infoLabel("Log in to see zone challenges."));
			return;
		}

		int per = config.challengesPerToken();
		tasksContent.add(header("Community challenges"));
		tasksContent.add(infoLabel("Crowd-sourced by the chunk-locked community. Tick them off as you complete them"
			+ (per > 0 ? " — every " + per + " earns a bonus zone token." : ".")
			+ " Done: " + state.getCompletedChallenges().size()));
		tasksContent.add(Box.createVerticalStrut(8));

		WorldPoint pos = playerPos.get();
		int currentZone = pos != null ? zones.fromWorld(pos) : -1;

		List<Integer> zoneIds = new ArrayList<>(state.getUnlockedChunks());
		zoneIds.sort(Comparator.naturalOrder());
		if (zoneIds.remove(Integer.valueOf(currentZone)))
		{
			zoneIds.add(0, currentZone);
		}

		boolean any = false;
		for (final int zoneId : zoneIds)
		{
			List<ChallengeData.Challenge> zoneChallenges = challenges.forRegion(zones.rsRegionId(zoneId));
			if (zoneChallenges.isEmpty())
			{
				continue;
			}
			any = true;
			int done = 0;
			for (ChallengeData.Challenge c : zoneChallenges)
			{
				if (state.isChallengeDone(zoneId, c.getN()))
				{
					done++;
				}
			}
			final boolean expanded = taskZoneExpanded.getOrDefault(zoneId, zoneId == currentZone);

			JPanel zoneHeader = new JPanel(new BorderLayout(4, 0));
			zoneHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
			zoneHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
			zoneHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
			zoneHeader.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			zoneHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			JLabel arrow = new JLabel(expanded ? "▼" : "▶");
			arrow.setForeground(new Color(255, 200, 0));
			zoneHeader.add(arrow, BorderLayout.WEST);
			JLabel title = new JLabel(zones.describe(zoneId) + "  " + done + "/" + zoneChallenges.size());
			title.setForeground(Color.WHITE);
			title.setFont(title.getFont().deriveFont(Font.BOLD));
			zoneHeader.add(title, BorderLayout.CENTER);
			zoneHeader.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					taskZoneExpanded.put(zoneId, !expanded);
					refresh();
				}
			});
			tasksContent.add(zoneHeader);
			tasksContent.add(Box.createVerticalStrut(3));

			if (expanded)
			{
				for (final ChallengeData.Challenge c : zoneChallenges)
				{
					boolean isDone = state.isChallengeDone(zoneId, c.getN());
					JCheckBox box = new JCheckBox("<html><body style='width:140px'>"
						+ c.display() + "</body></html>", isDone);
					box.setAlignmentX(Component.LEFT_ALIGNMENT);
					box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					box.setForeground(isDone ? new Color(94, 204, 94) : ColorScheme.LIGHT_GRAY_COLOR);
					if (c.getC() != null)
					{
						box.setToolTipText(c.getC());
					}
					box.addActionListener(e ->
					{
						int granted = state.toggleChallenge(zoneId, c.getN());
						if (granted > 0)
						{
							bonusTokenNotifier.accept(granted);
						}
						refresh();
					});
					tasksContent.add(box);
				}
			}
			tasksContent.add(Box.createVerticalStrut(6));
		}
		if (!any)
		{
			tasksContent.add(infoLabel("No community challenges recorded for your unlocked zones yet."));
		}
	}

	// ---- Collection ----

	private void rebuildCollection()
	{
		collectionContent.removeAll();
		if (!state.isLoaded())
		{
			collectionContent.add(infoLabel("Log in to load your collection log."));
			return;
		}

		// Current-zone summary at the top
		WorldPoint pos = playerPos.get();
		int currentZone = pos != null ? zones.fromWorld(pos) : -1;
		if (pos != null)
		{
			Set<String> zoneMobs = state.getDiscovered().get(currentZone);
			int sighted = zoneMobs == null ? 0 : zoneMobs.size();
			if (state.isUnlocked(currentZone))
			{
				collectionContent.add(header("Currently in: " + zones.describe(currentZone)));
				collectionContent.add(infoLabel(sighted + " different mob" + (sighted == 1 ? "" : "s")
					+ " sighted in this zone"));
			}
			else
			{
				JLabel locked = header("Currently in: " + zones.describe(currentZone) + " [LOCKED]");
				locked.setForeground(new Color(255, 120, 120));
				collectionContent.add(locked);
			}
			collectionContent.add(Box.createVerticalStrut(8));
		}

		if (state.getDiscovered().isEmpty())
		{
			collectionContent.add(infoLabel("Walk around your zone — sighted mobs' drop tables become your collection log."));
			return;
		}

		// One collapsible section per zone; the current zone is open by default
		List<Integer> zoneIds = new ArrayList<>(state.getDiscovered().keySet());
		zoneIds.sort(Comparator.naturalOrder());
		if (zoneIds.remove(Integer.valueOf(currentZone)))
		{
			zoneIds.add(0, currentZone);
		}

		for (final int zoneId : zoneIds)
		{
			Set<String> zoneMobs = state.getDiscovered().get(zoneId);
			if (zoneMobs == null || zoneMobs.isEmpty())
			{
				continue;
			}
			final boolean expanded = zoneExpanded.getOrDefault(zoneId, zoneId == currentZone);
			int[] pts = state.zonePoints(zoneId);

			JPanel zoneHeader = new JPanel(new BorderLayout(4, 0));
			zoneHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
			zoneHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
			zoneHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
			zoneHeader.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			zoneHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			JLabel arrow = new JLabel(expanded ? "▼" : "▶");
			arrow.setForeground(new Color(255, 200, 0));
			zoneHeader.add(arrow, BorderLayout.WEST);

			JLabel zoneTitle = new JLabel(zones.describe(zoneId)
				+ "  " + pts[0] + "/" + pts[1] + " pts");
			zoneTitle.setForeground(Color.WHITE);
			zoneTitle.setFont(zoneTitle.getFont().deriveFont(Font.BOLD));
			zoneHeader.add(zoneTitle, BorderLayout.CENTER);

			zoneHeader.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					zoneExpanded.put(zoneId, !expanded);
					refresh();
				}
			});
			collectionContent.add(zoneHeader);
			collectionContent.add(Box.createVerticalStrut(3));

			if (expanded)
			{
				for (String npc : new TreeSet<>(zoneMobs))
				{
					addMobSection(zoneId, npc);
				}
			}
			collectionContent.add(Box.createVerticalStrut(6));
		}
	}

	private void addMobSection(int zoneId, String npc)
	{
		List<Drop> cached = drops.get(npc);
		if (cached != null && cached.isEmpty())
		{
			// Known to drop nothing — nothing to collect, no log entry
			return;
		}
		List<Drop> table = cached == null ? null : new ArrayList<>(cached);

		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		section.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		if (table == null)
		{
			section.add(header(npc + " — fetching drop table..."));
			collectionContent.add(section);
			collectionContent.add(Box.createVerticalStrut(6));
			return;
		}

		table.sort(Comparator.comparingDouble(Drop::getRate).reversed());
		int owned = state.ownedOf(zoneId, npc, table);
		int earnedPts = 0;
		int totalPts = 0;
		for (Drop d : table)
		{
			int pts = state.pointsFor(d.tier());
			totalPts += pts;
			if (state.isCollected(zoneId, npc, d.getItemName()))
			{
				earnedPts += pts;
			}
		}
		int kc = state.killCount(zoneId, npc);
		section.add(header(npc + " (" + kc + " kc)  " + owned + "/" + table.size()
			+ "  ·  " + earnedPts + "/" + totalPts + " pts"));

		JPanel grid = new JPanel(new GridLayout(0, 4, 2, 2));
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		grid.setMaximumSize(new Dimension(180, Integer.MAX_VALUE));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		for (Drop d : table)
		{
			grid.add(itemCell(d, state.getCollectedEntry(zoneId, npc, d.getItemName())));
		}
		section.add(grid);
		collectionContent.add(section);
		collectionContent.add(Box.createVerticalStrut(6));
	}

	private JPanel itemCell(Drop drop, CardEntry owned)
	{
		RarityTier tier = drop.tier();
		JPanel cell = new JPanel(new BorderLayout());
		cell.setPreferredSize(new Dimension(40, 44));
		cell.setBackground(new Color(30, 30, 36));
		cell.setBorder(BorderFactory.createLineBorder(
			owned != null ? tier.getColor() : ColorScheme.MEDIUM_GRAY_COLOR.darker(), 2, true));

		JLabel icon = new JLabel("?", SwingConstants.CENTER);
		icon.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		if (owned != null && owned.getItemId() >= 0)
		{
			icon.setText("");
			AsyncBufferedImage img = itemManager.getImage(owned.getItemId());
			img.addTo(icon);
		}
		else if (owned != null)
		{
			icon.setText("✔");
			icon.setForeground(tier.getColor());
		}
		else
		{
			// Uncollected: show a grayed-out ghost of the item when resolvable
			int ghostId = resolveItemId(drop.getItemName());
			if (ghostId >= 0)
			{
				icon.setText("");
				AsyncBufferedImage img = itemManager.getImage(ghostId);
				Runnable apply = () -> SwingUtilities.invokeLater(() ->
				{
					BufferedImage ghost = ImageUtil.alphaOffset(ImageUtil.grayscaleImage(img), 0.4f);
					icon.setIcon(new ImageIcon(ghost));
					icon.repaint();
				});
				img.onLoaded(apply);
				apply.run();
			}
		}
		cell.add(icon, BorderLayout.CENTER);

		cell.setToolTipText("<html>" + drop.getItemName()
			+ "<br>Drop rate: " + formatRate(drop.getRate())
			+ "<br>" + tier.getLabel() + " · " + state.pointsFor(tier) + " pts"
			+ (owned != null ? "<br>Collected x" + owned.getCount() : "<br>Not collected") + "</html>");
		return cell;
	}

	/** Resolve an item name to an id for ghost icons (tradeables only). */
	private int resolveItemId(String itemName)
	{
		return itemIdCache.computeIfAbsent(WikiDropsService.normalize(itemName), k ->
		{
			for (ItemPrice p : itemManager.search(itemName))
			{
				if (p.getName().equalsIgnoreCase(itemName))
				{
					return p.getId();
				}
			}
			return -1;
		});
	}

	private static String formatRate(double rate)
	{
		if (rate >= 1.0)
		{
			return "Always";
		}
		if (rate <= 0)
		{
			return "Unknown";
		}
		return "~1/" + Math.round(1.0 / rate);
	}

	// ---- Zones ----

	private void rebuildZones()
	{
		zonesContent.removeAll();
		if (!state.isLoaded())
		{
			zonesContent.add(infoLabel("Log in to see zone progress."));
			return;
		}

		// Run goal banner
		String goal = state.effectiveGoal();
		if (!goal.isEmpty())
		{
			if (state.isGoalComplete())
			{
				JLabel done = header("★ GOAL COMPLETE: " + goal + " ★");
				done.setForeground(new Color(255, 215, 0));
				zonesContent.add(done);
			}
			else
			{
				zonesContent.add(header("Goal: " + goal));
				JButton complete = new JButton("Mark goal complete");
				complete.setAlignmentX(Component.LEFT_ALIGNMENT);
				complete.addActionListener(e ->
				{
					int choice = JOptionPane.showConfirmDialog(this,
						"Did you really complete your run goal?\n\n" + goal,
						"Goal complete", JOptionPane.YES_NO_OPTION);
					if (choice == JOptionPane.YES_OPTION)
					{
						state.markGoalComplete();
						goalCompleteNotifier.run();
						refresh();
					}
				});
				zonesContent.add(complete);
			}
			zonesContent.add(Box.createVerticalStrut(8));
		}
		else if (!state.isThresholdLocked())
		{
			zonesContent.add(infoLabel("Tip: set a Run goal in the plugin settings before your first drop — it locks in as your win condition."));
			zonesContent.add(Box.createVerticalStrut(8));
		}

		zonesContent.add(header("Zone tokens: " + state.getZoneTokens()));
		zonesContent.add(infoLabel("Reach " + state.effectiveThresholdPercent()
			+ "% of a zone's points for a token"
			+ (state.isThresholdLocked() ? " (locked for this run)" : " (locks at your first drop)")
			+ ". 100% the zone for a bonus token. Spend tokens on any frontier zone below."));
		if (state.getViolations() > 0)
		{
			JLabel v = infoLabel("Violations: " + state.getViolations());
			v.setForeground(new Color(255, 90, 90));
			zonesContent.add(v);
		}
		zonesContent.add(Box.createVerticalStrut(8));

		zonesContent.add(header("Your zones"));
		List<Integer> unlockedSorted = new ArrayList<>(state.getUnlockedChunks());
		unlockedSorted.sort(Comparator.naturalOrder());
		for (int id : unlockedSorted)
		{
			int[] pts = state.zonePoints(id);
			int claims = state.claimsOf(id);
			String status;
			Color color;
			if (pts[1] == 0)
			{
				status = "no mobs sighted yet";
				color = ColorScheme.MEDIUM_GRAY_COLOR;
			}
			else
			{
				int pct = pts[0] * 100 / pts[1];
				status = pts[0] + "/" + pts[1] + " pts (" + pct + "%)"
					+ ((claims & TcgStateService.CLAIM_THRESHOLD) != 0 ? " ✔" : "")
					+ ((claims & TcgStateService.CLAIM_FULL) != 0 ? " ★" : "");
				color = (claims & TcgStateService.CLAIM_FULL) != 0
					? new Color(255, 215, 0)
					: (claims & TcgStateService.CLAIM_THRESHOLD) != 0
					? new Color(94, 204, 94) : Color.WHITE;
			}
			JLabel row = new JLabel("Zone " + zones.describe(id) + "  " + status);
			row.setForeground(color);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			zonesContent.add(row);
			zonesContent.add(Box.createVerticalStrut(2));
		}
		zonesContent.add(Box.createVerticalStrut(8));

		List<Integer> frontier = new ArrayList<>(state.frontier());
		zonesContent.add(header("Frontier zones: " + frontier.size()));
		WorldPoint pos = playerPos.get();
		if (pos != null)
		{
			frontier.sort(Comparator.comparingInt(id -> chunkDistance(id, pos)));
		}

		int shown = 0;
		for (int id : frontier)
		{
			if (shown++ >= 12)
			{
				zonesContent.add(infoLabel("... and " + (frontier.size() - 12) + " more"));
				break;
			}
			JPanel row = new JPanel(new BorderLayout(4, 0));
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
			row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

			String desc = "Zone " + zones.describe(id);
			if (pos != null)
			{
				desc += "  " + chunkDistance(id, pos) + " tiles " + direction(id, pos);
			}
			JLabel label = new JLabel(desc);
			label.setForeground(Color.WHITE);
			row.add(label, BorderLayout.CENTER);

			JButton unlock = new JButton("Unlock");
			unlock.setEnabled(state.getZoneTokens() > 0);
			unlock.addActionListener(e ->
			{
				String err = state.tryUnlock(id);
				if (err != null)
				{
					label.setText(err);
					label.setForeground(new Color(255, 90, 90));
				}
				else
				{
					unlockNotifier.accept(id);
					refresh();
				}
			});
			row.add(unlock, BorderLayout.EAST);
			zonesContent.add(row);
			zonesContent.add(Box.createVerticalStrut(2));
		}

		zonesContent.add(Box.createVerticalStrut(14));
		JButton reset = new JButton("Reset run...");
		reset.setAlignmentX(Component.LEFT_ALIGNMENT);
		reset.setForeground(new Color(255, 120, 120));
		reset.setToolTipText("Wipe this character's entire run — requires typing 'reset' to confirm");
		reset.addActionListener(e ->
		{
			String typed = (String) JOptionPane.showInputDialog(this,
				"This wipes ALL progress for this character:\nzones, collection, tokens, locked threshold.\n\nType reset to confirm:",
				"Reset run", JOptionPane.WARNING_MESSAGE, null, null, "");
			if (typed != null && typed.trim().equalsIgnoreCase("reset"))
			{
				resetAction.run();
				refresh();
			}
			else if (typed != null)
			{
				JOptionPane.showMessageDialog(this, "Reset not performed — you must type exactly: reset",
					"Reset run", JOptionPane.INFORMATION_MESSAGE);
			}
		});
		zonesContent.add(reset);
	}

	private int chunkDistance(int chunkId, WorldPoint pos)
	{
		return Math.max(Math.abs(zones.centerX(chunkId) - pos.getX()),
			Math.abs(zones.centerY(chunkId) - pos.getY()));
	}

	private String direction(int chunkId, WorldPoint pos)
	{
		int dx = zones.centerX(chunkId) - pos.getX();
		int dy = zones.centerY(chunkId) - pos.getY();
		int half = zones.sizeTiles() / 2;
		String ns = dy > half ? "N" : dy < -half ? "S" : "";
		String ew = dx > half ? "E" : dx < -half ? "W" : "";
		String d = ns + ew;
		return d.isEmpty() ? "here" : d;
	}

	// ---- helpers ----

	private JLabel header(String text)
	{
		JLabel l = new JLabel("<html><body style='width:160px'>" + text + "</body></html>");
		l.setForeground(Color.WHITE);
		l.setFont(l.getFont().deriveFont(Font.BOLD));
		l.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private JLabel infoLabel(String text)
	{
		// Fixed html body width forces wrapping instead of stretching the sidebar
		JLabel l = new JLabel("<html><body style='width:160px'>" + text + "</body></html>");
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}
}
