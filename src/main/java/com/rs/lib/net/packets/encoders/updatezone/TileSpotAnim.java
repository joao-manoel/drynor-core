package com.rs.lib.net.packets.encoders.updatezone;

import com.rs.lib.game.SpotAnim;
import com.rs.lib.io.OutputStream;

public class TileSpotAnim extends UpdateZonePacketEncoder {

    private SpotAnim spotAnim;

    public TileSpotAnim(int chunkLocalHash, SpotAnim spotAnim) {
        super(UpdateZonePacket.SPOT_ANIM, chunkLocalHash);
        this.spotAnim = spotAnim;
    }

    @Override
    public void encodeBody(OutputStream stream) {
        stream.writeByte(chunkLocalHash);
        stream.writeShort(spotAnim.getId());
        stream.writeShort(spotAnim.getHeight());
        stream.writeShort(spotAnim.getSpeed());
        stream.writeByte(spotAnim.getRotation());
    }
}
