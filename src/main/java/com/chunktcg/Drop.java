package com.chunktcg;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One line of an NPC's drop table, parsed from the OSRS Wiki. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Drop
{
	private String itemName;
	/** Drop probability, 1.0 for "Always". */
	private double rate;
	/** Wiki page title when it differs from the in-game item name (disambiguation), else null. */
	private String pageName;

	public Drop(String itemName, double rate)
	{
		this(itemName, rate, null);
	}

	/** The wiki page to consult for this item. */
	public String wikiPage()
	{
		return pageName != null ? pageName : itemName;
	}

	public RarityTier tier()
	{
		return RarityTier.fromRate(rate);
	}
}
