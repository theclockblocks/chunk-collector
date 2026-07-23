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
