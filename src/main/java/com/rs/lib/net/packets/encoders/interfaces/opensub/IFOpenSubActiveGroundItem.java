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
package com.rs.lib.net.packets.encoders.interfaces.opensub;

import com.rs.lib.game.GroundItem;
import com.rs.lib.io.OutputStream;
import com.rs.lib.net.ServerPacket;
import com.rs.lib.net.packets.PacketEncoder;

public class IFOpenSubActiveGroundItem extends PacketEncoder {

	private boolean overlay;
	private int topId;
	private int topChildId;
	private int subId;
	private GroundItem item;
	
	public IFOpenSubActiveGroundItem(int topId, int topChildId, int subId, boolean overlay, GroundItem item) {
		super(ServerPacket.IF_OPENSUB_ACTIVE_GROUNDITEM);
		this.topId = topId;
		this.topChildId = topChildId;
		this.subId = subId;
		this.overlay = overlay;
		this.item = item;
	}
	
	@Override
	public void encodeBody(OutputStream stream) {
		int[] xteas = new int[4];
		int topUid = topId << 16 | topChildId;
		
		stream.writeIntInverseMiddle(topUid);
		stream.writeIntLittle(xteas[2]);
		stream.writeShortLittle(subId);
		stream.writeInt(xteas[3]);
		stream.writeIntMiddle(xteas[0]);
		stream.writeByteSubtract(overlay ? 1 : 0);
		stream.writeIntMiddle((item.getTile().getPlane() << 28) | (item.getTile().getX() << 14) | item.getTile().getY());
		stream.writeIntMiddle(xteas[1]);
		stream.writeShortAddLittle(item.getId());
	}

}
