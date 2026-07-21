package com.chunktcg;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WikiDropsParseTest
{
	@Test
	public void parsesSimpleLine()
	{
		List<Drop> drops = WikiDropsService.parseWikitext(
			"{{DropsLine|name=Bones|quantity=1|rarity=Always}}");
		assertEquals(1, drops.size());
		assertEquals("Bones", drops.get(0).getItemName());
		assertEquals(1.0, drops.get(0).getRate(), 1e-9);
	}

	@Test
	public void parsesGiantSpiderLootingBagLine()
	{
		// Real line from the Giant spider page: nested template, ref tag with
		// wiki links and pipes inside — previously parsed as 0 drops
		List<Drop> drops = WikiDropsService.parseWikitext(
			"{{DropsTableHead}}\n"
				+ "{{DropsLine|name=Looting bag|namenotes={{(m)}}|quantity=1|rarity=1/15"
				+ "|raritynotes=<ref group=d>Looting bags are only dropped by those found in the "
				+ "[[Wilderness]].</ref>|altrarity=1/5|gemw=No|leagueRegion=Wilderness}}\n"
				+ "{{DropsTableBottom}}");
		assertEquals(1, drops.size());
		assertEquals("Looting bag", drops.get(0).getItemName());
		assertEquals(1.0 / 15, drops.get(0).getRate(), 1e-9);
	}

	@Test
	public void skipsNothingEntries()
	{
		List<Drop> drops = WikiDropsService.parseWikitext(
			"{{DropsLine|name=Nothing|rarity=1/2}}\n"
				+ "{{DropsLine|name=Bones|quantity=1|rarity=Always}}");
		assertEquals(1, drops.size());
		assertEquals("Bones", drops.get(0).getItemName());
	}

	@Test
	public void parsesClueLines()
	{
		List<Drop> drops = WikiDropsService.parseWikitext(
			"{{DropsLine|name=Bones|quantity=1|rarity=Always}}\n"
				+ "{{DropsLineClue|type=beginner|rarity=1/128}}");
		assertEquals(2, drops.size());
		boolean found = false;
		for (Drop d : drops)
		{
			if (d.getItemName().equals("Clue scroll (beginner)"))
			{
				found = true;
				assertEquals(1.0 / 128, d.getRate(), 1e-9);
			}
		}
		org.junit.Assert.assertTrue(found);
	}

	@Test
	public void keepsHighestRateForDuplicates()
	{
		List<Drop> drops = WikiDropsService.parseWikitext(
			"{{DropsLine|name=Coins|quantity=5|rarity=1/4}}\n"
				+ "{{DropsLine|name=Coins|quantity=25|rarity=1/128}}");
		assertEquals(1, drops.size());
		assertEquals(0.25, drops.get(0).getRate(), 1e-9);
	}
}
