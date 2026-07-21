package com.chunktcg.panel;

import com.chunktcg.CardEntry;
import com.chunktcg.ChunkTcgConfig;
import com.chunktcg.Drop;
import com.chunktcg.PackService;
import com.chunktcg.RarityTier;
import com.chunktcg.TcgStateService;
import com.chunktcg.WikiDropsService;
import com.chunktcg.ZoneGrid;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.AsyncBufferedImage;

public class ChunkTcgPanel extends PluginPanel
{
	private final TcgStateService state;
	private final WikiDropsService drops;
	private final PackService packs;
	private final ChunkTcgConfig config;
	private final ItemManager itemManager;
	private final ZoneGrid zones;
	private final Supplier<WorldPoint> playerPos;
	private final Supplier<List<PackService.PullResult>> starterOpener;
	private final Supplier<List<PackService.PullResult>> packOpener;

	private final JPanel albumContent = new JPanel();
	private final JPanel packsContent = new JPanel();
	private final JPanel progressContent = new JPanel();
	private final JPanel display = new JPanel();

	public ChunkTcgPanel(TcgStateService state, WikiDropsService drops, PackService packs,
		ChunkTcgConfig config, ItemManager itemManager, ZoneGrid zones,
		Supplier<WorldPoint> playerPos, Supplier<List<PackService.PullResult>> starterOpener,
		Supplier<List<PackService.PullResult>> packOpener)
	{
		super(false);
		this.state = state;
		this.drops = drops;
		this.packs = packs;
		this.config = config;
		this.itemManager = itemManager;
		this.zones = zones;
		this.playerPos = playerPos;
		this.starterOpener = starterOpener;
		this.packOpener = packOpener;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		albumContent.setLayout(new BoxLayout(albumContent, BoxLayout.Y_AXIS));
		packsContent.setLayout(new BoxLayout(packsContent, BoxLayout.Y_AXIS));
		progressContent.setLayout(new BoxLayout(progressContent, BoxLayout.Y_AXIS));

		MaterialTabGroup tabGroup = new MaterialTabGroup(display);
		MaterialTab albumTab = new MaterialTab("Album", tabGroup, wrapScroll(albumContent));
		MaterialTab packsTab = new MaterialTab("Packs", tabGroup, wrapScroll(packsContent));
		MaterialTab progressTab = new MaterialTab("Zones", tabGroup, wrapScroll(progressContent));
		tabGroup.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
		tabGroup.addTab(albumTab);
		tabGroup.addTab(packsTab);
		tabGroup.addTab(progressTab);
		tabGroup.select(albumTab);

		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		refresh();
	}

	private JScrollPane wrapScroll(JPanel content)
	{
		JPanel holder = new JPanel(new BorderLayout());
		holder.setBackground(ColorScheme.DARK_GRAY_COLOR);
		holder.add(content, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(holder);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		// The sidebar is ~225px wide — never grow sideways, wrap instead
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
		rebuildAlbum();
		rebuildPacks(null);
		rebuildProgress();
		revalidate();
		repaint();
	}

	// ---- Album ----

	private void rebuildAlbum()
	{
		albumContent.removeAll();
		if (!state.isLoaded())
		{
			albumContent.add(infoLabel("Log in to load your collection."));
			return;
		}

		Set<String> npcs = new HashSet<>(state.allDiscoveredNpcs());
		Map<String, CardEntry> cards = new HashMap<>(state.getCards());
		Set<String> shownItems = new HashSet<>();

		if (npcs.isEmpty())
		{
			albumContent.add(infoLabel(state.starterComplete()
				? "Kill something in an unlocked zone to discover its cards. Go punch a goblin!"
				: "Open your starter packs in the Packs tab — fate decides your loadout!"));
		}

		for (String npc : new TreeSet<>(npcs))
		{
			List<Drop> cached = drops.get(npc);
			List<Drop> table = cached == null ? null : new ArrayList<>(cached);
			JPanel section = new JPanel();
			section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
			section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			section.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

			if (table == null)
			{
				section.add(header(npc + " — fetching drop table..."));
				albumContent.add(section);
				albumContent.add(Box.createVerticalStrut(6));
				continue;
			}

			table.sort(Comparator.comparingDouble(Drop::getRate).reversed());
			int owned = state.ownedOf(table);
			section.add(header(npc + "  " + owned + "/" + table.size()));

			// Only cards actually pulled from packs are revealed — the rest stay a mystery
			JPanel grid = new JPanel(new GridLayout(0, 4, 2, 2));
			grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			boolean any = false;
			for (Drop d : table)
			{
				String key = WikiDropsService.normalize(d.getItemName());
				CardEntry ownedCard = cards.get(key);
				if (ownedCard == null)
				{
					continue;
				}
				any = true;
				shownItems.add(key);
				grid.add(cardCell(d.getItemName(), ownedCard, d.tier()));
			}
			if (any)
			{
				section.add(grid);
			}
			else
			{
				section.add(infoLabel("No cards pulled yet — open packs!"));
			}
			albumContent.add(section);
			albumContent.add(Box.createVerticalStrut(6));
		}

		// Cards owned that aren't in any discovered table (e.g. old pulls)
		List<CardEntry> other = new ArrayList<>();
		for (Map.Entry<String, CardEntry> e : cards.entrySet())
		{
			if (!shownItems.contains(e.getKey()))
			{
				other.add(e.getValue());
			}
		}
		if (!other.isEmpty())
		{
			JPanel section = new JPanel();
			section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
			section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			section.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
			section.add(header("Other cards"));
			JPanel grid = new JPanel(new GridLayout(0, 4, 2, 2));
			grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			other.sort(Comparator.comparing(CardEntry::getName));
			for (CardEntry e : other)
			{
				grid.add(cardCell(e.getName(), e, RarityTier.COMMON));
			}
			section.add(grid);
			albumContent.add(section);
		}
	}

	private JPanel cardCell(String itemName, CardEntry owned, RarityTier tier)
	{
		// Mini trading card: rarity-coloured frame, item art, count badge
		JPanel cell = new JPanel(new BorderLayout());
		cell.setPreferredSize(new Dimension(40, 52));
		cell.setBackground(new Color(30, 30, 36));
		cell.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(
				owned != null ? tier.getColor() : ColorScheme.MEDIUM_GRAY_COLOR.darker(), 2, true),
			BorderFactory.createEmptyBorder(1, 1, 1, 1)));

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
		cell.add(icon, BorderLayout.CENTER);

		if (owned != null && owned.getCount() > 1)
		{
			JLabel count = new JLabel("x" + owned.getCount(), SwingConstants.RIGHT);
			count.setFont(count.getFont().deriveFont(9f));
			count.setForeground(Color.WHITE);
			cell.add(count, BorderLayout.SOUTH);
		}

		cell.setToolTipText("<html>" + itemName + "<br>" + tier.getLabel()
			+ (owned != null ? "<br>Owned: " + owned.getCount() : "<br>Not collected") + "</html>");
		return cell;
	}

	// ---- Packs ----

	private void rebuildPacks(List<PackService.PullResult> lastPulls)
	{
		packsContent.removeAll();
		if (!state.isLoaded())
		{
			packsContent.add(infoLabel("Log in to open packs."));
			return;
		}

		if (!state.starterComplete())
		{
			int opened = state.getStarterPacksOpened();
			int total = config.starterPackCount();
			packsContent.add(header("Starter packs: " + opened + "/" + total + " opened"));
			packsContent.add(infoLabel("Nothing is unlocked — not even bronze. These free packs "
				+ "draw from your starting zone's mobs plus a pool of basics, so fate decides "
				+ "what kind of adventurer you become."));
			packsContent.add(Box.createVerticalStrut(6));

			JButton open = new JButton("Open starter pack " + (opened + 1) + " of " + total);
			open.addActionListener(e ->
			{
				List<PackService.PullResult> pulls = starterOpener.get();
				rebuildPacks(pulls);
				rebuildAlbum();
				rebuildProgress();
				revalidate();
				repaint();
			});
			packsContent.add(open);
			packsContent.add(Box.createVerticalStrut(10));
			renderPulls(lastPulls);
			return;
		}

		int poolSize = packs.pool().size();
		packsContent.add(header("Credits: " + state.getCredits()));
		packsContent.add(infoLabel("Pack pool: " + poolSize + " cards from your unlocked zones"));
		packsContent.add(Box.createVerticalStrut(8));

		JButton buy = new JButton("Open pack (" + config.packCost() + " credits)");
		buy.setEnabled(poolSize > 0 && state.getCredits() >= config.packCost());
		buy.addActionListener(e ->
		{
			List<PackService.PullResult> pulls = packOpener.get();
			rebuildPacks(pulls);
			rebuildAlbum();
			rebuildProgress();
			revalidate();
			repaint();
		});
		packsContent.add(buy);
		packsContent.add(Box.createVerticalStrut(4));

		JButton sell = new JButton("Sell all duplicate cards");
		sell.addActionListener(e ->
		{
			int gained = state.sellAllDupes();
			rebuildPacks(null);
			rebuildAlbum();
			packsContent.add(infoLabel("Sold dupes for " + gained + " credits."));
			revalidate();
			repaint();
		});
		packsContent.add(sell);
		packsContent.add(Box.createVerticalStrut(10));

		renderPulls(lastPulls);
	}

	private void renderPulls(List<PackService.PullResult> lastPulls)
	{
		if (lastPulls == null)
		{
			return;
		}
		packsContent.add(header("Pack results"));
		for (PackService.PullResult pull : lastPulls)
		{
			JPanel row = new JPanel(new BorderLayout(4, 0));
			row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			row.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 3, 0, 0,
					pull.isZoneCard() ? new Color(255, 215, 0) : pull.getTier().getColor()),
				BorderFactory.createEmptyBorder(2, 4, 2, 4)));

			if (!pull.isZoneCard() && pull.getItemId() >= 0)
			{
				JLabel icon = new JLabel();
				itemManager.getImage(pull.getItemId()).addTo(icon);
				row.add(icon, BorderLayout.WEST);
			}

			JLabel label;
			if (pull.isZoneCard())
			{
				label = new JLabel("★ " + pull.getItemName());
				label.setForeground(new Color(255, 215, 0));
				label.setFont(label.getFont().deriveFont(Font.BOLD));
				label.setToolTipText(pull.getItemName());
			}
			else
			{
				label = new JLabel((pull.isNew() ? "NEW " : "") + pull.getItemName());
				label.setForeground(pull.getTier().getColor());
				if (pull.isNew())
				{
					label.setFont(label.getFont().deriveFont(Font.BOLD));
				}
				label.setToolTipText(pull.getItemName() + " [" + pull.getTier().getLabel() + "]"
					+ (pull.isNew() ? " — NEW" : " — duplicate"));
			}
			row.add(label, BorderLayout.CENTER);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
			packsContent.add(row);
			packsContent.add(Box.createVerticalStrut(2));
		}
	}

	// ---- Progress / chunks ----

	private void rebuildProgress()
	{
		progressContent.removeAll();
		if (!state.isLoaded())
		{
			progressContent.add(infoLabel("Log in to see progression."));
			return;
		}

		int[] completion = state.completion();
		int pct = completion[1] == 0 ? 100 : completion[0] * 100 / completion[1];
		progressContent.add(header("Zones unlocked: " + state.getUnlockedChunks().size()));
		progressContent.add(infoLabel("Cards pulled: " + completion[0] + "/" + completion[1] + " (" + pct + "%)"));
		progressContent.add(infoLabel("Credits: " + state.getCredits()));
		if (state.getViolations() > 0)
		{
			JLabel v = infoLabel("Violations: " + state.getViolations());
			v.setForeground(new Color(255, 90, 90));
			progressContent.add(v);
		}
		progressContent.add(Box.createVerticalStrut(8));

		List<Integer> frontier = new ArrayList<>(state.frontier());
		progressContent.add(header("Frontier zones: " + frontier.size()));
		progressContent.add(infoLabel("Zones unlock ONLY by pulling zone cards from packs. "
			+ frontier.size() + " zone cards are mixed into the pool (~" + packs.zoneCardPercent()
			+ "% per card). Which one you get is up to fate."));
		progressContent.add(Box.createVerticalStrut(4));

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
				progressContent.add(infoLabel("... and " + (frontier.size() - 12) + " more"));
				break;
			}
			JPanel row = new JPanel(new BorderLayout(4, 0));
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
			progressContent.add(row);
			progressContent.add(Box.createVerticalStrut(2));
		}
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
		return l;
	}

	private JLabel infoLabel(String text)
	{
		// Fixed html body width forces wrapping instead of stretching the sidebar
		JLabel l = new JLabel("<html><body style='width:160px'>" + text + "</body></html>");
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return l;
	}
}
