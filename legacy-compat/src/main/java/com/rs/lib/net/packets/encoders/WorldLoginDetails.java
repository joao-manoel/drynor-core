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
package com.rs.lib.net.packets.encoders;

import com.rs.lib.io.OutputStream;
import com.rs.lib.net.ServerPacket;
import com.rs.lib.net.packets.PacketEncoder;

public class WorldLoginDetails extends PacketEncoder {

	private int rights;
	private int pid;
	private String displayName;
	private int modLevel;
	private boolean verifiedEmail;
	private boolean membersWorld;
	
	public WorldLoginDetails(int rights, int pid, String displayName, int modLevel, boolean verifiedEmail, boolean membersWorld) {
		super(ServerPacket.WORLD_LOGIN_DETAILS);
		this.rights = rights;
		this.pid = pid;
		this.displayName = displayName;
		this.modLevel = modLevel;
		this.verifiedEmail = verifiedEmail;
		this.membersWorld = membersWorld;
	}

	@Override
	public void encodeBody(OutputStream stream) {
		stream.writeByte(rights);
		stream.writeByte(modLevel); //modlevel
		stream.writeByte(0); //qc only
		stream.writeByte(verifiedEmail ? 1 : 0); //verfied email
		stream.writeByte(1); //
		stream.writeByte(0); //qc only
		stream.writeShort(pid);
		stream.writeByte(1); //is member
		stream.writeMedium(0);
		stream.writeByte(membersWorld ? 1 : 0); // is member world
		stream.writeString(displayName);
	}

}
