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
package com.rs.lib.io;

import com.rs.lib.util.Utils;

import java.nio.ByteBuffer;

public final class InputStream extends Stream {

	public void initBitAccess() {
		bitPosition = buffer.position() * 8;
	}

	private static final int[] BIT_MASK = new int[] { 0, 1, 3, 7, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767, 65535,
			131071, 262143, 524287, 1048575, 2097151, 4194303, 8388607, 16777215, 33554431, 67108863, 134217727, 268435455,
			536870911, 1073741823, 2147483647, -1 };

	public void finishBitAccess() {
		buffer.position((7 + bitPosition) / 8);
	}

	public int readBits(int bitOffset) {
		int bytePos = bitPosition >> 1779819011;
		int i_8_ = -(0x7 & bitPosition) + 8;
		bitPosition += bitOffset;
		int value = 0;
		for (/**/; (bitOffset ^ 0xffffffff) < (i_8_ ^ 0xffffffff); i_8_ = 8) {
			value += (BIT_MASK[i_8_] & buffer.get(bytePos++)) << -i_8_ + bitOffset;
			bitOffset -= i_8_;
		}
		if ((i_8_ ^ 0xffffffff) == (bitOffset ^ 0xffffffff))
			value += buffer.get(bytePos) & BIT_MASK[i_8_];
		else
			value += (buffer.get(bytePos) >> -bitOffset + i_8_ & BIT_MASK[bitOffset]);
		return value;
	}

	public InputStream(int capacity) {
		buffer = ByteBuffer.allocate(capacity);
	}

	public InputStream(byte[] buffer) {
		this.buffer = ByteBuffer.wrap(buffer);
	}

	public void ensureCapacity(int capacity) {
		while (capacity > buffer.remaining()) {
			int newCap = buffer.capacity() * 2;
			ByteBuffer old = buffer;
			old.flip();
			buffer = ByteBuffer.allocate(newCap);
			buffer.put(old);
		}
	}

	public int read24BitInt() {
		return (readUnsignedByte() << 16) + (readUnsignedByte() << 8) + (readUnsignedByte());
	}

	public void skip(int length) {
		buffer.position(buffer.position()+length);
	}

	public void setOffset(int offset) {
		buffer.position(offset);
	}

	public void decOffset(int amt) {
		buffer.position(buffer.position()-amt);
	}

	public void incOffset(int amt) {
		buffer.position(buffer.position()+amt);
	}

	public void addBytes(byte[] b, int offset, int length) {
		ensureCapacity(length);
		buffer.put(b, offset, length);
//		System.arraycopy(b, offset, buffer, this.offset, length);
//		this.length += length - offset;
	}

	public int peekPacket(IsaacKeyPair isaac) {
		return (peekUnsignedByte() - isaac.inKey().peek()) & 0xff;
	}

	public int readPacket(IsaacKeyPair isaac) {
		return (readUnsignedByte() - isaac.inKey().nextInt()) & 0xff;
	}

	public int readSmartPacket(IsaacKeyPair isaac) {
		int opcode = readUnsignedByte() - isaac.inKey().nextInt() & 0xff;
		return opcode < 128 ? opcode : (opcode - 128 << 8) + (readUnsignedByte() - isaac.inKey().nextInt() & 0xff);
	}

	public int readSmart() {
		int i_2 = peekByte() & 0xFF;
		return i_2 < 128 ? readUnsignedByte() : readUnsignedShort() - 32768;
	}

	public int read24BitUnsignedInteger() {
		setOffset(getOffset()+3);
		return ((buffer.get(getOffset()-3) & 0xff) << 16) + (buffer.get(getOffset()-1) & 0xff) + ((buffer.get(getOffset()-2) & 0xff) << 8);
	}

	public int peekUnsignedByte() {
		return peekByte() & 0xff;
	}

	public int peekByte() {
		return getRemaining() > 0 ? buffer.get(buffer.position()) : 0;
	}

	public byte readByte() {
		return getRemaining() > 0 ? buffer.get() : 0;
	}

	public void readBytes(byte buffer[], int off, int len) {
		for (int k = off; k < len + off; k++) {
			buffer[k] = (byte) readByte();
		}
	}

	public void readBytes(byte buffer[]) {
		readBytes(buffer, 0, buffer.length);
	}

	public int readSmart2() {
		int i = 0;
		int i_33_ = readUnsignedSmart();
		while (i_33_ == 32767) {
			i_33_ = readUnsignedSmart();
			i += 32767;
		}
		i += i_33_;
		return i;
	}

	public int readUnsignedByte() {
		return readByte() & 0xff;
	}

	public int readByteAdd() {
		return (byte) (readByte() - 128);
	}

	public int readByteInverse() {
		return (byte) -readByte();
	}

	public int readByteSubtract() {
		return (byte) (128 - readByte());
	}

	public int readUnsignedByte128() {
		return readUnsignedByte() - 128 & 0xff;
	}

	public int readUByteInverse() {
		return -readUnsignedByte() & 0xff;
	}

	public int readUnsignedByteSubtract() {
		return 128 - readUnsignedByte() & 0xff;
	}

	public int readShortLittle() {
		int i = readUnsignedByte() + (readUnsignedByte() << 8);
		if (i > 32767) {
			i -= 0x10000;
		}
		return i;
	}

	public int readShortAdd() {
		int i = (readUnsignedByte() << 8) + (readByte() - 128 & 0xff);
		if (i > 32767) {
			i -= 0x10000;
		}
		return i;
	}

	public int readShortAddLittle() {
		int i = (readByte() - 128 & 0xff) + (readUnsignedByte() << 8);
		if (i > 32767) {
			i -= 0x10000;
		}
		return i;
	}

	public int read128ShortLE() {
		int i = (128 - readByte() & 0xff) + (readUnsignedByte() << 8);
		if (i > 32767) {
			i -= 0x10000;
		}
		return i;
	}

	public int readShort() {
		int i = (readUnsignedByte() << 8) + readUnsignedByte();
		if (i > 32767) {
			i -= 65536;
		}
		return i;
	}

	public int readUnsignedShortLE() {
		return readUnsignedByte() + (readUnsignedByte() << 8);
	}

	public int readUnsignedShort() {
		return (readUnsignedByte() << 8) + readUnsignedByte();
	}

	// @SuppressWarnings("unused")
	public int readBigSmart() {
		/*
		 * if(Settings.CLIENT_BUILD < 670) return readUnsignedShort();
		 */
		if ((peekByte() ^ 0xffffffff) <= -1) {
			int value = readUnsignedShort();
			if (value == 32767) {
				return -1;
			}
			return value;
		}
		return readInt() & 0x7fffffff;
	}

	public int readUnsignedShortAdd() {
		return (readUnsignedByte() << 8) + (readByte() - 128 & 0xff);
	}

	public int readUnsignedShortAddLittle() {
		return (readByte() - 128 & 0xff) + (readUnsignedByte() << 8);
	}

	public int readInt() {
		return buffer.getInt();
	}

	public int readMiddle() {
		return (readUnsignedByte() << 16) + (readUnsignedByte() << 8) + readUnsignedByte();
	}

	public int readUnsignedIntMiddle() {
		return (readUnsignedByte() << 8) + readUnsignedByte() + (readUnsignedByte() << 24) + (readUnsignedByte() << 16);
	}

	public int readIntInverseMiddle() {
		return (readUnsignedByte() << 16) + (readUnsignedByte() << 24) + readUnsignedByte() + (readUnsignedByte() << 8);
	}

	public int readUnsignedIntLittle() {
		return readUnsignedByte() + (readUnsignedByte() << 8) + (readUnsignedByte() << 16) + (readUnsignedByte() << 24);
	}

	public long readLong() {
		return buffer.getLong();
	}

	public String readNoSpecString() {
		StringBuilder s = new StringBuilder();
		int b;
		while ((b = readByte()) != 0)
			s.append((char) b);
		return s.toString();
	}

	public String readString() {
		int start = getOffset();
		while (buffer.get() != 0) { }
		int length = getOffset() - start - 1;
		return length == 0 ? "" : Utils.readString(this.buffer, start, length);
	}

	public String readJagString() {
		readByte();
		String s = "";
		int b;
		while ((b = readByte()) != 0) {
			s += (char) b;
		}
		return s;
	}

	public String readGJString() {
        byte b = buffer.get();
        if (b != 0) {
            throw new IllegalStateException("");
        } else {
            int idx = getOffset();
            while (buffer.get() != 0) { }
            int i_4 = getOffset() - idx - 1;
            return i_4 == 0 ? "" : Utils.readString(this.buffer, idx, i_4);
        }
	}

	public final int readSignedSmart() {
		int v = peekByte() & 0xff;
		if (v < 128)
			return readUnsignedByte() - 64;
		return readUnsignedShort() - 49152;
	}

	public int readUnsignedSmart() {
		int i = peekByte() & 0xff;
		if (i >= 128) {
			return readUnsignedShort() - 32768;
		}
		return readUnsignedByte();
	}

	public int readUnsignedSmartNegOne() {
		int i = peekByte() & 0xff;
		if (i >= 128) {
			return readUnsignedShort() - 32769;
		}
		return readUnsignedByte() - 1;
	}

	public String readNullString() {
		if (peekByte() == 0) {
			this.setOffset(getOffset()+1);
			return null;
		}
		return readString();
	}

	public long readSized(int size) {
		--size;
        if (size >= 0 && size <= 7) {
            int bitSize = size * 8;
            long val;
            for (val = 0L; bitSize >= 0; bitSize -= 8) {
                val |= ((long) buffer.get() & 0xffL) << bitSize;
            }
            return val;
        } else {
            throw new IllegalArgumentException();
        }
	}

	public float readFloat() {
		return Float.intBitsToFloat(readInt());
	}
}