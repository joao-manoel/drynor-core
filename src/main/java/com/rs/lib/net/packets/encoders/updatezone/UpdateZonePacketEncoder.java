package com.rs.lib.net.packets.encoders.updatezone;

import com.rs.lib.net.packets.PacketEncoder;

public abstract class UpdateZonePacketEncoder extends PacketEncoder {
    protected UpdateZonePacket updateZonePacket;
    protected int chunkLocalHash;

    public UpdateZonePacketEncoder(UpdateZonePacket packet, int chunkLocalHash) {
        super(packet.getServerPacket());
        this.updateZonePacket = packet;
        this.chunkLocalHash = chunkLocalHash;
    }

    public UpdateZonePacket getUpdateZonePacket() {
        return updateZonePacket;
    }
}
