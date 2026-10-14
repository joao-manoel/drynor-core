package com.rs.lib.net.packets.encoders.updatezone;

import com.rs.lib.game.GroundItem;
import com.rs.lib.io.OutputStream;

public class RevealGroundItem extends UpdateZonePacketEncoder {
    private GroundItem item;
    private int pid;

    public RevealGroundItem(int chunkLocalHash, GroundItem item, int pid) {
        super(UpdateZonePacket.GROUND_ITEM_REVEAL, chunkLocalHash);
        this.item = item;
        this.pid = pid;
    }

    @Override
    public void encodeBody(OutputStream stream) {
        stream.writeShort(pid);
        stream.writeByte(chunkLocalHash);
        stream.writeShortAddLittle(item.getAmount());
        stream.writeShortAddLittle(item.getId());
    }
}
