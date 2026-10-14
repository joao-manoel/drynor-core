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
package com.rs.lib.net.packets.encoders.updatezone;

import com.rs.lib.game.Projectile;
import com.rs.lib.io.OutputStream;

public class ProjAnim extends UpdateZonePacketEncoder {
	
	private Projectile proj;

	public ProjAnim(int chunkLocalHash, Projectile proj) {
		super(UpdateZonePacket.MAP_PROJANIM, chunkLocalHash);
		this.proj = proj;
	}
	
	@Override
	public void encodeBody(OutputStream stream) {
		int flags = ((proj.getSource().getXInChunk() << 3) | proj.getSource().getYInChunk());
		if (proj.usesTerrainHeight())
			flags |= 0x80;
		stream.writeByte(flags);
		int xOff = proj.getDestination().getX() - proj.getSource().getX();
		int yOff = proj.getDestination().getY() - proj.getSource().getY();
		stream.writeByte(xOff);
		stream.writeByte(yOff);
		stream.writeShort(proj.getLockOnId());
		stream.writeShort(proj.getSpotAnimId());
		stream.writeByte(proj.getStartHeight());
		stream.writeByte(proj.getEndHeight());
		stream.writeShort(proj.getStartDelayClientCycles());
		stream.writeShort(proj.getStartDelayClientCycles() + proj.getInAirClientCycles());
		stream.writeByte(proj.getAngle());
		stream.writeShort(proj.getOffset());
	}

//	private int offset(int tile, int bit) {
//		int part1 = ((tile >> 14) & 0x3fff) % 8;
//		int part2 = (tile & 0x3fff) % 8;
//		return (part1 << bit) | part2;
//	}
}
