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
package com.rs.lib.io;

import java.nio.ByteBuffer;
import java.util.Arrays;

public abstract class Stream {
	protected ByteBuffer buffer;
	protected int bitPosition;

	public int getLength() {
		return buffer.capacity();
	}

	public byte[] getBuffer() {
		return buffer.array();
	}

	public byte[] toByteArray() {
		int len = buffer.position();
		byte[] arr = new byte[len];
		buffer.position(0);
		buffer.get(arr, 0, len);
		buffer.position(len);
		return arr;
	}

	public int getOffset() {
		return buffer.position();
	}

	public void decodeXTEA(int keys[]) {
		decodeXTEA(keys, 5, buffer.capacity());
	}

	public void decodeXTEA(int keys[], int start, int end) {
		int origPos = buffer.position();
		buffer.position(start);
		int i1 = (end - start) / 8;
		for (int j1 = 0; j1 < i1; j1++) {
			int k1 = readInt();
			int l1 = readInt();
			int sum = 0xc6ef3720;
			int delta = 0x9e3779b9;
			for (int k2 = 32; k2-- > 0;) {
				l1 -= keys[(sum & 0x1c84) >>> 11] + sum ^ (k1 >>> 5 ^ k1 << 4) + k1;
				sum -= delta;
				k1 -= (l1 >>> 5 ^ l1 << 4) + l1 ^ keys[sum & 3] + sum;
			}
			buffer.position(buffer.position()-8);
			writeInt(k1);
			writeInt(l1);
		}
		buffer.position(origPos);
	}

	public final void encodeXTEA(int keys[], int start, int end) {
		int o = buffer.position();
		int j = (end - start) / 8;
		buffer.position(start);
		for (int k = 0; k < j; k++) {
			int l = readInt();
			int i1 = readInt();
			int sum = 0;
			int delta = 0x9e3779b9;
			for (int l1 = 32; l1-- > 0;) {
				l += sum + keys[3 & sum] ^ i1 + (i1 >>> 5 ^ i1 << 4);
				sum += delta;
				i1 += l + (l >>> 5 ^ l << 4) ^ keys[(0x1eec & sum) >>> 11] + sum;
			}
			buffer.position(buffer.position()-8);
			writeInt(l);
			writeInt(i1);
		}
		buffer.position(o);
	}

	private final int readInt() {
		return buffer.getInt();
	}

	private final void writeInt(int value) {
		buffer.putInt(value);
	}

	public final void getBytes(byte data[], int off, int len) {
		buffer.get(data, off, len);
	}

	public int read24BitInt() {
		return (readUnsignedByte() << 16) + (readUnsignedByte() << 8) + (readUnsignedByte());
	}

	public int readUnsignedByte() {
		return readByte() & 0xff;
	}

	public byte readByte() {
		return buffer.get();
	}

	public int getRemaining() {
		return buffer.remaining();
	}

}
