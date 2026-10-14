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
import java.lang.SuppressWarnings;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.rs.cache.ArchiveType;
import com.rs.cache.Cache;
import com.rs.cache.IndexType;
import com.rs.cache.Store;
import com.rs.lib.game.VarManager;
import com.rs.lib.io.InputStream;
import com.rs.lib.io.OutputStream;
import com.rs.lib.net.ClientPacket;
import com.rs.lib.util.Utils;

@SuppressWarnings("unused")
public class ObjectDefinitions {

	private static final ConcurrentHashMap<Integer, ObjectDefinitions> objectDefinitions = new ConcurrentHashMap<Integer, ObjectDefinitions>();

	public static ObjectDefinitions DEFAULTS = new ObjectDefinitions();

	public boolean hasHideableOptions = false;
	public int anInt5633;
	public byte aByte5634;
	public int offsetY;
	public ObjectType[] types;
	public int[][] modelIds;
	private String name = "null";
	public int scaleY;
	public short[] modifiedColors;
	public byte[] aByteArray5641;
	public byte aByte5642;
	public short[] modifiedTextures;
	public byte aByte5644;
	public short[] originalColors;
	public byte aByte5646;
	public String[] options = new String[5];
	public String[] hideableOptions = new String[5];
	public String[] ingameVisibleOptions = new String[5];
	public int sizeX;
	public int sizeY;
	public int[] transformTo;
	public int interactable;
	public int ambientSoundId;
	public int anInt5654;
	public boolean delayShading;
	public int occludes;
	public boolean castsShadow;
	public int anInt5658;
	public int[] animations;
	public boolean members;
	public int decorDisplacement;
	public int varp;
	public int contrast;
	public boolean blocks;
	public int anInt5665;
	public int anInt5666;
	public int anInt5667;
	public int mapIcon;
	public int anInt5670;
	public boolean adjustMapSceneRotation;
	public int mapSpriteRotation;
	public boolean flipMapSprite;
	public boolean inverted;
	public int[] animProbs;
	public int scaleX;
	public int clipType;
	public int scaleZ;
	public int offsetX;
	public short[] originalTextures;
	public int offsetZ;
	public int anInt5682;
	public int anInt5683;
	public int anInt5684;
	public boolean obstructsGround;
	public boolean ignoresPathfinder;
	public int supportsItems;
	public int[] soundEffectsTimed;
	public int mapSpriteId;
	public int varpBit;
	public static short[] aShortArray5691 = new short[256];
	public int ambient;
	public int ambientSoundHearDistance;
	public int anInt5694;
	public int ambientSoundVolume;
	public boolean midiSound;
	public byte groundContoured;
	public int anInt5698;
	public boolean aBool5699;
	public boolean midiSoundEffectsTimed;
	public boolean hidden;
	public boolean randomizeAnimationStartFrame;
	public boolean aBool5703;
	public int anInt5704;
	public int anInt5705;
	public boolean hasAnimation;
	public int[] anIntArray5707;
	public int anInt5708;
	public int anInt5709;
	public int anInt5710;
	public boolean aBool5711;
	public HashMap<Integer, Object> parameters;
	public int id;
	public int accessBlockFlag;

	public static void main(String[] args) throws IOException {
		Cache.init("../cache/");
		ObjectDefinitions defs = getDefs(40953);
		System.out.println(defs);
		
//		for (int i = 0;i < Utils.getObjectDefinitionsSize();i++) {
//			ObjectDefinitions def = getDefs(i);
//			if (def.getName().contains("Potter") && def.getName().toLowerCase().contains("oven")) {
//				System.out.println(def.getName());
//			}
//		}
//		
//		for (int i = 0;i < defs.toObjectIds.length;i++) {
//			ObjectDefinitions toDef = getObjectDefinitions(defs.toObjectIds[i]);
//			System.out.println(i+"-"+toDef.getName());
//		}
		
//		for (int i = 0;i < Utils.getObjectDefinitionsSize();i++) {
//			ObjectDefinitions defs = getObjectDefinitions(i);
//			if (defs.configFileId == 8405)
//				System.out.println(defs);
//		}
		
//		ProductInfo[] infos = new ProductInfo[] { ProductInfo.Hammerstone, ProductInfo.Asgarnian, ProductInfo.Yanillian, ProductInfo.Krandorian, ProductInfo.Wildblood, ProductInfo.Barley, ProductInfo.Jute };
//		
//		int prodIdx = 0;
//		for (ProductInfo info : infos) {
//			int count = 0;
//			int startIdx = 0;
//			for (int i = 0;i < 64;i++) {
//				ObjectDefinitions toDef = getObjectDefinitions(defs.toObjectIds[i]);
//				if (toDef != null && toDef.getName().contains(info.name())) {
//					if (startIdx == 0)
//						startIdx = i;
//					count++;
//				}
//			}
//			System.out.println(prodIdx + "("+info.name() + ") " + (count-info.maxStage) + "-" + startIdx);
//			
//			prodIdx++;
//		}
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
	
	public int[] getModels(ObjectType type) {
		for (int i = 0;i < types.length;i++) {
			if (types[i] == type)
				return modelIds[i];
		}
		return new int[] {0};
	}
	
	public String getFirstOption() {
		if (ingameVisibleOptions == null || ingameVisibleOptions.length < 1 || ingameVisibleOptions[0] == null)
			return "";
		return options[0];
	}

	public String getSecondOption() {
		if (ingameVisibleOptions == null || ingameVisibleOptions.length < 2 || ingameVisibleOptions[1] == null)
			return "";
		return ingameVisibleOptions[1];
	}

	public String getOption(int option) {
		if (ingameVisibleOptions == null || ingameVisibleOptions.length < option || option == 0 || ingameVisibleOptions[option - 1] == null)
			return "";
		return ingameVisibleOptions[option - 1];
	}
	
	public String getOption(ClientPacket option) {
		int op = -1;
		switch(option) {
			case OBJECT_OP1 -> op = 0;
			case OBJECT_OP2 -> op = 1;
			case OBJECT_OP3 -> op = 2;
			case OBJECT_OP4 -> op = 3;
			case OBJECT_OP5 -> op = 4;
			default -> throw new IllegalArgumentException("Unexpected value: " + option);
		}
		if (ingameVisibleOptions == null || op < 0 || ingameVisibleOptions.length < op || ingameVisibleOptions[op] == null)
			return "";
		return ingameVisibleOptions[op];
	}

	public String getThirdOption() {
		if (ingameVisibleOptions == null || ingameVisibleOptions.length < 3 || ingameVisibleOptions[2] == null)
			return "";
		return ingameVisibleOptions[2];
	}

	public boolean containsOption(int i, String option) {
		if (ingameVisibleOptions == null || ingameVisibleOptions[i] == null || ingameVisibleOptions.length <= i)
			return false;
		return ingameVisibleOptions[i].equals(option);
	}
	
	public boolean containsOptionIgnoreCase(String string) {
		if (ingameVisibleOptions == null)
			return false;
		for (String option : ingameVisibleOptions) {
			if (option == null)
				continue;
			if (option.toLowerCase().contains(string.toLowerCase()))
				return true;
		}
		return false;
	}

	public boolean containsOption(String o) {
		if (ingameVisibleOptions == null)
			return false;
		for (String option : ingameVisibleOptions) {
			if (option == null)
				continue;
			if (option.equalsIgnoreCase(o))
				return true;
		}
		return false;
	}
	
	public boolean containsOption(int i) {
		if (ingameVisibleOptions == null || ingameVisibleOptions[i] == null || ingameVisibleOptions.length <= i)
			return false;
		return !ingameVisibleOptions[i].equals("null");
	}

	private void readValues(InputStream stream, int opcode, boolean newProtocol) {
		if (opcode == 1 || opcode == 5) {
			int i_4_ = stream.readUnsignedByte();
			types = new ObjectType[i_4_];
			modelIds = new int[i_4_][];
			for (int i_5_ = 0; i_5_ < i_4_; i_5_++) {
				types[i_5_] = ObjectType.forId(stream.readByte());
				int i_6_ = stream.readUnsignedByte();
				modelIds[i_5_] = new int[i_6_];
				for (int i_7_ = 0; i_7_ < i_6_; i_7_++)
					modelIds[i_5_][i_7_] = stream.readBigSmart();
			}
			if (opcode == 5)
				skipReadModelIds(stream);
		} else if (opcode == 2)
			name = stream.readString();
		else if (opcode == 14)
			sizeX = stream.readUnsignedByte();
		else if (15 == opcode)
			sizeY = stream.readUnsignedByte();
		else if (17 == opcode) {
			clipType = 0;
			blocks = false;
		} else if (18 == opcode)
			blocks = false;
		else if (opcode == 19)
			interactable = stream.readUnsignedByte();
		else if (21 == opcode)
			groundContoured = (byte) 1;
		else if (22 == opcode)
			delayShading = true;
		else if (opcode == 23)
			occludes = 1;
		else if (opcode == 24) {
			int i_8_ = stream.readBigSmart();
			if (i_8_ != -1)
				animations = new int[] { i_8_ };
		} else if (opcode == 27)
			clipType = 1;
		else if (opcode == 28)
			decorDisplacement = (stream.readUnsignedByte() << 2);
		else if (opcode == 29)
			ambient = stream.readByte();
		else if (39 == opcode)
			contrast = stream.readByte();
		else if (opcode >= 30 && opcode < 35) {
			options[opcode - 30] = stream.readString();
			ingameVisibleOptions[opcode - 30] = options[opcode - 30];
		} else if (40 == opcode) {
			int i_9_ = stream.readUnsignedByte();
			originalColors = new short[i_9_];
			modifiedColors = new short[i_9_];
			for (int i_10_ = 0; i_10_ < i_9_; i_10_++) {
				originalColors[i_10_] = (short) stream.readUnsignedShort();
				modifiedColors[i_10_] = (short) stream.readUnsignedShort();
			}
		} else if (opcode == 41) {
			int i_11_ = stream.readUnsignedByte();
			originalTextures = new short[i_11_];
			modifiedTextures = new short[i_11_];
			for (int i_12_ = 0; i_12_ < i_11_; i_12_++) {
				originalTextures[i_12_] = (short) stream.readUnsignedShort();
				modifiedTextures[i_12_] = (short) stream.readUnsignedShort();
			}
		} else if (opcode == 42) {
			int i_13_ = stream.readUnsignedByte();
			aByteArray5641 = new byte[i_13_];
			for (int i_14_ = 0; i_14_ < i_13_; i_14_++)
				aByteArray5641[i_14_] = (byte) stream.readByte();
		} else if (opcode == 44 || opcode == 45) {
			int i_86_ = (short) stream.readUnsignedShort();
		} else if (opcode == 62)
			inverted = true;
		else if (opcode == 64)
			castsShadow = false;
		else if (65 == opcode)
			scaleX = stream.readUnsignedShort();
		else if (opcode == 66)
			scaleY = stream.readUnsignedShort();
		else if (67 == opcode)
			scaleZ = stream.readUnsignedShort();
		else if (opcode == 69)
			accessBlockFlag = stream.readUnsignedByte();
		else if (70 == opcode)
			offsetX = (stream.readShort() << 2);
		else if (opcode == 71)
			offsetY = (stream.readShort() << 2);
		else if (opcode == 72)
			offsetZ = (stream.readShort() << 2);
		else if (73 == opcode)
			obstructsGround = true;
		else if (opcode == 74)
			ignoresPathfinder = true;
		else if (opcode == 75)
			supportsItems = stream.readUnsignedByte();
		else if (77 == opcode || 92 == opcode) {
			varpBit = stream.readUnsignedShort();
			if (65535 == varpBit)
				varpBit = -1;
			varp = stream.readUnsignedShort();
			if (varp == 65535)
				varp = -1;
			int objectId = -1;
			if (opcode == 92)
				objectId = stream.readBigSmart();
			int transforms = /*protocol >= 910*/newProtocol ? stream.readSmart() : stream.readUnsignedByte();
			transformTo = new int[transforms + 2];
			for (int i = 0; i <= transforms; i++)
				transformTo[i] = stream.readBigSmart();
			transformTo[1 + transforms] = objectId;
		} else if (78 == opcode) {
			ambientSoundId = stream.readUnsignedShort();
			ambientSoundHearDistance = stream.readUnsignedByte();
		} else if (79 == opcode) {
			anInt5667 = stream.readUnsignedShort();
			anInt5698 = stream.readUnsignedShort();
			ambientSoundHearDistance = stream.readUnsignedByte();
			int i_18_ = stream.readUnsignedByte();
			soundEffectsTimed = new int[i_18_];
			for (int i_19_ = 0; i_19_ < i_18_; i_19_++)
				soundEffectsTimed[i_19_] = stream.readUnsignedShort();
		} else if (81 == opcode) {
			groundContoured = (byte) 2;
			anInt5654 = stream.readUnsignedByte() * 256;
		} else if (opcode == 82)
			hidden = true;
		else if (88 == opcode)
			aBool5703 = false;
		else if (opcode == 89)
			randomizeAnimationStartFrame = false;
		else if (91 == opcode)
			members = true;
		else if (93 == opcode) {
			groundContoured = (byte) 3;
			anInt5654 = stream.readUnsignedShort();
		} else if (opcode == 94)
			groundContoured = (byte) 4;
		else if (95 == opcode) {
			groundContoured = (byte) 5;
			anInt5654 = stream.readShort();
		} else if (97 == opcode)
			adjustMapSceneRotation = true;
		else if (98 == opcode)
			hasAnimation = true;
		else if (99 == opcode) {
			anInt5705 = stream.readUnsignedByte();
			anInt5665 = stream.readUnsignedShort();
		} else if (opcode == 100) {
			anInt5670 = stream.readUnsignedByte();
			anInt5666 = stream.readUnsignedShort();
		} else if (101 == opcode)
			mapSpriteRotation = stream.readUnsignedByte();
		else if (opcode == 102)
			mapSpriteId = stream.readUnsignedShort();
		else if (opcode == 103)
			occludes = 0;
		else if (104 == opcode)
			ambientSoundVolume = stream.readUnsignedByte();
		else if (opcode == 105)
			flipMapSprite = true;
		else if (106 == opcode) {
			int i_20_ = stream.readUnsignedByte();
			int i_21_ = 0;
			animations = new int[i_20_];
			animProbs = new int[i_20_];
			for (int i_22_ = 0; i_22_ < i_20_; i_22_++) {
				animations[i_22_] = stream.readBigSmart();
				i_21_ += animProbs[i_22_] = stream.readUnsignedByte();
			}
			for (int i_23_ = 0; i_23_ < i_20_; i_23_++)
				animProbs[i_23_] = animProbs[i_23_] * 65535 / i_21_;
		} else if (opcode == 107)
			mapIcon = stream.readUnsignedShort();
		else if (opcode >= 150 && opcode < 155) {
			hideableOptions[opcode - 150] = stream.readString();
			ingameVisibleOptions[opcode - 150] = hideableOptions[opcode - 150];
//			if (!((ObjectDefinitionsLoader) loader).showOptions)
//				aStringArray5647[opcode - 150] = null;
		} else if (160 == opcode) {
			int i_24_ = stream.readUnsignedByte();
			anIntArray5707 = new int[i_24_];
			for (int i_25_ = 0; i_25_ < i_24_; i_25_++)
				anIntArray5707[i_25_] = stream.readUnsignedShort();
		} else if (162 == opcode) {
			groundContoured = (byte) 3;
			anInt5654 = stream.readInt();
		} else if (163 == opcode) {
			aByte5644 = (byte) stream.readByte();
			aByte5642 = (byte) stream.readByte();
			aByte5646 = (byte) stream.readByte();
			aByte5634 = (byte) stream.readByte();
		} else if (164 == opcode)
			anInt5682 = stream.readShort();
		else if (165 == opcode)
			anInt5683 = stream.readShort();
		else if (166 == opcode)
			anInt5710 = stream.readShort();
		else if (167 == opcode)
			anInt5704 = stream.readUnsignedShort();
		else if (168 == opcode)
			midiSound = true;
		else if (169 == opcode)
			midiSoundEffectsTimed = true;
		else if (opcode == 170)
			anInt5684 = stream.readUnsignedSmart();
		else if (opcode == 171)
			anInt5658 = stream.readUnsignedSmart();
		else if (opcode == 173) {
			anInt5708 = stream.readUnsignedShort();
			anInt5709 = stream.readUnsignedShort();
		} else if (177 == opcode)
			aBool5699 = true;
		else if (178 == opcode)
			anInt5694 = stream.readUnsignedByte();
		else if (opcode == 186)
			stream.readUnsignedByte();
		else if (opcode == 188) {

		} else if (189 == opcode)
			aBool5711 = true;
		else if (opcode >= 190 && opcode < 196) {
//			if (anIntArray4534 == null) {
//				anIntArray4534 = new int[6];
//				Arrays.fill(anIntArray4534, -1);
//			}
			/*anIntArray4534[opcode - 190] = */
			stream.readUnsignedShort();
		} else if (opcode == 196 || opcode == 197) {
			stream.readUnsignedByte();
		} else if (opcode == 198) {
		} else if (opcode == 199) {
		} else if (opcode == 201) {
			stream.readUnsignedSmart();
			stream.readUnsignedSmart();
			stream.readUnsignedSmart();
			stream.readUnsignedSmart();
			stream.readUnsignedSmart();
			stream.readUnsignedSmart();
		} else if (249 == opcode) {
			int length = stream.readUnsignedByte();
			if (parameters == null)
				parameters = new HashMap<Integer, Object>(length);
			for (int i_60_ = 0; i_60_ < length; i_60_++) {
				boolean bool = stream.readUnsignedByte() == 1;
				int i_61_ = stream.read24BitInt();
				if (!bool)
					parameters.put(i_61_, stream.readInt());
				else
					parameters.put(i_61_, stream.readString());
			}
		}
//		else {
//			System.out.println("UNKNOWN OPCODE: " + opcode);
//			System.exit(0);
//		}
	}

	public boolean write(Store store) {
		return store.getIndex(IndexType.OBJECTS).putFile(ArchiveType.OBJECTS.archiveId(id), ArchiveType.OBJECTS.fileId(id), encode(store.newProtocol));
	}

	private byte[] encode(boolean newProtocol) {
		OutputStream stream = new OutputStream();
		if (types != null && modelIds != null) {
			stream.writeByte(1);
			stream.writeByte(types.length);
			for (int i = 0; i < types.length; i++) {
				stream.writeByte(types[i].id);
				stream.writeByte(modelIds[i].length);
				for (int id : modelIds[i]) {
					stream.writeBigSmart(id);
				}
			}
		}

		if (name != null && !name.equals("null")) {
			stream.writeByte(2);
			stream.writeString(name);
		}

		if (sizeX != DEFAULTS.sizeX) {
			stream.writeByte(14);
			stream.writeByte(sizeX);
		}

		if (sizeY != DEFAULTS.sizeY) {
			stream.writeByte(15);
			stream.writeByte(sizeY);
		}

		if (clipType == 0 && !blocks) {
			stream.writeByte(17);
		}

		if (!blocks) {
			stream.writeByte(18);
		}

		if (interactable != DEFAULTS.interactable) {
			stream.writeByte(19);
			stream.writeByte(interactable);
		}

		if (groundContoured == 1) {
			stream.writeByte(21);
		}

		if (delayShading) {
			stream.writeByte(22);
		}

		if (occludes == 1) {
			stream.writeByte(23);
		}

		if (animations != null && animations.length > 0 && animations[0] != -1) {
			stream.writeByte(24);
			stream.writeBigSmart(animations[0]);
		}

		if (clipType == 1) {
			stream.writeByte(27);
			stream.writeByte(clipType);
		}

		if (decorDisplacement != DEFAULTS.decorDisplacement) {
			stream.writeByte(28);
			stream.writeByte(decorDisplacement >> 2);
		}

		if (ambient != DEFAULTS.ambient) {
			stream.writeByte(29);
			stream.writeByte(ambient);
		}

		if (contrast != DEFAULTS.contrast) {
			stream.writeByte(39);
			stream.writeByte(contrast);
		}

		for (int i = 0; i < options.length; i++) {
			if (options[i] != null && !options[i].equals("null")) {
				stream.writeByte(30 + i);
				stream.writeString(options[i]);
			}
		}

		if (originalColors != null && modifiedColors != null) {
			stream.writeByte(40);
			stream.writeByte(originalColors.length);
			for (int i = 0; i < originalColors.length; i++) {
				stream.writeShort(originalColors[i]);
				stream.writeShort(modifiedColors[i]);
			}
		}

		if (originalTextures != null && modifiedTextures != null) {
			stream.writeByte(41);
			stream.writeByte(originalTextures.length);
			for (int i = 0; i < originalTextures.length; i++) {
				stream.writeShort(originalTextures[i]);
				stream.writeShort(modifiedTextures[i]);
			}
		}

		if (aByteArray5641 != null) {
			stream.writeByte(42);
			stream.writeByte(aByteArray5641.length);
			for (byte b : aByteArray5641) {
				stream.writeByte(b);
			}
		}

		if (inverted) {
			stream.writeByte(62);
		}

		if (!castsShadow) {
			stream.writeByte(64);
		}

		if (scaleX != DEFAULTS.scaleX) {
			stream.writeByte(65);
			stream.writeShort(scaleX);
		}

		if (scaleY != DEFAULTS.scaleY) {
			stream.writeByte(66);
			stream.writeShort(scaleY);
		}

		if (scaleZ != DEFAULTS.scaleZ) {
			stream.writeByte(67);
			stream.writeShort(scaleZ);
		}

		if (accessBlockFlag != DEFAULTS.accessBlockFlag) {
			stream.writeByte(69);
			stream.writeByte(accessBlockFlag);
		}

		if (offsetX != DEFAULTS.offsetX) {
			stream.writeByte(70);
			stream.writeShort(offsetX >> 2);
		}

		if (offsetY != DEFAULTS.offsetY) {
			stream.writeByte(71);
			stream.writeShort(offsetY >> 2);
		}

		if (offsetZ != DEFAULTS.offsetZ) {
			stream.writeByte(72);
			stream.writeShort(offsetZ >> 2);
		}

		if (obstructsGround) {
			stream.writeByte(73);
		}

		if (ignoresPathfinder) {
			stream.writeByte(74);
		}

		if (supportsItems != DEFAULTS.supportsItems) {
			stream.writeByte(75);
			stream.writeByte(supportsItems);
		}

		// Opcodes 77 and 92
		if (varpBit != DEFAULTS.varpBit || varp != DEFAULTS.varp) {
			stream.writeByte(newProtocol && transformTo != null && transformTo[transformTo.length - 1] != -1 ? 92 : 77);
			stream.writeShort(varpBit == -1 ? 65535 : varpBit);
			stream.writeShort(varp == -1 ? 65535 : varp);
			if (newProtocol) {
				stream.writeSmart(transformTo.length - 1);
			} else {
				stream.writeByte(transformTo.length - 1);
			}
			for (int i = 0; i < transformTo.length - 1; i++) {
				stream.writeBigSmart(transformTo[i]);
			}
		}

		if (ambientSoundId != DEFAULTS.ambientSoundId || ambientSoundHearDistance != DEFAULTS.ambientSoundHearDistance) {
			stream.writeByte(78);
			stream.writeShort(ambientSoundId);
			stream.writeByte(ambientSoundHearDistance);
		}

		if (anInt5667 != DEFAULTS.anInt5667 || anInt5698 != DEFAULTS.anInt5698 || ambientSoundHearDistance != DEFAULTS.ambientSoundHearDistance || soundEffectsTimed != null) {
			stream.writeByte(79);
			stream.writeShort(anInt5667);
			stream.writeShort(anInt5698);
			stream.writeByte(ambientSoundHearDistance);
			stream.writeByte(soundEffectsTimed.length);
			for (int effect : soundEffectsTimed) {
				stream.writeShort(effect);
			}
		}

		if (groundContoured == 2) {
			stream.writeByte(81);
			stream.writeByte(anInt5654 / 256);
		}

		if (hidden) {
			stream.writeByte(82);
		}

		if (!aBool5703) {
			stream.writeByte(88);
		}

		if (!randomizeAnimationStartFrame) {
			stream.writeByte(89);
		}

		if (members) {
			stream.writeByte(91);
		}

		if (groundContoured == 3) {
			stream.writeByte(93);
			stream.writeShort(anInt5654);
		}

		if (groundContoured == 4) {
			stream.writeByte(93);
		}

		if (groundContoured == 5) {
			stream.writeByte(95);
			stream.writeShort(anInt5654);
		}

		if (adjustMapSceneRotation) {
			stream.writeByte(97);
		}

		if (hasAnimation) {
			stream.writeByte(98);
		}

		if (anInt5705 != DEFAULTS.anInt5705 || anInt5665 != DEFAULTS.anInt5665) {
			stream.writeByte(99);
			stream.writeByte(anInt5705);
			stream.writeShort(anInt5665);
		}

		if (anInt5670 != DEFAULTS.anInt5670 || anInt5666 != DEFAULTS.anInt5666) {
			stream.writeByte(100);
			stream.writeByte(anInt5670);
			stream.writeShort(anInt5666);
		}

		if (mapSpriteRotation != DEFAULTS.mapSpriteRotation) {
			stream.writeByte(101);
			stream.writeByte(mapSpriteRotation);
		}

		if (mapSpriteId != DEFAULTS.mapSpriteId) {
			stream.writeByte(102);
			stream.writeShort(mapSpriteId);
		}

		if (occludes == 0) {
			stream.writeByte(103);
		}

		if (ambientSoundVolume != DEFAULTS.ambientSoundVolume) {
			stream.writeByte(104);
			stream.writeByte(ambientSoundVolume);
		}

		if (flipMapSprite) {
			stream.writeByte(105);
		}

		if (animations != null && animProbs != null) {
			stream.writeByte(106);
			stream.writeByte(animations.length);
			int totalProb = 0;
			for (int prob : animProbs) {
				totalProb += prob;
			}
			for (int i = 0; i < animations.length; i++) {
				stream.writeBigSmart(animations[i]);
				stream.writeByte((int) ((animProbs[i] / (float) totalProb) * 65535));
			}
		}

		if (mapIcon != DEFAULTS.mapIcon) {
			stream.writeByte(107);
			stream.writeShort(mapIcon);
		}

		for (int i = 0; i < hideableOptions.length; i++) {
			if (hideableOptions[i] != null && !hideableOptions[i].equals("null")) {
				stream.writeByte(150 + i);
				stream.writeString(hideableOptions[i]);
			}
		}

		if (anIntArray5707 != null) {
			stream.writeByte(160);
			stream.writeByte(anIntArray5707.length);
			for (int i : anIntArray5707) {
				stream.writeShort(i);
			}
		}

		if (groundContoured == 3) {
			stream.writeByte(162);
			stream.writeInt(anInt5654);
		}

		if (aByte5644 != DEFAULTS.aByte5644 || aByte5642 != DEFAULTS.aByte5642 || aByte5646 != DEFAULTS.aByte5646 || aByte5634 != DEFAULTS.aByte5634) {
			stream.writeByte(163);
			stream.writeByte(aByte5644);
			stream.writeByte(aByte5642);
			stream.writeByte(aByte5646);
			stream.writeByte(aByte5634);
		}

		if (anInt5682 != DEFAULTS.anInt5682) {
			stream.writeByte(164);
			stream.writeShort(anInt5682);
		}

		if (anInt5683 != DEFAULTS.anInt5683) {
			stream.writeByte(165);
			stream.writeShort(anInt5683);
		}

		if (anInt5710 != DEFAULTS.anInt5710) {
			stream.writeByte(166);
			stream.writeShort(anInt5710);
		}

		if (anInt5704 != DEFAULTS.anInt5704) {
			stream.writeByte(167);
			stream.writeShort(anInt5704);
		}

		if (midiSound) {
			stream.writeByte(168);
		}

		if (midiSoundEffectsTimed) {
			stream.writeByte(169);
		}

		if (anInt5684 != DEFAULTS.anInt5684) {
			stream.writeByte(170);
			stream.writeUnsignedSmart(anInt5684);
		}

		if (anInt5658 != DEFAULTS.anInt5658) {
			stream.writeByte(171);
			stream.writeUnsignedSmart(anInt5658);
		}

		if (anInt5708 != DEFAULTS.anInt5708 || anInt5709 != DEFAULTS.anInt5709) {
			stream.writeByte(173);
			stream.writeShort(anInt5708);
			stream.writeShort(anInt5709);
		}

		if (aBool5699) {
			stream.writeByte(177);
		}

		if (anInt5694 != DEFAULTS.anInt5694) {
			stream.writeByte(178);
			stream.writeByte(anInt5694);
		}

		if (aBool5711) {
			stream.writeByte(189);
		}

		if (parameters != null) {
			stream.writeByte(249);
			stream.writeByte(parameters.size());
			for (int key : parameters.keySet()) {
				Object value = parameters.get(key);
				stream.writeByte(value instanceof String ? 1 : 0);
				stream.write24BitInt(key);
				if (value instanceof String) {
					stream.writeString((String) value);
				} else {
					stream.writeInt((Integer) value);
				}
			}
		}
		stream.writeByte(0);

		byte[] data = new byte[stream.getOffset()];
		stream.setOffset(0);
		stream.getBytes(data, 0, data.length);
		return data;
	}
	
	public int getIdForPlayer(VarManager vars) {
		if (transformTo == null || transformTo.length == 0)
			return id;
		if (vars == null) {
			int varIdx = transformTo[transformTo.length - 1];
			return varIdx;
		}
		int index = -1;
		if (varpBit != -1) {
			index = vars.getVarBit(varpBit);
		} else if (varp != -1) {
			index = vars.getVar(varp);
		}
		if (index >= 0 && index < transformTo.length - 1 && transformTo[index] != -1) {
			return transformTo[index];
		} else {
			int varIdx = transformTo[transformTo.length - 1];
			return varIdx;
		}
	}
	
	public String getConfigInfoString() {
		String finalString = "";
		String transforms = "\r\n";
		boolean found = false;
		for (int objectId = 0;objectId < Utils.getObjectDefinitionsSize();objectId++) {
			ObjectDefinitions defs = getDefs(objectId);
			if (defs.transformTo == null)
				continue;
			for (int i = 0;i < defs.transformTo.length;i++) {
				if (defs.transformTo[i] == id) {
					found = true;
					transforms += "[" + objectId + "("+defs.getName()+")" +":";
					if (defs.varp != -1)
						transforms += ("v"+defs.varp+"="+i);
					if (defs.varpBit != -1)
						transforms += ("vb"+defs.varpBit+"="+i);
					transforms += "], \r\n";
				}
			}
		}
		if (found) {
			finalString += " - transformed into by: " + transforms;
			transforms = "";
		}
		found = false;
		if (transformTo != null) {
			found = true;
			for (int i = 0;i < transformTo.length;i++) {
				if (transformTo[i] != -1)
					transforms += "[" + i + ": " + transformTo[i] +" ("+getDefs(transformTo[i]).name+")";
				else
					transforms += "["+i+": INVISIBLE";
				transforms += "], \r\n";
			}
		}
		if (found) {
			finalString += " - transforms into with ";
			if (varp != -1)
				finalString += ("v"+varp) + ":";
			if (varpBit != -1)
				finalString += ("vb"+varpBit) + ":";
			finalString += transforms;
			transforms = "";
		}
		return finalString;
	}

	private void skipReadModelIds(InputStream stream) {
		int length = stream.readUnsignedByte();
		for (int index = 0; index < length; index++) {
			stream.skip(1);
			int length2 = stream.readUnsignedByte();
			for (int i = 0; i < length2; i++)
				stream.readBigSmart();
		}
	}

	private void readValueLoop(InputStream stream, boolean newProtocol) {
		for (;;) {
			int opcode = stream.readUnsignedByte();
			if (opcode == 0) {
				break;
			}
			readValues(stream, opcode, newProtocol);
		}
	}

	private ObjectDefinitions() {
		aByte5634 = (byte) 0;
		sizeX = 1;
		sizeY = 1;
		clipType = 2;
		blocks = true;
		interactable = -1;
		groundContoured = (byte) 0;
		anInt5654 = -1;
		delayShading = false;
		occludes = -1;
		anInt5684 = 960;
		anInt5658 = 0;
		animations = null;
		animProbs = null;
		decorDisplacement = 64;
		ambient = 0;
		contrast = 0;
		anInt5665 = -1;
		anInt5666 = -1;
		anInt5705 = -1;
		anInt5670 = -1;
		mapIcon = -1;
		mapSpriteId = -1;
		adjustMapSceneRotation = false;
		mapSpriteRotation = 0;
		flipMapSprite = false;
		inverted = false;
		castsShadow = true;
		scaleX = 128;
		scaleY = 128;
		scaleZ = 128;
		offsetX = 0;
		offsetY = 0;
		offsetZ = 0;
		anInt5682 = 0;
		anInt5683 = 0;
		anInt5710 = 0;
		obstructsGround = false;
		ignoresPathfinder = false;
		supportsItems = -1;
		anInt5704 = 0;
		varpBit = -1;
		varp = -1;
		ambientSoundId = -1;
		ambientSoundHearDistance = 0;
		anInt5694 = 0;
		ambientSoundVolume = -1;
		midiSound = false;
		anInt5667 = 0;
		anInt5698 = 0;
		midiSoundEffectsTimed = false;
		randomizeAnimationStartFrame = true;
		hidden = false;
		aBool5703 = true;
		members = false;
		hasAnimation = false;
		anInt5708 = -1;
		anInt5709 = 0;
		accessBlockFlag = 0;
		aBool5699 = false;
		aBool5711 = false;
		name = "null";
	}

	public static ObjectDefinitions getDefs(int id) {
		return getDefs(id, true);
	}

	public static ObjectDefinitions getDefs(int id, boolean init) {
		ObjectDefinitions def = objectDefinitions.get(id);
		if (def == null) {
			def = new ObjectDefinitions();
			def.id = id;
			byte[] data = Cache.STORE.getIndex(IndexType.OBJECTS).getFile(ArchiveType.OBJECTS.archiveId(id), ArchiveType.OBJECTS.fileId(id));
			if (data != null)
				def.readValueLoop(new InputStream(data), Cache.STORE.getIndex(IndexType.OBJECTS).newProtocol);
			if (init)
				def.method7966();
			/*
			 * DUNGEONEERING DOORS?..
			 */
			switch (id) {
			case 50342:
			case 50343:
			case 50344:
			case 53948:
			case 55762:
			case 50350:
			case 50351:
			case 50352:
			case 53950:
			case 55764:
				def.ignoresPathfinder = false;
				def.blocks = true;
				def.clipType = 1;
				break;
			}
			objectDefinitions.put(id, def);
		}
		return def;
	}
	
	void method7966() {
		if (interactable == -1) {
			interactable = 0;
			if (null != types && types.length == 1 && (types[0] == ObjectType.SCENERY_INTERACT))
				interactable = 1;
			for (int i_30_ = 0; i_30_ < 5; i_30_++) {
				if (ingameVisibleOptions[i_30_] != null) {
					interactable = 1;
					break;
				}
			}
		}
		if (supportsItems == -1)
			supportsItems =  (0 != clipType ? 1 : 0);
		if (animations != null || hasAnimation || transformTo != null)
			aBool5699 = true;
	}
	
	public static ObjectDefinitions getDefs(int id, VarManager player) {
		ObjectDefinitions defs = getDefs(getDefs(id).getIdForPlayer(player));
		if (defs == null)
			return getDefs(id);
		return defs;
	}
	
	public String getName() {
		return getName(null);
	}
	
	public String getName(VarManager player) {
		int realId = getIdForPlayer(player);
		if (realId == id)
			return name;
		if (realId == -1)
			return "null";
		return getDefs(realId).name;
	}
	

	public ObjectDefinitions getRealDefs() {
		return getDefs(getIdForPlayer(null));
	}

	public int getClipType() {
		return clipType;
	}

	public boolean blocks() {
		return blocks;
	}

	public boolean isClipped() {
		return clipType != 0;
	}

	public int getSizeX() {
		return sizeX;
	}

	public int getSizeY() {
		return sizeY;
	}
	
	public int getAccessBlockFlag() {
		return accessBlockFlag;
	}

	public static void clearObjectDefinitions() {
		objectDefinitions.clear();
	}

	public ObjectType getType(int i) {
		if (types != null && i < types.length)
			return types[i];
		return ObjectType.SCENERY_INTERACT;
	}

	public int getSlot() {
		return getType(0).slot;
	}

}
