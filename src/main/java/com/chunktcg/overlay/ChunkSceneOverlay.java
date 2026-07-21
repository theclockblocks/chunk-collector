package com.chunktcg.overlay;

import com.chunktcg.ChunkCoord;
import com.chunktcg.ChunkTcgConfig;
import com.chunktcg.TcgStateService;
import com.chunktcg.ZoneGrid;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Shades locked chunks in the 3D scene and outlines frontier chunks.
 */
public class ChunkSceneOverlay extends Overlay
{
	private static final int LOCAL_TILE = 128;

	private final Client client;
	private final TcgStateService state;
	private final ChunkTcgConfig config;
	private final ZoneGrid zones;

	@Inject
	private ChunkSceneOverlay(Client client, TcgStateService state, ChunkTcgConfig config, ZoneGrid zones)
	{
		this.client = client;
		this.state = state;
		this.config = config;
		this.zones = zones;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!state.isLoaded())
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || wv.isInstance())
		{
			return null;
		}

		int baseX = wv.getBaseX();
		int baseY = wv.getBaseY();
		int sizeX = wv.getSizeX();
		int sizeY = wv.getSizeY();
		int plane = wv.getPlane();

		Set<Integer> frontier = state.frontier();

		int shift = zones.shift();
		int chunkX0 = baseX >> shift;
		int chunkX1 = (baseX + sizeX - 1) >> shift;
		int chunkY0 = baseY >> shift;
		int chunkY1 = (baseY + sizeY - 1) >> shift;

		g.setStroke(new BasicStroke(2));
		for (int cx = chunkX0; cx <= chunkX1; cx++)
		{
			for (int cy = chunkY0; cy <= chunkY1; cy++)
			{
				int id = ChunkCoord.id(cx, cy);
				boolean unlocked = state.isUnlocked(id);
				boolean isFrontier = frontier.contains(id);
				if (unlocked && !config.shadeScene())
				{
					continue;
				}

				Polygon poly = chunkPoly(wv, plane, cx, cy, baseX, baseY, sizeX, sizeY);
				if (poly == null)
				{
					continue;
				}

				if (!unlocked && config.shadeScene())
				{
					g.setColor(config.lockedColor());
					g.fillPolygon(poly);
				}
				if (isFrontier)
				{
					g.setColor(config.frontierColor());
					g.drawPolygon(poly);
				}
				else if (unlocked)
				{
					g.setColor(config.unlockedBorderColor());
					g.drawPolygon(poly);
				}
			}
		}
		return null;
	}

	/**
	 * Projected quad of the chunk's footprint, clamped to the scene. Null if
	 * any corner fails to project.
	 */
	private Polygon chunkPoly(WorldView wv, int plane, int cx, int cy, int baseX, int baseY, int sizeX, int sizeY)
	{
		int shift = zones.shift();
		int zoneTiles = zones.sizeTiles();
		int wx0 = Math.max(cx << shift, baseX);
		int wy0 = Math.max(cy << shift, baseY);
		int wx1 = Math.min((cx << shift) + zoneTiles, baseX + sizeX);
		int wy1 = Math.min((cy << shift) + zoneTiles, baseY + sizeY);
		if (wx0 >= wx1 || wy0 >= wy1)
		{
			return null;
		}

		int[][] corners = {{wx0, wy0}, {wx1, wy0}, {wx1, wy1}, {wx0, wy1}};
		Polygon poly = new Polygon();
		for (int[] c : corners)
		{
			int localX = clampLocal((c[0] - baseX) * LOCAL_TILE, sizeX);
			int localY = clampLocal((c[1] - baseY) * LOCAL_TILE, sizeY);
			LocalPoint lp = new LocalPoint(localX, localY, wv);
			Point p = Perspective.localToCanvas(client, lp, plane);
			if (p == null)
			{
				return null;
			}
			poly.addPoint(p.getX(), p.getY());
		}
		return poly;
	}

	private static int clampLocal(int local, int sizeTiles)
	{
		return Math.max(0, Math.min(local, sizeTiles * LOCAL_TILE - 1));
	}
}
