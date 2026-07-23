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
	public void parsesLineWithNestedTemplateAndRef()
	{
		// Nested template, ref tag with wiki links and pipes inside — a benign
		// note must not break param splitting or exclude the line
		List<Drop> drops = WikiDropsService.parseWikitext(
			"{{DropsTableHead}}\n"
				+ "{{DropsLine|name=Goblin mail|namenotes={{sic}}|quantity=1|rarity=10/128"
				+ "|raritynotes=<ref group=d>Colour received depends on the "
				+ "[[Goblin mail|mail]] worn.</ref>|gemw=No}}\n"
				+ "{{DropsTableBottom}}");
		assertEquals(1, drops.size());
		assertEquals("Goblin mail", drops.get(0).getItemName());
		assertEquals(10.0 / 128, drops.get(0).getRate(), 1e-9);
	}

	@Test
	public void skipsMembersOnlyDrops()
	{
		// Real line from the Goblin page: {{(m)}} marks a members-only drop
		List<Drop> drops = WikiDropsService.parseWikitext(
			"{{DropsLine|name=Goblin champion scroll|namenotes={{(m)}}|quantity=1"
				+ "|rarity=1/5000|gemw=No}}\n"
				+ "{{DropsLine|name=Bones|quantity=1|rarity=Always}}");
		assertEquals(1, drops.size());
		assertEquals("Bones", drops.get(0).getItemName());
	}

	@Test
	public void skipsQuestConditionalDrops()
	{
		// Quest-locked drops carry a ref note like "only dropped during ..."
		List<Drop> drops = WikiDropsService.parseWikitext(
			"{{DropsLine|name=Goblin skull|quantity=1|rarity=1/4"
				+ "|raritynotes=<ref group=d>Goblin skulls are only dropped during "
				+ "[[Rag and Bone Man I]].</ref>|gemw=No}}\n"
				+ "{{DropsLine|name=Bones|quantity=1|rarity=Always}}");
		assertEquals(1, drops.size());
		assertEquals("Bones", drops.get(0).getItemName());
	}

	@Test
	public void keepsItemWithSeparateUnconditionalLine()
	{
		// Only the conditional line is skipped — the item stays via its
		// unconditional line
		List<Drop> drops = WikiDropsService.parseWikitext(
			"{{DropsLine|name=Coins|quantity=10|rarity=1/4"
				+ "|raritynotes=<ref group=d>Only dropped in [[free-to-play]] "
				+ "worlds.</ref>|gemw=No}}\n"
				+ "{{DropsLine|name=Coins|quantity=5|rarity=1/8|gemw=No}}");
		assertEquals(1, drops.size());
		assertEquals("Coins", drops.get(0).getItemName());
		assertEquals(1.0 / 8, drops.get(0).getRate(), 1e-9);
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
	public void skipsClueLines()
	{
		// Clue scrolls are global RNG rewards, not per-mob collectibles
		List<Drop> drops = WikiDropsService.parseWikitext(
			"{{DropsLine|name=Bones|quantity=1|rarity=Always}}\n"
				+ "{{DropsLineClue|type=beginner|rarity=1/128}}\n"
				+ "{{DropsLineClue|type=easy|rarity=1/128|f2p=yes}}");
		assertEquals(1, drops.size());
		assertEquals("Bones", drops.get(0).getItemName());
	}

	@Test
	public void prefersAltNameOverPageName()
	{
		// Real line from the Imp page: name= is the disambiguated wiki page
		// title, alt= is the actual in-game item name
		List<Drop> drops = WikiDropsService.parseWikitext(
			"{{DropsLine|name=Potion (Apothecary)|alt=Potion|quantity=1"
				+ "|rarity=1/128|gemw=No}}");
		assertEquals(1, drops.size());
		assertEquals("Potion", drops.get(0).getItemName());
		// The page title is kept for wiki lookups (in-game name is a disambig)
		assertEquals("Potion (Apothecary)", drops.get(0).getPageName());
		assertEquals("Potion (Apothecary)", drops.get(0).wikiPage());
	}

	@Test
	public void parsesInfoboxItemId()
	{
		assertEquals(2311, WikiDropsService.parseInfoboxItemId(
			"{{Infobox Item\n|name = Burnt bread\n|id = 2311\n|tradeable = No\n}}"));
		// Versioned infoboxes number their ids
		assertEquals(195, WikiDropsService.parseInfoboxItemId(
			"{{Infobox Item\n|name = Potion\n|id1 = 195\n|id2 = 196\n}}"));
		assertEquals(-1, WikiDropsService.parseInfoboxItemId(
			"Some disambiguation page with no infobox."));
	}

	@Test
	public void excludesFringeDropVersion()
	{
		// Condensed from the real Rat page: most variant declarations use
		// Regular, so the Stronghold-only Bones line must not leak onto
		// regular rats (whose own lines are all conditional)
		List<Drop> drops = WikiDropsService.parseWikitext(
			"|dropversion = Regular\n|dropversion = Regular\n"
				+ "|dropversion = Regular\n|dropversion = Regular\n"
				+ "|dropversion = Regular\n|dropversion = Regular\n"
				+ "|dropversion = Regular\n|dropversion = Regular\n"
				+ "{{DropsTableHead|dropversion=Regular}}\n"
				+ "{{DropsLine|name=Rat's tail|quantity=1|rarity=Always"
				+ "|raritynotes=<ref group=d>Rat's tails is only dropped during "
				+ "[[Witch's Potion]].</ref>|gemw=No}}\n"
				+ "{{DropsTableBottom}}\n"
				+ "{{DropsTableHead|dropversion=Stronghold of Security}}\n"
				+ "{{DropsLine|name=Bones|quantity=1|rarity=Always}}\n"
				+ "{{DropsTableBottom}}");
		assertEquals(0, drops.size());
	}

	@Test
	public void mergesBalancedDropVersions()
	{
		// Condensed from the real Goblin page: both drop tables are used by
		// many overworld variants, so their tables must merge
		List<Drop> drops = WikiDropsService.parseWikitext(
			"|dropversion = Drop table 1\n|dropversion = Drop table 2\n"
				+ "|dropversion = Drop table 2\n"
				+ "|dropversion = Drop table 1,Drop table 2\n"
				+ "|dropversion = Drop table 1, Drop table 2\n"
				+ "{{DropsTableHead|dropversion=Drop table 1}}\n"
				+ "{{DropsLine|name=Chef's hat|quantity=1|rarity=3/128}}\n"
				+ "{{DropsTableBottom}}\n"
				+ "{{DropsTableHead|dropversion=Drop table 2}}\n"
				+ "{{DropsLine|name=Tin ore|quantity=1|rarity=1/128}}\n"
				+ "{{DropsTableBottom}}");
		assertEquals(2, drops.size());
	}

	@Test
	public void mergesUnversionedTables()
	{
		// Pages like Goblin have several tables (per combat level) with no
		// dropversion — those still merge
		List<Drop> drops = WikiDropsService.parseWikitext(
			"{{DropsTableHead}}\n"
				+ "{{DropsLine|name=Bones|quantity=1|rarity=Always}}\n"
				+ "{{DropsTableBottom}}\n"
				+ "{{DropsTableHead}}\n"
				+ "{{DropsLine|name=Hammer|quantity=1|rarity=15/128}}\n"
				+ "{{DropsTableBottom}}");
		assertEquals(2, drops.size());
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
