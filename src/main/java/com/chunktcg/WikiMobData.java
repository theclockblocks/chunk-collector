package com.chunktcg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/**
 * A mob's wiki drop data, version-aware. Pages like Goblin split drops into
 * dropversion variants ("Drop table 1" for overworld goblins, "Drop table 2"
 * for armed cave goblins) and map NPC ids to versions in the infobox — so the
 * table shown for a mob depends on which variants the player has actually
 * encountered.
 */
@Data
public class WikiMobData
{
	/** Cache format version; older on-disk caches are refetched. */
	public static final int CURRENT_FMT = 2;

	private int fmt;

	/**
	 * The resolved wiki page title — canonical mob identity. "Bull" redirects
	 * to "Brutus": one mob, one log entry, whichever name the client shows.
	 */
	private String canonicalName;

	/** dropversion label (normalized, "" = unversioned baseline) -> drops. */
	private Map<String, List<Drop>> tablesByVersion = new HashMap<>();

	/** NPC id (string, for gson) -> dropversion labels from the infobox. */
	private Map<String, List<String>> versionsById = new HashMap<>();

	/** Vote-filtered fallback labels used until a variant has been seen. */
	private List<String> majorVersions = new ArrayList<>();

	/**
	 * The effective flat table: baseline drops plus the tables of the given
	 * seen versions — or of all major versions when nothing has been seen yet.
	 */
	public List<Drop> flatten(Set<String> seenVersions)
	{
		Map<String, Drop> byName = new HashMap<>();
		mergeInto(byName, tablesByVersion.get(""));
		Collection<String> labels =
			seenVersions != null && !seenVersions.isEmpty() ? seenVersions : majorVersions;
		for (String label : labels)
		{
			mergeInto(byName, tablesByVersion.get(WikiDropsService.normalize(label)));
		}
		return new ArrayList<>(byName.values());
	}

	private static void mergeInto(Map<String, Drop> byName, List<Drop> drops)
	{
		if (drops == null)
		{
			return;
		}
		for (Drop d : drops)
		{
			String key = WikiDropsService.normalize(d.getItemName());
			Drop existing = byName.get(key);
			if (existing == null || d.getRate() > existing.getRate())
			{
				byName.put(key, d);
			}
		}
	}
}
