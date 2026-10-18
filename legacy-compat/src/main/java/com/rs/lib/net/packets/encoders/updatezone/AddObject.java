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
//  This file is part of project: Drynor
//
package com.rs.lib.net.packets.encoders.updatezone;

import com.rs.lib.game.WorldObject;
import com.rs.lib.io.OutputStream;

public class AddObject extends UpdateZonePacketEncoder {
	
	private WorldObject object;

	public AddObject(int chunkLocalHash, WorldObject object) {
		super(UpdateZonePacket.CREATE_OBJECT, chunkLocalHash);
		this.object = object;
	}

	@Override
	public void encodeBody(OutputStream stream) {
		stream.writeInt(object.getId());
		stream.writeByteSubtract(chunkLocalHash);
		stream.writeByteSubtract((object.getType().id << 2) + (object.getRotation() & 0x3));
	}

}
