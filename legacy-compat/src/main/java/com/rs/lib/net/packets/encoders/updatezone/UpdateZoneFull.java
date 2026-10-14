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
import com.rs.lib.net.ServerPacket;
import com.rs.lib.net.packets.PacketEncoder;
import com.rs.lib.util.MapUtils;
import com.rs.lib.util.MapUtils.Structure;

public class UpdateZoneFull extends PacketEncoder {
    private int baseChunkId;
    private int chunkId;

    public UpdateZoneFull(int baseChunkId, int chunkId) {
        super(ServerPacket.UPDATE_ZONE_FULL_FOLLOWS);
        this.baseChunkId = baseChunkId;
        this.chunkId = chunkId;
    }

    @Override
    public void encodeBody(OutputStream stream) {
        int[] base = MapUtils.decode(Structure.CHUNK, baseChunkId);
        int[] local = MapUtils.decode(Structure.CHUNK, chunkId);
        stream.writeByteSubtract(local[0] - base[0]);
        stream.writeByte(local[1] - base[1]);
        stream.writeByte(local[2]);
    }

}
