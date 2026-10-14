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
package com.rs.cache.loaders.map;

import com.rs.cache.Cache;
import com.rs.cache.IndexType;
import com.rs.cache.loaders.ObjectType;
import com.rs.lib.game.WorldObject;
import com.rs.lib.io.InputStream;
import com.rs.lib.util.Logger;
import com.rs.lib.util.MapXTEAs;

import java.util.ArrayList;
import java.util.List;

public class Region {
    public static final int WIDTH = 64;
    public static final int HEIGHT = 64;

    protected int regionId;

    private List<WorldObject> objects = new ArrayList<>();
    private int[][][] clipFlags;
    private int[][][] heights;
    private int[][][] overlayIds;
    private int[][][] underlayIds;
    private byte[][][] overlayPathShapes;
    private byte[][][] overlayRotations;
    private byte[][][] renderFlags;
    private boolean hasData;
    private boolean xtea;

    public Region(int regionId) {
        this.regionId = regionId;
    }

    public int getBaseX() {
        return (regionId >> 8 & 0xFF) << 6;
    }

    public int getBaseY() {
        return (regionId & 0xFF) << 6;
    }

    public int getRegionId() {
        return regionId;
    }

    public void loadRegionMap(boolean loadExtraData) {
        loadMapTiles(loadExtraData);
        loadObjects();
    }

    public void loadMapTiles(boolean loadExtraData) {
        int regionX = (regionId >> 8) * 64;
        int regionY = (regionId & 0xff) * 64;
        int mapArchiveId = Cache.STORE.getIndex(IndexType.MAPS).getArchiveId("m" + ((regionX >> 3) / 8) + "_" + ((regionY >> 3) / 8));
        byte[] mapContainerData = mapArchiveId == -1 ? null : Cache.STORE.getIndex(IndexType.MAPS).getFile(mapArchiveId, 0);
        if (clipFlags == null)
            clipFlags = new int[4][64][64];
        renderFlags = mapContainerData == null ? null : new byte[4][64][64];
        if (loadExtraData) {
            heights = mapContainerData == null ? null : new int[4][64][64];
            overlayIds = mapContainerData == null ? null : new int[4][64][64];
            underlayIds = mapContainerData == null ? null : new int[4][64][64];
            overlayPathShapes = mapContainerData == null ? null : new byte[4][64][64];
            overlayRotations = mapContainerData == null ? null : new byte[4][64][64];
        }

        if (mapContainerData != null) {
            hasData = true;
            InputStream mapStream = new InputStream(mapContainerData);
            for (int plane = 0; plane < 4; plane++) {
                for (int x = 0; x < 64; x++) {
                    for (int y = 0; y < 64; y++) {
                        while (true) {
                            int value = mapStream.readUnsignedByte();
                            if (value == 0)
                                break;
                            if (value == 1) {
                                if (loadExtraData)
                                    heights[plane][x][y] = mapStream.readByte();
                                else
                                    mapStream.skip(1);
                                break;
                            }
                            if (value <= 49) {
                                if (loadExtraData) {
                                    int v = mapStream.readUnsignedByte();
                                    overlayIds[plane][x][y] = v;
                                    overlayPathShapes[plane][x][y] = (byte) ((value - 2) / 4);
                                    overlayRotations[plane][x][y] = (byte) ((value - 2) & 0x3);
                                } else
                                    mapStream.skip(1);
                            } else if (value <= 81) {
                                renderFlags[plane][x][y] = (byte) (value - 49);
                                if (RenderFlag.flagged(renderFlags[plane][x][y], RenderFlag.UNDER_ROOF))
                                    clipFlags[plane][x][y] |= ClipFlag.UNDER_ROOF.flag;
                            } else if (loadExtraData)
                                underlayIds[plane][x][y] = (value - 81);
                        }
                    }
                }
            }
            if (regionId != 11844) {
                for (int plane = 0; plane < 4; plane++) {
                    for (int x = 0; x < 64; x++) {
                        for (int y = 0; y < 64; y++) {
                            if (RenderFlag.flagged(renderFlags[plane][x][y], RenderFlag.CLIPPED)) {
                                int finalPlane = plane;
                                if (RenderFlag.flagged(renderFlags[1][x][y], RenderFlag.LOWER_OBJECTS_TO_OVERRIDE_CLIPPING))
                                    finalPlane--;
                                if (finalPlane >= 0)
                                    clipFlags[finalPlane][x][y] |= ClipFlag.PFBW_FLOOR.flag;
                            }
                        }
                    }
                }
            }
        } else
            for (int plane = 0; plane < 4; plane++)
                for (int x = 0; x < 64; x++)
                    for (int y = 0; y < 64; y++)
                        clipFlags[plane][x][y] |= ClipFlag.PFBW_FLOOR.flag;
    }

    public void loadObjects() {
        int regionX = (regionId >> 8) * 64;
        int regionY = (regionId & 0xff) * 64;
        int landArchiveId = Cache.STORE.getIndex(IndexType.MAPS).getArchiveId("l" + ((regionX >> 3) / 8) + "_" + ((regionY >> 3) / 8));
        byte[] landContainerData = landArchiveId == -1 ? null : Cache.STORE.getIndex(IndexType.MAPS).getFile(landArchiveId, 0, MapXTEAs.getMapKeys(regionId));

        if (clipFlags == null)
            clipFlags = new int[4][64][64];

        if (landContainerData != null) {
            xtea = true;
            InputStream landStream = new InputStream(landContainerData);
            int objectId = -1;
            int incr;
            while ((incr = landStream.readSmart2()) != 0) {
                objectId += incr;
                int location = 0;
                int incr2;
                while ((incr2 = landStream.readUnsignedSmart()) != 0) {
                    location += incr2 - 1;
                    int localX = (location >> 6 & 0x3f);
                    int localY = (location & 0x3f);
                    int plane = location >> 12;
                    int objectData = landStream.readUnsignedByte();
                    int type = objectData >> 2;
                    int rotation = objectData & 0x3;
                    if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64)
                        continue;
                    int objectPlane = plane;
                    if (renderFlags != null && (renderFlags[1][localX][localY] & 0x2) != 0)
                        objectPlane--;
                    if (objectPlane < 0 || objectPlane >= 4 || plane < 0 || plane >= 4)
                        continue;
                    objects.add(new WorldObject(objectId, ObjectType.forId(type), rotation, localX + regionX, localY + regionY, objectPlane));
                }
            }
        }
        if (landContainerData == null && landArchiveId != -1 && MapXTEAs.getMapKeys(regionId) != null)
            Logger.warn(Region.class, "loadRegionMap", "Missing xteas for region " + regionId + ".");
    }

    public void loadUnderwaterObjects() {
        int regionX = (regionId >> 8) * 64;
        int regionY = (regionId & 0xff) * 64;
        int uLandArchiveId = Cache.STORE.getIndex(IndexType.MAPS).getArchiveId("ul" + ((regionX >> 3) / 8) + "_" + ((regionY >> 3) / 8));
        byte[] uLandContainerData = uLandArchiveId == -1 ? null : Cache.STORE.getIndex(IndexType.MAPS).getFile(uLandArchiveId);
        if (uLandContainerData != null) {
            InputStream landStream = new InputStream(uLandContainerData);
            int objectId = -1;
            int incr;
            while ((incr = landStream.readSmart2()) != 0) {
                objectId += incr;
                int location = 0;
                int incr2;
                while ((incr2 = landStream.readUnsignedSmart()) != 0) {
                    location += incr2 - 1;
                    int localX = (location >> 6 & 0x3f);
                    int localY = (location & 0x3f);
                    int plane = location >> 12;
                    int objectData = landStream.readUnsignedByte();
                    int type = objectData >> 2;
                    int rotation = objectData & 0x3;
                    if (localX < 0 || localX >= 64 || localY < 0 || localY >= 64)
                        continue;
                    int objectPlane = plane;
                    if (renderFlags != null && (renderFlags[1][localX][localY] & 0x2) != 0)
                        objectPlane--;
                    if (objectPlane < 0 || objectPlane >= 4 || plane < 0 || plane >= 4)
                        continue;
                    objects.add(new WorldObject(objectId, ObjectType.forId(type), rotation, localX + regionX, localY + regionY, objectPlane));                }
            }
        }
    }

    public int getRotation(int plane, int localX, int localY) {
        return 0;
    }

    public final boolean isLinkedBelow(final int z, final int x, final int y) {
        return RenderFlag.flagged(getRenderFlags(z, x, y), RenderFlag.LOWER_OBJECTS_TO_OVERRIDE_CLIPPING);
    }

    public final boolean isVisibleBelow(final int z, final int x, final int y) {
        return RenderFlag.flagged(getRenderFlags(z, x, y), RenderFlag.FORCE_TO_BOTTOM);
    }

    public int getRenderFlags(int z, int x, int y) {
        return renderFlags != null ? renderFlags[z][x][y] : 0;
    }

    public int getUnderlayId(int z, int x, int y) {
        return underlayIds != null ? underlayIds[z][x][y] & 0x7fff : -1;
    }

    public int getOverlayId(int z, int x, int y) {
        return overlayIds != null ? overlayIds[z][x][y] & 0x7fff : -1;
    }

    public int getOverlayPathShape(int z, int x, int y) {
        return overlayPathShapes != null ? overlayPathShapes[z][x][y] & 0x7fff : -1;
    }

    public int getOverlayRotation(int z, int x, int y) {
        return overlayRotations != null ? overlayRotations[z][x][y] : -1;
    }

    public boolean hasData() {
        return hasData;
    }

    public boolean hasXtea() {
        return xtea;
    }

    public boolean isMissingXtea() {
        return hasData && !xtea;
    }

    public int[][][] getClipFlags() {
        return clipFlags;
    }

    public List<WorldObject> getObjects() {
        return objects;
    }

    public boolean checkXtea(int[] xteas) {
        int regionX = (regionId >> 8) * 64;
        int regionY = (regionId & 0xff) * 64;
        int landArchiveId = Cache.STORE.getIndex(IndexType.MAPS).getArchiveId("l" + ((regionX >> 3) / 8) + "_" + ((regionY >> 3) / 8));
        if (landArchiveId == -1)
            return false;
        byte[] data = Cache.STORE.getIndex(IndexType.MAPS).getFile(landArchiveId, 0, xteas);
        if (data == null)
            return false;
        return true;
    }
}
