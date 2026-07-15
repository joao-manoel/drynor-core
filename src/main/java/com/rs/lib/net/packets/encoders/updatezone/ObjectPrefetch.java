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

import com.rs.cache.loaders.ObjectType;
import com.rs.lib.game.Animation;
import com.rs.lib.game.WorldObject;
import com.rs.lib.io.OutputStream;

public class ObjectPrefetch extends UpdateZonePacketEncoder {

	private int objectId;
	private ObjectType type;

	public ObjectPrefetch(int objectId, ObjectType type) {
		super(UpdateZonePacket.OBJECT_PREFETCH, -1);
		this.objectId = objectId;
		this.type = type;
	}

	@Override
	public void encodeBody(OutputStream stream) {
		stream.writeInt(objectId);
		stream.writeByte(type.id);
	}

}
