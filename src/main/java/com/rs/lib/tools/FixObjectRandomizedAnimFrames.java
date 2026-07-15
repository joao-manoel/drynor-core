package com.rs.lib.tools;

import com.rs.cache.Cache;
import com.rs.cache.IndexType;
import com.rs.cache.loaders.ObjectDefinitions;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class FixObjectRandomizedAnimFrames {
    public static void main(String[] args) throws IOException {
        Cache.init("../cache/");
        Set<Integer> fixed = new HashSet<>();
        int[] toFix = new int[] {
                11429, 11431, 11433
//                5657,
//                38661,
//                38662,
//                38663,
//                38664,
//                38665,
//                38666,
//                38667,
//                38668,
//                41065,
//                41346,
//                41347,
//                66521,
//                66522,
//                66532,
//                11391,
//                11392,
//                11393,
//                11394,
//                11395,
//                38660,
//                11925,
//                12712,
//                11926,
//                11927,
//                11929,
//                11928,
//                12711,
//                19176, //failed bird snare
//                19177, //tropical wagtail catch
//                19179, //crimson swift catch
//                19181, //cerulean twitch catch
//                19183, //golen warbler catch
//                19185, //copper longtail catch
//                19188, //box trap fail
//                19193, //gray chin catches
//                19194,
//                19195,
//                19196,
//                19197, //crimson chin catches
//                19198,
//                19199,
//                19200,
//                19201, //ferret catches
//                19202,
//                19203,
//                19204,
//                19207, //deadfall catch prickly kebbit north
//                19208, //deadfall catch prickly kebbit south
//                19209, //sabretooth kebbit north
//                19210, //sabretooth kebbit south
//                19211, //barbtail kebbit north
//                19212, //barbtail kebbit south
//                19213, //wild kebbit north
//                19214, //wild kebbit south
//                19219, //deadfall trap fail
//                19334, //rabbit snare fail
//                19655, //orange salamander catch
//                19657, //orange salamander fail
//                19658, //red salamander catch
//                19660, //red salamander fail
//                19666, //black salamander catch
//                19668, //black salamander fail
//                19674, //green salamander catch
//                19676, //green salamander fail
//                28559, //green salamander catch (alt?)
//                28561, //green salamander fail (alt?)
//                28568, //gecko box trap catch
//                28569,
//                28570,
//                28571,
//                28750, //squirrel net trap catch
//                28754, //raccoon box trap catch
//                28755,
//                28756,
//                28757,
//                28758, //monkey box trap catch
//                28759,
//                28760,
//                28777,
//                28908, //grenwall box trap catch
//                28909,
//                28910,
//                28911,
//                28915, //pawya box trap catch
//                28916,
//                28917,
//                28918,
//                28938, //deadfall fail (first resort)
//                28939, //deadfall catch north (first resort)
//                28940, //deadfall catch south (first resort)
//                29161, //ditto
//                29162, //ditto
//                29164, //tropical wagtail catch
//                29185, //platypus large box trap catch
//                29186,
//                29187,
//                29188,
//                29189, //platypus medium box trap catch
//                29190,
//                29191,
//                29192,
//                29889, //brown raccoon box trap catch
//                29890,
//                29891,
//                29892,
//                29893, //orange raccoon box trap catch
//                29894,
//                29895,
//                29896,
//                29906, //black monkey box trap catch
//                29907,
//                29908,
//                29909,
//                29914, //orange spotted gecko box trap catch
//                29915,
//                29916,
//                29917,
//                29918, //green spotted gecko box trap catch
//                29919,
//                29920,
//                29921,
//                29922, //red spotted gecko box trap catch
//                29923,
//                29924,
//                29925,
//                29926, //blue spotted gecko box trap catch
//                29927,
//                29928,
//                29929,
//                29960, //monkey color variants box trap catch
//                29961,
//                29962,
//                29963,
//                29964,
//                29965,
//                29966,
//                29967,
//                29968,
//                29969,
//                29970,
//                29971,
//                29972,
//                29973,
//                29974,
//                29975,
//                29976,
//                29977,
//                29978,
//                29979,
//                29980,
//                29981,
//                29982,
//                29983,
//                29984,
//                29985,
//                29986,
//                29987,
//                29988,
//                29989,
//                29990,
//                29991,
//                43465, //penguin deadfall trap fail
//                43466, //penguin deadfall catch south
//                43467, //penguin deadfall catch north
//                43468,
//                43469,
//                43474, //penguin box trap catch
//                43475,
//                43476,
//                43477,
//                43478, //penguin net trap catch
//                43480, //penguin net trap fail
//                56807,
//                56808,
//                56809,
//                56810,
//                56812,
//                56813,
//                56814,
//                56815,
//                56817,
//                56818,
        };

        for (int id : toFix) {
            if (fixed.contains(id))
                continue;
            ObjectDefinitions def = ObjectDefinitions.getDefs(id, false);
            System.out.println("Fixing: " + def.getName() + "(" + id + ")");
            def.randomizeAnimationStartFrame = false;
            def.write(Cache.STORE);
            fixed.add(id);
            System.out.println("Fixed: " + def.getName() + "(" + id + ")");
        }

        Cache.STORE.getIndex(IndexType.OBJECTS).rewriteTable();
    }
}
