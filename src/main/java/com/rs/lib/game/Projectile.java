// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
// 
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
// 
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
//  Copyright (C) 2021 Trenton Kress
//  This file is part of project: Darkan
//
package com.rs.lib.game;

import kotlin.Pair;

public class Projectile {
	
	protected Tile from, to;
	protected int sourceId;
	protected int lockOnId;
	private boolean useTerrainHeight;
	private int halfSqStartOffsetX, halfSqStartOffsetY;
	private int halfSqEndOffsetX, halfSqEndOffsetY;
	private int spotAnimId, startHeight, endHeight, startDelayClientCycles, inAirClientCycles, offset, angle;
	private int basFrameHeightAdjust = -1;
	
	public Projectile(Tile from, int sourceId, Tile to, int lockOnId, int spotAnimId, int startHeight, int endHeight, int startDelayClientCycles, int inAirClientCycles, int offset, int angle) {
		this.sourceId = sourceId;
		this.from = from;
		this.lockOnId = lockOnId;
		this.to = to;
		this.spotAnimId = spotAnimId;
		this.startHeight = startHeight;
		this.endHeight = endHeight;
		this.startDelayClientCycles = startDelayClientCycles;
		this.inAirClientCycles = inAirClientCycles;
		this.offset = offset;
		this.angle = angle;
	}

	public Projectile(Tile from, int sourceId, Tile to, int spotAnimId, int startHeight, int endHeight, int startDelayClientCycles, int inAirClientCycles, int offset, int angle) {
		this(from, sourceId, to, -1, spotAnimId, startHeight, endHeight, startDelayClientCycles, inAirClientCycles, offset, angle);
	}

	public Projectile(Tile from, Tile to, int lockOnId, int spotAnimId, int startHeight, int endHeight, int startDelayClientCycles, int inAirClientCycles, int offset, int angle) {
		this(from, -1, to, lockOnId, spotAnimId, startHeight, endHeight, startDelayClientCycles, inAirClientCycles, offset, angle);
	}

	public Projectile(Tile from, Tile to, int spotAnimId, int startHeight, int endHeight, int startDelayClientCycles, int inAirClientCycles, int offset, int angle) {
		this(from, -1, to, -1, spotAnimId, startHeight, endHeight, startDelayClientCycles, inAirClientCycles, offset, angle);
	}
	
	public Projectile setBASFrameHeightAdjust(int frameIndex) {
		basFrameHeightAdjust = frameIndex;
		return this;
	}
	
	public int getBASFrameHeightAdjust() {
		return basFrameHeightAdjust;
	}
	
	public Projectile setUseTerrainHeight() {
		useTerrainHeight = true;
		return this;
	}

	public Projectile setHalfSqStartOffsets(Pair<Integer, Integer> xy) {
		this.halfSqStartOffsetX = xy.getFirst();
		this.halfSqStartOffsetY = xy.getSecond();
		return this;
	}

	public Projectile setHalfSqEndOffsets(Pair<Integer, Integer> xy) {
		this.halfSqEndOffsetX = xy.getFirst();
		this.halfSqEndOffsetY = xy.getSecond();
		return this;
	}

	public boolean usesTerrainHeight() {
		return useTerrainHeight;
	}

	public int getSpotAnimId() {
		return spotAnimId;
	}

	public int getStartHeight() {
		return startHeight;
	}

	public int getEndHeight() {
		return endHeight;
	}

	public int getHalfSqStartOffsetX() {
		return halfSqStartOffsetX;
	}

	public int getHalfSqStartOffsetY() {
		return halfSqStartOffsetY;
	}

	public int getHalfSqEndOffsetX() {
		return halfSqEndOffsetX;
	}

	public int getHalfSqEndOffsetY() {
		return halfSqEndOffsetY;
	}

	public int getStartDelayClientCycles() {
		return startDelayClientCycles;
	}

	public int getInAirClientCycles() {
		return inAirClientCycles;
	}

	public int getOffset() {
		return offset;
	}

	public int getAngle() {
		return angle;
	}

	public int getSourceId() {
		return sourceId;
	}

	public int getLockOnId() {
		return lockOnId;
	}
	
	public Tile getSource() {
		return from;
	}
	
	public Tile getDestination() {
		return to;
	}

}
