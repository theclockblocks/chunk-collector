package com.chunktcg.overlay;

import com.chunktcg.ChunkTcgConfig;
import com.chunktcg.TcgStateService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Grays out items in the inventory, equipment and bank that haven't been
 * unlocked by a pack pull. Packs are life.
 */
public class ItemLockOverlay extends WidgetItemOverlay
{
	private static final Color SHADE = new Color(0, 0, 0, 150);

	private final TcgStateService state;
	private final ChunkTcgConfig config;
	private final ItemManager itemManager;

	@Inject
	private ItemLockOverlay(TcgStateService state, ChunkTcgConfig config, ItemManager itemManager)
	{
		this.state = state;
		this.config = config;
		this.itemManager = itemManager;
		showOnInventory();
		showOnEquipment();
		showOnBank();
	}

	@Override
	public void renderItemOverlay(Graphics2D g, int itemId, WidgetItem itemWidget)
	{
		if (!state.isLoaded() || !config.enforceItemLock())
		{
			return;
		}
		String name = itemManager.getItemComposition(itemId).getName();
		if (state.isItemUnlocked(name))
		{
			return;
		}
		Rectangle bounds = itemWidget.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}
		g.setColor(SHADE);
		g.fill(bounds);
	}
}
