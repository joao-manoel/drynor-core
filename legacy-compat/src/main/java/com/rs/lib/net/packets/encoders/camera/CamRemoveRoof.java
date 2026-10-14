package com.rs.lib.net.packets.encoders.camera;

import com.rs.lib.io.OutputStream;
import com.rs.lib.net.ServerPacket;
import com.rs.lib.net.packets.PacketEncoder;

public class CamRemoveRoof extends PacketEncoder {
    private int localX, localY;

    public CamRemoveRoof(int localX, int localY) {
        super(ServerPacket.CAM_REMOVEROOF);
        this.localX = localX;
        this.localY = localY;
    }

    @Override
    public void encodeBody(OutputStream stream) {
        stream.writeIntLittle(localY + (localX << 14));
    }
}
