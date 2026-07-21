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

	public RarityTier tier()
	{
		return RarityTier.fromRate(rate);
	}
}
