package com.chunktcg;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An owned card. Keyed in the collection by lower-cased item name. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardEntry
{
	private String name;
	/** Item id for icon rendering, -1 if unknown (e.g. pack pull of an item we couldn't resolve). */
	private int itemId;
	private int count;
}
