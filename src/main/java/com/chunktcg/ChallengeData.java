package com.chunktcg;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Community challenges per map region, crowd-sourced by the chunk-locked
 * community via Chunk Picker. Keyed by RS region id.
 */
@Slf4j
@Singleton
public class ChallengeData
{
	@Data
	public static class Challenge
	{
		private String n;
		private String s;
		private String c;
		private Integer l;

		public String display()
		{
			StringBuilder sb = new StringBuilder();
			if (s != null && !s.equals("Nonskill"))
			{
				sb.append(s);
				if (l != null)
				{
					sb.append(" ").append(l);
				}
				sb.append(": ");
			}
			sb.append(n);
			return sb.toString();
		}
	}

	@Inject
	private Gson gson;

	private Map<String, List<Challenge>> byRegion;

	public List<Challenge> forRegion(int rsRegionId)
	{
		List<Challenge> list = data().get(String.valueOf(rsRegionId));
		return list == null ? Collections.emptyList() : list;
	}

	private synchronized Map<String, List<Challenge>> data()
	{
		if (byRegion == null)
		{
			try (InputStream in = ChallengeData.class.getResourceAsStream("/challenges.json"))
			{
				if (in == null)
				{
					byRegion = Collections.emptyMap();
				}
				else
				{
					Type t = new TypeToken<Map<String, List<Challenge>>>()
					{
					}.getType();
					byRegion = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), t);
				}
			}
			catch (Exception e)
			{
				log.debug("Failed loading challenges", e);
				byRegion = Collections.emptyMap();
			}
		}
		return byRegion;
	}
}
