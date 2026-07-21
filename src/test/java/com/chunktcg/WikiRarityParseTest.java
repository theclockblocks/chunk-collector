package com.chunktcg;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WikiRarityParseTest
{
	@Test
	public void parsesFractions()
	{
		assertEquals(1.0 / 128, WikiDropsService.parseRarity("1/128"), 1e-9);
		assertEquals(3.0 / 128, WikiDropsService.parseRarity("3/128"), 1e-9);
		assertEquals(1.0 / 25.6, WikiDropsService.parseRarity("1/25.6"), 1e-9);
		assertEquals(1.0 / 5000, WikiDropsService.parseRarity("~1/5,000"), 1e-9);
	}

	@Test
	public void parsesWords()
	{
		assertEquals(1.0, WikiDropsService.parseRarity("Always"), 1e-9);
		assertEquals(1.0 / 20, WikiDropsService.parseRarity("Common"), 1e-9);
		assertEquals(1.0 / 64, WikiDropsService.parseRarity("Uncommon"), 1e-9);
		assertEquals(1.0 / 512, WikiDropsService.parseRarity("Rare"), 1e-9);
		assertEquals(1.0 / 5000, WikiDropsService.parseRarity("Very rare"), 1e-9);
	}

	@Test
	public void tiersFromRates()
	{
		assertEquals(RarityTier.COMMON, RarityTier.fromRate(1.0));
		assertEquals(RarityTier.UNCOMMON, RarityTier.fromRate(1.0 / 30));
		assertEquals(RarityTier.RARE, RarityTier.fromRate(1.0 / 128));
		assertEquals(RarityTier.EPIC, RarityTier.fromRate(1.0 / 1000));
		assertEquals(RarityTier.LEGENDARY, RarityTier.fromRate(1.0 / 10000));
	}
}
