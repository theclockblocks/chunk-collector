package com.chunktcg;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ChunkTcgPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ChunkTcgPlugin.class);
		RuneLite.main(args);
	}
}
