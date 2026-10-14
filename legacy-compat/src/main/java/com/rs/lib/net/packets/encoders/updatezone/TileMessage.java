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

import com.rs.lib.io.OutputStream;

public class TileMessage extends UpdateZonePacketEncoder {
	private String message;
	private int delay, height, color;
	
	public TileMessage(int chunkLocalHash, String message, int delay, int height, int color) {
		super(UpdateZonePacket.TILE_MESSAGE, chunkLocalHash);
		this.message = message;
		this.delay = delay;
		this.height = height;
		this.color = color;
	}

	@Override
	public void encodeBody(OutputStream stream) {
		stream.skip(1);
		stream.writeByte(chunkLocalHash);
		stream.writeShort(delay / 30);
		stream.writeByte(height);
		stream.writeMedium(color);
		stream.writeString(message);
	}

}
