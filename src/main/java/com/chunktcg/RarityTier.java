package com.chunktcg;

import java.awt.Color;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RarityTier
{
	COMMON("Common", new Color(176, 176, 176), 60, 5),
	UNCOMMON("Uncommon", new Color(94, 204, 94), 25, 15),
	RARE("Rare", new Color(86, 156, 255), 10, 40),
	EPIC("Epic", new Color(196, 106, 255), 4, 150),
	LEGENDARY("Legendary", new Color(255, 176, 46), 1, 500);

	private final String label;
	private final Color color;
	private final int pullWeight;
	private final int sellValue;

	/**
	 * Map a drop rate (probability, e.g. 1/128 = 0.0078) to a tier.
	 */
	public static RarityTier fromRate(double rate)
	{
		if (rate >= 1.0 / 10)
		{
			return COMMON;
		}
		if (rate >= 1.0 / 64)
		{
			return UNCOMMON;
		}
		if (rate >= 1.0 / 512)
		{
			return RARE;
		}
		if (rate >= 1.0 / 4096)
		{
			return EPIC;
		}
		return LEGENDARY;
	}
}
