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

import com.rs.lib.model.Account;
import com.rs.lib.util.Logger;
import com.rs.lib.util.Utils;

import java.nio.ByteBuffer;

public final class OutputStream extends Stream {

	private static final int[] BIT_MASK = new int[32];
	private int opcodeStart = 0;

	static {
		for (int i = 0; i < 32; i++)
			BIT_MASK[i] = (1 << i) - 1;
	}

	public OutputStream(int capacity) {
		this.buffer = ByteBuffer.allocate(capacity);
	}

	public OutputStream() {
		this.buffer = ByteBuffer.allocate(16);
	}

	public OutputStream(byte[] buffer) {
		this.buffer = ByteBuffer.wrap(buffer);
		this.buffer.position(buffer.length);
	}

//	public OutputStream(int[] buffer) {
//		setBuffer(new byte[buffer.length]);
//		for (int value : buffer)
//			writeByte(value);
//	}

	public void ensureCapacity(int capacity) {
		while (capacity > buffer.remaining()) {
			int newCapacity = buffer.capacity() * 2;
			ByteBuffer old = buffer;
			old.flip();
			buffer = ByteBuffer.allocate(newCapacity);
			buffer.put(old);
		}
	}

	public void skip(int length) {
		ensureCapacity(length);
		setOffset(getOffset() + length);
	}

	public void setOffset(int offset) {
		this.buffer.position(offset);
	}

	public void writeBytes(byte[] b, int offset, int length) {
		ensureCapacity(length);
		buffer.put(b, offset, length);
	}

	public void writeBytes(byte[] b) {
		ensureCapacity(b.length);
		this.buffer.put(b);
	}

	public void addBytes128(byte[] data, int offset, int len) {
		for (int k = offset; k < len; k++)
			writeByte((byte) (data[k] + 128));
	}

	public void addBytesS(byte[] data, int offset, int len) {
		for (int k = offset; k < len; k++)
			writeByte((byte) (-128 + data[k]));
	}

	public void addBytes_Reverse(byte[] data, int offset, int len) {
		for (int i = len - 1; i >= 0; i--) {
			writeByte((data[i]));
		}
	}

	public void addBytes_Reverse128(byte[] data, int offset, int len) {
		for (int i = len - 1; i >= 0; i--) {
			writeByte((byte) (data[i] + 128));
		}
	}

	public void writeByte(byte i) {
		ensureCapacity(1);
		buffer.put(i);
	}

	public void writeByte(int i) {
		writeByte((byte) i);
	}

	public void writeNegativeByte(byte i) {
		ensureCapacity(1);
		buffer.put((byte) -i);

	}

	public void writeByte(byte i, int position) {
		buffer.put(position, i);
	}

	public void writeByteAdd(int i) {
		writeByte((byte) (i + 128));
	}

	public void writeByteInverse(int i) {
		writeByte((byte) -i);
	}

	public void writeByteSubtract(int i) {
		writeByte((byte) (128 - i));
	}

	public void writeShortAddLittle(int i) {
		writeByte((byte) (i + 128));
		writeByte((byte) (i >> 8));
	}

	public void writeShortAdd(int i) {
		writeByte((byte) (i >> 8));
		writeByte((byte) (i + 128));
	}
	
	public void writeSmartNS(int i) {
		if (i >= 128) {
			writeShort(i + 32769);
		} else {
			writeByte((byte) (i + 1));
		}
	}
	
	public void writeUnsignedSmart(int value) {
		if (value < 64 && value >= -64) {
			writeByte((byte) (value + 64));
			return;
		}
		if (value < 16384 && value >= -16384) {
			writeShort(value + 49152);
			return;
		} else {
			System.err.println("Error psmart out of range: " + value);
			return;
		}
	}

	public void writeBigSmart(int i) {
		if (i >= Short.MAX_VALUE)
			writeInt(i - Integer.MAX_VALUE - 1);
		else {
			writeShort(i >= 0 ? i : 32767);
		}
	}

	public void writeShort(int i) {
		writeByte((byte) (i >> 8));
		writeByte((byte) i);
	}

	public void writeShortLittle(int i) {
		writeByte((byte) i);
		writeByte((byte) (i >> 8));
	}

	public void writeMedium(int i) {
		writeByte((byte) (i >> 16));
		writeByte((byte) (i >> 8));
		writeByte((byte) i);
	}

	public void writeMediumReverseEnd(int i) {
		writeByte((byte) (i >> 16));
		writeByte((byte) i);
		writeByte((byte) (i >> 8));
	}

	public void writeInt(int i) {
		ensureCapacity(4);
		buffer.putInt(i);
	}

	public void writeIntMiddle(int i) {
		writeByte((byte) (i >> 8));
		writeByte((byte) i);
		writeByte((byte) (i >> 24));
		writeByte((byte) (i >> 16));
	}

	public void writeIntInverseMiddle(int i) {
		writeByte((byte) (i >> 16));
		writeByte((byte) (i >> 24));
		writeByte((byte) i);
		writeByte((byte) (i >> 8));
	}

	public void writeIntLittle(int i) {
		writeByte((byte) i);
		writeByte((byte) (i >> 8));
		writeByte((byte) (i >> 16));
		writeByte((byte) (i >> 24));
	}

	public void writeLong(long l) {
		ensureCapacity(8);
		buffer.putLong(l);
	}

	public void writePSmarts(int i) {
		if (i < 128) {
			writeByte((byte) i);
			return;
		}
		if (i < 32768) {
			writeShort(32768 + i);
			return;
		} else {
			Logger.error(OutputStream.class, "writePSmarts", "Error psmarts out of range: " + i);
			return;
		}
	}

	public void writeString(String string) {
		int i_2 = string.indexOf(0);
		if (i_2 >= 0) {
			throw new IllegalArgumentException("");
		} else {
			ensureCapacity(string.length() + 1);
			buffer.position(buffer.position() + Utils.writeString(string, 0, string.length(), buffer, buffer.position()));
			writeByte((byte) 0);
		}
	}

	public void writeGJString2(String string) {
		byte[] packed = new byte[256];
		int length = Utils.packGJString2(0, packed, string);
		writeByte((byte) 0);
		writeBytes(packed, 0, length);
		writeByte((byte) 0);
	}

	public void writeGJString(String s) {
		writeByte((byte) 0);
		writeString(s);
	}

	public void putGJString3(String s) {
		writeByte((byte) 0);
		writeString(s);
		writeByte((byte) 0);
	}

	public OutputStream writePacket(IsaacKeyPair isaac, int id, boolean worldPacket) {
		if (!worldPacket)
			writeSmart(id);
		else if (id >= 128) {
			if (isaac == null) {
				writeByte((byte) id);
				writeByte((byte) id);
				return this;
			}
			writeByte((byte) ((id >> 8) + 128 + isaac.outKey().nextInt()));
			writeByte((byte) (id + isaac.outKey().nextInt()));
		} else {
			if (isaac == null) {
				writeByte((byte) id);
				return this;
			}
			writeByte((byte) (id + isaac.outKey().nextInt()));
		}
		return this;
	}

	public void writePacketVarByte(IsaacKeyPair isaac, int id, boolean worldPacket) {
		writePacket(isaac, id, worldPacket);
		writeByte((byte) 0);
		opcodeStart = getOffset() - 1;
	}

	public void writePacketVarShort(IsaacKeyPair isaac, int id, boolean worldPacket) {
		writePacket(isaac, id, worldPacket);
		writeShort(0);
		opcodeStart = getOffset() - 2;
	}

	public void endPacketVarByte() {
		writeByte((byte) (getOffset() - (opcodeStart + 2) + 1), opcodeStart);
	}

	public void endPacketVarShort() {
		int size = getOffset() - (opcodeStart + 2);
		writeByte((byte) (size >> 8), opcodeStart++);
		writeByte((byte) size, opcodeStart);
	}

	public void initBitAccess() {
		bitPosition = getOffset() * 8;
	}

	public void finishBitAccess() {
		setOffset((bitPosition + 7) / 8);
	}

	public int getBitPos(int i) {
		return 8 * i - bitPosition;
	}

	public void writeBits(int numBits, int value) {
		int bytePos = bitPosition >> 3;
		buffer.position(bytePos);
		int bitOffset = 8 - (bitPosition & 7);
		bitPosition += numBits;
		for (; numBits > bitOffset; bitOffset = 8) {
			ensureCapacity(bytePos-buffer.position()+1);
			buffer.put(bytePos, (byte) (buffer.get(bytePos) & ~BIT_MASK[bitOffset]));
			buffer.put(bytePos, (byte) (buffer.get(bytePos) | (value >> numBits - bitOffset & BIT_MASK[bitOffset])));
			bytePos++;
			buffer.position(bytePos);
			numBits -= bitOffset;
		}
		ensureCapacity(bytePos-buffer.position()+1);
		if (numBits == bitOffset) {
			buffer.put(bytePos, (byte) (buffer.get(bytePos) & ~BIT_MASK[bitOffset]));
			buffer.put(bytePos, (byte) (buffer.get(bytePos) | (value & BIT_MASK[bitOffset])));
		} else {
			buffer.put(bytePos, (byte) (buffer.get(bytePos) & ~(BIT_MASK[numBits] << bitOffset - numBits)));
			buffer.put(bytePos, (byte) (buffer.get(bytePos) | ((value & BIT_MASK[numBits]) << bitOffset - numBits)));
		}
	}
	
	public void writeDisplayNameChat(Account account) {
		if (account.getPrevDisplayName() == null || account.getPrevDisplayName().isEmpty() || account.getPrevDisplayName().equals(account.getDisplayName())) {
			writeByte((byte) 0);
			writeString(account.getDisplayName());
		} else {
			writeByte((byte) 1);
			writeString(account.getDisplayName()); //this value would be a custom nickname or something client sided?
			writeString(account.getDisplayName());
		}
	}
	
	public void write5ByteInteger(long value) {
		writeByte((byte) (value >> 32));
		writeInt((int) (value & 0xffffffff));
	}

	public void writeSum(int i) {
		while (i >= 32767) {
			writeSmart(32767);
			i -= 32767;
		}
		writeSmart(i);
	}

	public void write24BitInt(int i) {
		writeByte((byte) (i >> 16));
		writeByte((byte) (i >> 8));
		writeByte((byte) i);
	}

	public void writeBoolean(boolean bool) {
		writeByte((byte) (bool ? 1 : 0));
	}

	public void writeSmart(int value) {
        if (value >= 128) {
            writeShort(value + 32768);
        } else {
            writeByte((byte) value);
        }
    }

	public void writeJagString(String string) {
		int i_3 = string.indexOf(0);
		if (i_3 >= 0)
			throw new IllegalArgumentException("");
		writeByte((byte) 0);
		write(string, string.length());
		writeByte((byte) 0);
	}

	public int write(CharSequence charsequence_0, int i_2) {
		int i_6 = i_2;
		for (int i_7 = 0; i_7 < i_6; i_7++) {
			char var_8 = charsequence_0.charAt(i_7);
			if (var_8 > 0 && var_8 < 128 || var_8 >= 160 && var_8 <= 255)
				writeByte((byte) var_8);
			else if (var_8 == 8364)
				writeByte((byte) -128);
			else if (var_8 == 8218)
				writeByte((byte) -126);
			else if (var_8 == 402)
				writeByte((byte) -125);
			else if (var_8 == 8222)
				writeByte((byte) -124);
			else if (var_8 == 8230)
				writeByte((byte) -123);
			else if (var_8 == 8224)
				writeByte((byte) -122);
			else if (var_8 == 8225)
				writeByte((byte) -121);
			else if (var_8 == 710)
				writeByte((byte) -120);
			else if (var_8 == 8240)
				writeByte((byte) -119);
			else if (var_8 == 352)
				writeByte((byte) -118);
			else if (var_8 == 8249)
				writeByte((byte) -117);
			else if (var_8 == 338)
				writeByte((byte) -116);
			else if (var_8 == 381)
				writeByte((byte) -114);
			else if (var_8 == 8216)
				writeByte((byte) -111);
			else if (var_8 == 8217)
				writeByte((byte) -110);
			else if (var_8 == 8220)
				writeByte((byte) -109);
			else if (var_8 == 8221)
				writeByte((byte) -108);
			else if (var_8 == 8226)
				writeByte((byte) -107);
			else if (var_8 == 8211)
				writeByte((byte) -106);
			else if (var_8 == 8212)
				writeByte((byte) -105);
			else if (var_8 == 732)
				writeByte((byte) -104);
			else if (var_8 == 8482)
				writeByte((byte) -103);
			else if (var_8 == 353)
				writeByte((byte) -102);
			else if (var_8 == 8250)
				writeByte((byte) -101);
			else if (var_8 == 339)
				writeByte((byte) -100);
			else if (var_8 == 382)
				writeByte((byte) -98);
			else if (var_8 == 376)
				writeByte((byte) -97);
			else
				writeByte((byte) 63);
		}
		return i_6;
	}
}