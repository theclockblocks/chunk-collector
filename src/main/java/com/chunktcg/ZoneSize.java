package com.chunktcg;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ZoneSize
{
	REGION_64("64x64 region (Region Locker style)", 6),
	CHUNK_8("8x8 chunk (hardcore)", 3);

	private final String label;
	/** World coord right-shift: tiles per zone edge = 1 << shift. */
	private final int shift;

	@Override
	public String toString()
	{
		return label;
	}
}
