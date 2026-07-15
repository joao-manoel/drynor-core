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
package com.rs.cache.loaders;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.rs.cache.Cache;
import com.rs.cache.IndexType;
import com.rs.lib.io.InputStream;
import com.rs.lib.util.Utils;

public class FontMetrics {
	
	private static final HashMap<Integer, FontMetrics> CACHE = new HashMap<>();
	
	private static int p11Full, p12Full, b12Full;
	
	public int id;
	byte[] glyphWidths;
	public int anInt4975;
	public int anInt4978;
	public int anInt4979;
	byte[][] kerning;
	
	public static void main(String[] args) throws IOException {
		//Cache.init();
		
		p11Full = Cache.STORE.getIndex(IndexType.NORMAL_FONTS).getArchiveId("p11_full");
		p12Full = Cache.STORE.getIndex(IndexType.NORMAL_FONTS).getArchiveId("p12_full");
		b12Full = Cache.STORE.getIndex(IndexType.NORMAL_FONTS).getArchiveId("b12_full");
		
		System.out.println(p11Full);
		System.out.println(p12Full);
		System.out.println(b12Full);
		System.out.println(get(p11Full));
		System.out.println(get(p12Full));
		System.out.println(get(b12Full));
		
//		for (int i = 0;i < Cache.STORE.getIndex(IndexType.FONT_METRICS).getLastArchiveId()+1;i++) {
//			FontMetrics defs = get(i);
//			System.out.println(defs);
//		}
	}
	
	public static final FontMetrics get(int id) {
		FontMetrics defs = CACHE.get(id);
		if (defs != null)
			return defs;
		
		byte[] data = Cache.STORE.getIndex(IndexType.FONT_METRICS).getFile(id);
		
		defs = new FontMetrics();
		defs.id = id;
		if (data != null)
			defs.decode(new InputStream(data));
		CACHE.put(id, defs);
		return defs;
	}

	public String[] splitTextIntoLines(String text, int[] lineWidths, boolean useHyphenSplit) {
		List<String> lines = new ArrayList<>();
		if (text == null)
			return new String[0];

		int currentLineWidth = 0;
		int startingCharIndexForLine = 0;
		int lastSpaceCharIndex = -1;
		int lineWidthAtLastSpace = 0;
		int hyphenSplitWidth = 0;
		int tagStartIndex = -1;
		int previousCharCode = -1;
		int textLength = text.length();

		for (int currentCharIndex = 0; currentCharIndex < textLength; currentCharIndex++) {
			int currentCharCode = getByteForChar(text.charAt(currentCharIndex)) & 0xff;
			int charWidth = 0;

			if (currentCharCode == 60) // '<'
				tagStartIndex = currentCharIndex;
			else {
				int splitIndex;
				if (tagStartIndex != -1) {
					if (currentCharCode != 62) // '>'
						continue;

					splitIndex = tagStartIndex;
					String tagText = text.substring(tagStartIndex + 1, currentCharIndex);
					tagStartIndex = -1;

					if ("br".equals(tagText)) {
						lines.add(text.substring(startingCharIndexForLine, currentCharIndex + 1));
						startingCharIndexForLine = currentCharIndex + 1;
						currentLineWidth = 0;
						lastSpaceCharIndex = -1;
						previousCharCode = -1;
						continue;
					}

					charWidth += computeTagWidth(tagText, previousCharCode);
					currentCharCode = -1;
				} else {
					splitIndex = currentCharIndex;
					charWidth += getGlyphWidth(currentCharCode);
					if (kerning != null && previousCharCode != -1)
						charWidth += kerning[previousCharCode][currentCharCode];

					previousCharCode = currentCharCode;
				}

				if (text.charAt(currentCharIndex) == '\n') {
					lines.add(text.substring(startingCharIndexForLine, splitIndex));
					startingCharIndexForLine = splitIndex;
					lastSpaceCharIndex = -1;
					currentLineWidth = charWidth;
				}
				if (charWidth > 0) {
					currentLineWidth += charWidth;
					if (lineWidths != null) {
						if (currentCharCode == 32) { // space character
							lastSpaceCharIndex = currentCharIndex;
							lineWidthAtLastSpace = currentLineWidth;
							hyphenSplitWidth = useHyphenSplit ? 1 : 0;
						}

						if (currentLineWidth > lineWidths[lines.size() < lineWidths.length ? lines.size() : lineWidths.length - 1]) {
							if (lastSpaceCharIndex >= 0) {
								lines.add(text.substring(startingCharIndexForLine, lastSpaceCharIndex + 1 - hyphenSplitWidth));
								startingCharIndexForLine = lastSpaceCharIndex + 1;
								lastSpaceCharIndex = -1;
								currentLineWidth -= lineWidthAtLastSpace;
							} else {
								lines.add(text.substring(startingCharIndexForLine, splitIndex));
								startingCharIndexForLine = splitIndex;
								lastSpaceCharIndex = -1;
								currentLineWidth = charWidth;
							}
							previousCharCode = -1;
						}

						if (currentCharCode == 45) { // '-'
							lastSpaceCharIndex = currentCharIndex;
							lineWidthAtLastSpace = currentLineWidth;
							hyphenSplitWidth = 0;
						}
					}
				}
			}
		}

		if (text.length() > startingCharIndexForLine)
			lines.add(text.substring(startingCharIndexForLine));

		return lines.toArray(String[]::new);
	}

	private int computeTagWidth(String tagText, int previousCharCode) {
		int charWidth = 0;
		int currentCharCode = getCharCodeForTag(tagText);
		if (currentCharCode != -1) {
			charWidth += getGlyphWidth(currentCharCode);
			if (kerning != null && previousCharCode != -1)
				charWidth += kerning[previousCharCode][currentCharCode];
			previousCharCode = currentCharCode;
		}
		return charWidth;
	}

	public int getGlyphWidth(int i_1) {
		return glyphWidths[i_1] & 0xff;
	}

	private int getCharCodeForTag(String tagText) {
		switch (tagText) {
			case "lt":
				return 60;
			case "gt":
				return 62;
			case "nbsp":
				return 160;
			case "shy":
				return 173;
			case "times":
				return 215;
			case "euro":
				return 8364;
			case "copy":
				return 169;
			case "reg":
				return 174;
			default:
				return -1;
		}
	}

	public static byte getByteForChar(char c) {
		byte i_6_;
		if (c > 0 && c < '\u0080' || c >= '\u00a0' && c <= '\u00ff')
			i_6_ = (byte) c;
		else if (c == '\u20ac')
			i_6_ = (byte) -128;
		else if ('\u201a' == c)
			i_6_ = (byte) -126;
		else if ('\u0192' == c)
			i_6_ = (byte) -125;
		else if (c == '\u201e')
			i_6_ = (byte) -124;
		else if ('\u2026' == c)
			i_6_ = (byte) -123;
		else if (c == '\u2020')
			i_6_ = (byte) -122;
		else if (c == '\u2021')
			i_6_ = (byte) -121;
		else if (c == '\u02c6')
			i_6_ = (byte) -120;
		else if ('\u2030' == c)
			i_6_ = (byte) -119;
		else if ('\u0160' == c)
			i_6_ = (byte) -118;
		else if (c == '\u2039')
			i_6_ = (byte) -117;
		else if ('\u0152' == c)
			i_6_ = (byte) -116;
		else if ('\u017d' == c)
			i_6_ = (byte) -114;
		else if ('\u2018' == c)
			i_6_ = (byte) -111;
		else if (c == '\u2019')
			i_6_ = (byte) -110;
		else if (c == '\u201c')
			i_6_ = (byte) -109;
		else if (c == '\u201d')
			i_6_ = (byte) -108;
		else if (c == '\u2022')
			i_6_ = (byte) -107;
		else if (c == '\u2013')
			i_6_ = (byte) -106;
		else if ('\u2014' == c)
			i_6_ = (byte) -105;
		else if ('\u02dc' == c)
			i_6_ = (byte) -104;
		else if (c == '\u2122')
			i_6_ = (byte) -103;
		else if ('\u0161' == c)
			i_6_ = (byte) -102;
		else if (c == '\u203a')
			i_6_ = (byte) -101;
		else if (c == '\u0153')
			i_6_ = (byte) -100;
		else if (c == '\u017e')
			i_6_ = (byte) -98;
		else if (c == '\u0178')
			i_6_ = (byte) -97;
		else
			i_6_ = (byte) 63;
		return i_6_;
	}
	
	private void decode(InputStream stream) {
		int i_3 = stream.readUnsignedByte();
		if (i_3 != 0) {
			throw new RuntimeException("");
		} else {
			boolean bool_4 = stream.readUnsignedByte() == 1;
			this.glyphWidths = new byte[256];
			stream.readBytes(this.glyphWidths, 0, 256);
			if (bool_4) {
				int[] ints_5 = new int[256];
				int[] ints_6 = new int[256];

				int i_7;
				for (i_7 = 0; i_7 < 256; i_7++) {
					ints_5[i_7] = stream.readUnsignedByte();
				}

				for (i_7 = 0; i_7 < 256; i_7++) {
					ints_6[i_7] = stream.readUnsignedByte();
				}

				byte[][] bytes_12 = new byte[256][];

				int i_10;
				for (int i_8 = 0; i_8 < 256; i_8++) {
					bytes_12[i_8] = new byte[ints_5[i_8]];
					byte b_9 = 0;

					for (i_10 = 0; i_10 < bytes_12[i_8].length; i_10++) {
						b_9 += stream.readByte();
						bytes_12[i_8][i_10] = b_9;
					}
				}

				byte[][] bytes_13 = new byte[256][];

				int i_14;
				for (i_14 = 0; i_14 < 256; i_14++) {
					bytes_13[i_14] = new byte[ints_5[i_14]];
					byte b_15 = 0;

					for (int i_11 = 0; i_11 < bytes_13[i_14].length; i_11++) {
						b_15 += stream.readByte();
						bytes_13[i_14][i_11] = b_15;
					}
				}

				this.kerning = new byte[256][256];

				for (i_14 = 0; i_14 < 256; i_14++) {
					if (i_14 != 32 && i_14 != 160) {
						for (i_10 = 0; i_10 < 256; i_10++) {
							if (i_10 != 32 && i_10 != 160) {
								this.kerning[i_14][i_10] = (byte) Utils.calculateKerning(bytes_12, bytes_13, ints_6, this.glyphWidths, ints_5, i_14, i_10);
							}
						}
					}
				}

				this.anInt4975 = ints_6[32] + ints_5[32];
			} else {
				this.anInt4975 = stream.readUnsignedByte();
			}

			stream.readUnsignedByte();
			stream.readUnsignedByte();
			this.anInt4978 = stream.readUnsignedByte();
			this.anInt4979 = stream.readUnsignedByte();
		}
	}

	@Override
	public String toString() {
		StringBuilder result = new StringBuilder();
		String newLine = System.getProperty("line.separator");

		result.append(this.getClass().getName());
		result.append(" {");
		result.append(newLine);

		// determine fields declared in this class only (no fields of
		// superclass)
		Field[] fields = this.getClass().getDeclaredFields();

		// print field names paired with their values
		for (Field field : fields) {
			if (Modifier.isStatic(field.getModifiers()))
				continue;
			result.append("  ");
			try {
				result.append(field.getType().getCanonicalName() + " " + field.getName() + ": ");
				result.append(Utils.getFieldValue(this, field));
			} catch (Throwable ex) {
				System.out.println(ex);
			}
			result.append(newLine);
		}
		result.append("}");

		return result.toString();
	}
	
}
