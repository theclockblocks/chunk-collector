package com.chunktcg.overlay;

import com.chunktcg.ChunkCoord;
import com.chunktcg.ChunkTcgConfig;
import com.chunktcg.TcgStateService;
import com.chunktcg.ZoneGrid;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Shades locked chunks on the world map.
 */
public class ChunkWorldMapOverlay extends Overlay
{
	private final Client client;
	private final TcgStateService state;
	private final ChunkTcgConfig config;
	private final ZoneGrid zones;

	@Inject
	private ChunkWorldMapOverlay(Client client, TcgStateService state, ChunkTcgConfig config, ZoneGrid zones)
	{
		this.client = client;
		this.state = state;
		this.config = config;
		this.zones = zones;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!state.isLoaded() || !config.showWorldMap())
		{
			return null;
		}
		Widget mapWidget = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (mapWidget == null || mapWidget.isHidden())
		{
			return null;
		}
		WorldMap worldMap = client.getWorldMap();
		if (worldMap == null)
		{
			return null;
		}

		Rectangle bounds = mapWidget.getBounds();
		float pixelsPerTile = worldMap.getWorldMapZoom();
		Point mapCenter = worldMap.getWorldMapPosition();
		if (bounds == null || pixelsPerTile <= 0 || mapCenter == null)
		{
			return null;
		}

		// world tile at the left/bottom edge of the widget
		double tilesW = bounds.getWidth() / pixelsPerTile;
		double tilesH = bounds.getHeight() / pixelsPerTile;
		double worldLeft = mapCenter.getX() - tilesW / 2.0;
		double worldBottom = mapCenter.getY() - tilesH / 2.0;

		int zoneTiles = zones.sizeTiles();
		int chunkX0 = (int) Math.floor(worldLeft / zoneTiles) - 1;
		int chunkX1 = (int) Math.ceil((worldLeft + tilesW) / zoneTiles) + 1;
		int chunkY0 = (int) Math.floor(worldBottom / zoneTiles) - 1;
		int chunkY1 = (int) Math.ceil((worldBottom + tilesH) / zoneTiles) + 1;

		Set<Integer> frontier = state.frontier();

		Shape oldClip = g.getClip();
		g.setClip(bounds);
		g.setStroke(new BasicStroke(1));
		for (int cx = Math.max(0, chunkX0); cx <= chunkX1; cx++)
		{
			for (int cy = Math.max(0, chunkY0); cy <= chunkY1; cy++)
			{
				int id = ChunkCoord.id(cx, cy);
				boolean unlocked = state.isUnlocked(id);
				boolean isFrontier = frontier.contains(id);

				int x0 = bounds.x + (int) Math.round((cx * (double) zoneTiles - worldLeft) * pixelsPerTile);
				int y1 = bounds.y + bounds.height - (int) Math.round((cy * (double) zoneTiles - worldBottom) * pixelsPerTile);
				int x1 = bounds.x + (int) Math.round(((cx + 1) * (double) zoneTiles - worldLeft) * pixelsPerTile);
				int y0 = bounds.y + bounds.height - (int) Math.round(((cy + 1) * (double) zoneTiles - worldBottom) * pixelsPerTile);

				if (!unlocked)
				{
					g.setColor(config.lockedColor());
					g.fillRect(x0, y0, x1 - x0, y1 - y0);
				}
				if (isFrontier)
				{
					g.setColor(config.frontierColor());
					g.drawRect(x0, y0, x1 - x0, y1 - y0);
				}
				else if (unlocked)
				{
					g.setColor(config.unlockedBorderColor());
					g.drawRect(x0, y0, x1 - x0, y1 - y0);
				}
			}
		}
		g.setClip(oldClip);
		return null;
	}
}
