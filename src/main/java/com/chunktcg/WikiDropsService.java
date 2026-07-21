package com.chunktcg;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches and caches NPC drop tables from the OSRS Wiki by parsing
 * {{DropsLine|...}} templates out of page wikitext. Results are cached in
 * memory and on disk under .runelite/chunk-tcg/drops/.
 */
@Slf4j
@Singleton
public class WikiDropsService
{
	private static final String WIKI_API = "https://oldschool.runescape.wiki/api.php";
	private static final File CACHE_DIR = new File(RuneLite.RUNELITE_DIR, "chunk-tcg/drops");
	// Tolerates one level of nested {{templates}} inside the line's params
	private static final Pattern DROPS_LINE = Pattern.compile("\\{\\{DropsLine\\|((?:[^{}]|\\{\\{[^{}]*}})*)}}");
	private static final Pattern NESTED_TEMPLATE = Pattern.compile("\\{\\{[^{}]*}}");
	private static final Pattern REF_TAG = Pattern.compile("<ref[^>]*+(?:/>|>.*?</ref>)", Pattern.DOTALL);
	private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[(?:[^\\]|]*\\|)?([^\\]]*)]]");
	private static final Pattern FRACTION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:[.,]\\d+)?)");

	private final Map<String, List<Drop>> cache = new ConcurrentHashMap<>();
	private final Set<String> pending = ConcurrentHashMap.newKeySet();
	private final Set<String> failed = ConcurrentHashMap.newKeySet();

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	/** Returns the cached drop table, or null if not (yet) available. */
	public List<Drop> get(String npcName)
	{
		String key = normalize(npcName);
		List<Drop> cached = cache.get(key);
		if (cached != null)
		{
			return cached.isEmpty() ? null : cached;
		}

		List<Drop> fromDisk = loadFromDisk(key);
		if (fromDisk != null)
		{
			cache.put(key, fromDisk);
			return fromDisk.isEmpty() ? null : fromDisk;
		}
		return null;
	}

	public boolean isKnown(String npcName)
	{
		String key = normalize(npcName);
		return cache.containsKey(key) || diskFile(key).exists();
	}

	/**
	 * Ensure the drop table for this NPC is available, fetching from the wiki if
	 * needed. onUpdate runs (on an OkHttp thread) after a successful fetch.
	 */
	public void ensureFetched(String npcName, Runnable onUpdate)
	{
		String key = normalize(npcName);
		if (cache.containsKey(key) || failed.contains(key) || !pending.add(key))
		{
			return;
		}

		List<Drop> fromDisk = loadFromDisk(key);
		if (fromDisk != null)
		{
			cache.put(key, fromDisk);
			pending.remove(key);
			onUpdate.run();
			return;
		}

		HttpUrl url = HttpUrl.get(WIKI_API).newBuilder()
			.addQueryParameter("action", "parse")
			.addQueryParameter("format", "json")
			.addQueryParameter("prop", "wikitext")
			.addQueryParameter("redirects", "1")
			.addQueryParameter("page", npcName)
			.build();

		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", "chunk-tcg-runelite-plugin")
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Wiki fetch failed for {}", npcName, e);
				pending.remove(key);
				failed.add(key);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						pending.remove(key);
						failed.add(key);
						return;
					}
					String body = r.body().string();
					List<Drop> drops = parseDrops(body);
					cache.put(key, drops);
					saveToDisk(key, drops);
					pending.remove(key);
					log.debug("Fetched {} drops for {}", drops.size(), npcName);
					onUpdate.run();
				}
				catch (Exception e)
				{
					log.debug("Wiki parse failed for {}", npcName, e);
					pending.remove(key);
					failed.add(key);
				}
			}
		});
	}

	private List<Drop> parseDrops(String apiResponseJson)
	{
		JsonObject root = gson.fromJson(apiResponseJson, JsonObject.class);
		if (root == null || !root.has("parse"))
		{
			return new ArrayList<>();
		}
		String wikitext = root.getAsJsonObject("parse")
			.getAsJsonObject("wikitext")
			.get("*").getAsString();
		return parseWikitext(wikitext);
	}

	static List<Drop> parseWikitext(String wikitext)
	{
		Map<String, Drop> byName = new HashMap<>();
		Matcher m = DROPS_LINE.matcher(wikitext);
		while (m.find())
		{
			// Strip refs, nested templates and wiki-link syntax so pipes inside
			// them don't break param splitting
			String body = REF_TAG.matcher(m.group(1)).replaceAll("");
			body = NESTED_TEMPLATE.matcher(body).replaceAll("");
			body = WIKI_LINK.matcher(body).replaceAll("$1");

			String name = null;
			String rarity = null;
			for (String param : body.split("\\|"))
			{
				int eq = param.indexOf('=');
				if (eq < 0)
				{
					continue;
				}
				String k = param.substring(0, eq).trim().toLowerCase(Locale.ROOT);
				String v = param.substring(eq + 1).trim();
				if (k.equals("name"))
				{
					name = v;
				}
				else if (k.equals("rarity"))
				{
					rarity = v;
				}
			}
			if (name == null || name.isEmpty())
			{
				continue;
			}
			double rate = parseRarity(rarity);
			String nkey = normalize(name);
			// Keep the highest (easiest) rate if the same item appears on multiple lines
			Drop existing = byName.get(nkey);
			if (existing == null || rate > existing.getRate())
			{
				byName.put(nkey, new Drop(name, rate));
			}
		}
		return new ArrayList<>(byName.values());
	}

	static double parseRarity(String rarity)
	{
		if (rarity == null || rarity.isEmpty())
		{
			return 1.0 / 64;
		}
		String r = rarity.toLowerCase(Locale.ROOT);
		if (r.contains("always"))
		{
			return 1.0;
		}
		Matcher f = FRACTION.matcher(r);
		if (f.find())
		{
			double num = Double.parseDouble(f.group(1));
			double den = Double.parseDouble(f.group(2).replace(",", ""));
			if (den > 0)
			{
				return Math.min(1.0, num / den);
			}
		}
		if (r.contains("very rare"))
		{
			return 1.0 / 5000;
		}
		if (r.contains("rare"))
		{
			return 1.0 / 512;
		}
		if (r.contains("uncommon"))
		{
			return 1.0 / 64;
		}
		if (r.contains("common"))
		{
			return 1.0 / 20;
		}
		return 1.0 / 64;
	}

	/** Best (highest) drop rate for an item name across the given NPCs' cached tables. */
	public RarityTier tierFor(String itemName, Collection<String> npcNames)
	{
		String key = normalize(itemName);
		double best = -1;
		for (String npc : npcNames)
		{
			List<Drop> drops = get(npc);
			if (drops == null)
			{
				continue;
			}
			for (Drop d : drops)
			{
				if (normalize(d.getItemName()).equals(key) && d.getRate() > best)
				{
					best = d.getRate();
				}
			}
		}
		return best < 0 ? RarityTier.COMMON : RarityTier.fromRate(best);
	}

	/** Union of drop-table items across the given NPCs (cached tables only), keyed by lower name. */
	public Map<String, Drop> unionDrops(Collection<String> npcNames)
	{
		Map<String, Drop> union = new HashMap<>();
		Set<String> seen = new HashSet<>();
		for (String npc : npcNames)
		{
			if (!seen.add(normalize(npc)))
			{
				continue;
			}
			List<Drop> drops = get(npc);
			if (drops == null)
			{
				continue;
			}
			for (Drop d : drops)
			{
				String key = normalize(d.getItemName());
				Drop existing = union.get(key);
				if (existing == null || d.getRate() > existing.getRate())
				{
					union.put(key, d);
				}
			}
		}
		return union;
	}

	private File diskFile(String key)
	{
		return new File(CACHE_DIR, key.replaceAll("[^a-z0-9_-]", "_") + ".json");
	}

	private List<Drop> loadFromDisk(String key)
	{
		File f = diskFile(key);
		if (!f.exists())
		{
			return null;
		}
		try
		{
			String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			Type type = new TypeToken<List<Drop>>()
			{
			}.getType();
			return gson.fromJson(json, type);
		}
		catch (Exception e)
		{
			log.debug("Failed reading drops cache {}", f, e);
			return null;
		}
	}

	private void saveToDisk(String key, List<Drop> drops)
	{
		try
		{
			CACHE_DIR.mkdirs();
			Files.write(diskFile(key).toPath(), gson.toJson(drops).getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.debug("Failed writing drops cache for {}", key, e);
		}
	}

	public static String normalize(String s)
	{
		return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
	}
}
