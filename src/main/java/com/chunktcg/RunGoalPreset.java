package com.chunktcg;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Classic win conditions from the locked-account community, plus Custom. */
@Getter
@RequiredArgsConstructor
public enum RunGoalPreset
{
	CUSTOM("Custom (type below)"),
	DRAGON_SLAYER("Complete Dragon Slayer I"),
	F2P_BOSSES("Defeat Obor & Bryophyta"),
	F2P_QUESTS("Complete every F2P quest"),
	FIRE_CAPE("Earn a Fire Cape (TzTok-Jad)"),
	BARROWS_GLOVES("Barrows Gloves (Recipe for Disaster)"),
	QUEST_CAPE("Quest Point Cape"),
	TOTAL_1000("Reach total level 1,000"),
	ZONES_10("Unlock 10 zones"),
	ZONES_25("Unlock 25 zones"),
	FULL_CLEAR_5("100% complete 5 zones");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
