package com.chunktcg.overlay;

import com.chunktcg.RarityTier;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Bronzeman-style unlock splash: when a new card is pulled, a card-shaped
 * toast is shown top-center for a few seconds. Inspired by
 * sethrem/bronzeman's unlock graphic.
 */
@Singleton
public class CardToastOverlay extends Overlay
{
	private static final int SHOW_MS = 3500;
	private static final int WIDTH = 220;
	private static final int HEIGHT = 56;

	private static class Toast
	{
		final String text;
		final int itemId;
		final Color color;
		long shownAt;

		Toast(String text, int itemId, Color color)
		{
			this.text = text;
			this.itemId = itemId;
			this.color = color;
		}
	}

	private final Deque<Toast> queue = new ArrayDeque<>();
	private final ItemManager itemManager;

	@Inject
	private CardToastOverlay(ItemManager itemManager)
	{
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	public void push(String text, int itemId, RarityTier tier)
	{
		synchronized (queue)
		{
			queue.addLast(new Toast(text, itemId, tier != null ? tier.getColor() : new Color(255, 215, 0)));
		}
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Toast toast;
		synchronized (queue)
		{
			toast = queue.peekFirst();
			if (toast == null)
			{
				return null;
			}
			long now = System.currentTimeMillis();
			if (toast.shownAt == 0)
			{
				toast.shownAt = now;
			}
			if (now - toast.shownAt > SHOW_MS)
			{
				queue.pollFirst();
				return null;
			}
		}

		g.setColor(new Color(20, 20, 25, 230));
		g.fillRoundRect(0, 0, WIDTH, HEIGHT, 8, 8);
		g.setColor(toast.color);
		g.setStroke(new BasicStroke(2));
		g.drawRoundRect(0, 0, WIDTH, HEIGHT, 8, 8);

		int textX = 10;
		if (toast.itemId >= 0)
		{
			BufferedImage img = itemManager.getImage(toast.itemId);
			if (img != null)
			{
				g.drawImage(img, 8, (HEIGHT - img.getHeight()) / 2, null);
				textX = 8 + img.getWidth() + 6;
			}
		}

		g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
		FontMetrics fm = g.getFontMetrics();
		g.setColor(Color.WHITE);
		g.drawString("Card unlocked", textX, HEIGHT / 2 - 4);
		g.setColor(toast.color);
		String name = toast.text;
		if (fm.stringWidth(name) > WIDTH - textX - 8)
		{
			while (name.length() > 3 && fm.stringWidth(name + "...") > WIDTH - textX - 8)
			{
				name = name.substring(0, name.length() - 1);
			}
			name += "...";
		}
		g.drawString(name, textX, HEIGHT / 2 + 12);

		return new Dimension(WIDTH, HEIGHT);
	}
}
