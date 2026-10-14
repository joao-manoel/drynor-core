# Dead code in the 727 clientscripts

Everything here is in the bytecode but cannot affect what the script does.
A dead store is kept in the decompiled source; unreachable code is dropped from it.
Unreachable code that only jumps somewhere is the compiler's own layout and is not listed,
and neither is the default `return` every script ends with.

## cs2_23 (script 23)

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 0);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 1);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 2);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 3);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 4);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 5);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 6);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 7);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 8);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 9);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 10);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 11);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 12);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 13);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 14);
```

- **unused result** - targets int slot 1

```ts
  [str1, int1] = cs2_13(varbit_skill_guide_skill_v2, 15);
```

- **unused result** - targets string slot 3

```ts
  [int4, int6, str2, str3] = cs2_1567(varbit_skill_guide_skill_v2, varbit_skill_guide_subsection_v2, int2);
```

- **unused result** - targets string slot 3

```ts
  [int4, int5, str2, str3] = cs2_14(varbit_skill_guide_skill_v2, varbit_skill_guide_subsection_v2, int2);
```

## [clientscript,chatdefault_onkey] (script 73)

- **unused result** - targets int slot 9, int slot 10, int slot 11

```ts
  [int6, int7, int8, int9, int10, int11] = cs2_4590();
```

## [proc,add_to_inputstring] (script 74)

- **unused result** - targets int slot 3

```ts
  [str1, int3] = cs2_802(int3, strArg0, intArg0, intArg1, intArg2);
```

## [proc,rebuildchatbox] (script 84)

- **dead store**

```ts
  str3 = "<col=96ff7d>";
```

## [clientscript,chat_op] (script 86)

- **dead store**

```ts
  str1 = removetags(chatGethistoryname(intArg1));
```

## [clientscript,meslayer_onkey] (script 112)

- **empty branch** - both arms are empty

```ts
  varc_meslayermode != 7
```

## [proc,friend_update] (script 125)

- **dead store**

```ts
  int27 = 0;
```

- **dead store**

```ts
  int27 = 1;
```

## [clientscript,friendschat_setrank] (script 197)

- **unused result** - targets string slot 1

```ts
  [str0, str1] = friendGetName(intArg0);
```

## cs2_206 (script 206)

- **dead store**

```ts
  int6 = 0;
```

## cs2_224 (script 224)

- **dead store**

```ts
  int5 = 0;
```

- **dead store**

```ts
  int5 = 14798;
```

## [proc,worldmap_elements_update] (script 295)

- **dead store**

```ts
  int5 = cs2_297(589620541, 52864326, 0, 16776960, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(39325979, 38474018, 0, 16776960, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(313332908, 581768364, 0, 16776960, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(46507915, 45557681, 0, 65535, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(594388249, 57517334, 0, 16711680, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(50079065, 50079060, 0, 16711680, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(42247458, 42149154, 0, 32767, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(41101361, 41199667, 0, 16776960, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = worldmap_elements_textbox(3145807, 1, "Upper level", 280, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(41669235, 41112143, 0, 16776960, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_298(38753372, 38360163, 0, 65280, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(319901872, 51482798, 0, 16711935, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(584127643, 46781541, 0, 16711680, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(43197740, 580068647, 0, 16711680, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(54748313, 53645556, 0, 16711680, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = worldmap_elements_textbox(3473463, 1, "Sub-level 6", 280, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(51597875, 51597879, 0, 16711680, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(51926406, 51762557, 0, 65280, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = worldmap_elements_textbox(15, 1, "The world map screen" + "<br>" + "shows you where you are," + "<br>" + "and where important" + "<br>" + "features may be found.", 280, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(296867009, 27219081, 0, 65280, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = worldmap_elements_textbox(51913714, 1, "Bottom", 280, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = cs2_297(21977324, 17832289, 0, 65280, 6, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

- **dead store**

```ts
  int5 = worldmap_elements_textbox(41952522, 1, "Forgotten Sewers", 280, intArg0, intArg1, intArg2, intArg3, intArg4, int5);
```

## cs2_309 (script 309)

- **dead store**

```ts
  int3 = cs2_292(280, intArg1, intArg2, int3);
```

## [clientscript,levelup_start] (script 336)

- **dead store**

```ts
  int1 = 1;
```

- **dead store**

```ts
  int4 = random(150);
```

- **dead store**

```ts
  int5 = random(50);
```

## cs2_337 (script 337)

- **dead store**

```ts
  int4 = random(150);
```

- **dead store**

```ts
  int5 = random(50);
```

## cs2_363 (script 363)

- **dead store**

```ts
  int10 = int9 - 10;
```

## cs2_387 (script 387)

- **dead store**

```ts
  int5 = int5 + int1 + 10;
```

- **dead store**

```ts
  int1 = max((int8 + 1) * int3 + (89 - int3), scale(4, 5, 765));
```

## cs2_427 (script 427)

- **dead store**

```ts
  int2 = int2 + 1;
```

- **dead store**

```ts
  int2 = int2 + 1;
```

## cs2_444 (script 444)

- **dead store**

```ts
  int5 = 0;
```

## [clientscript,assist_skill_update] (script 522)

- **dead store**

```ts
  int2 = statVisibleXp(intArg1);
```

## [proc,stats_mouseover_create] (script 547)

- **dead store**

```ts
  int16 = int16 + 1;
```

## cs2_563 (script 563)

- **dead store**

```ts
  int1 = int1 + 29;
```

- **dead store**

```ts
  int1 = int1 + 29;
```

## cs2_565 (script 565)

- **dead store**

```ts
  int1 = int1 + 29;
```

- **dead store**

```ts
  int1 = int1 + 29;
```

## cs2_567 (script 567)

- **dead store**

```ts
  int3 = varbit_artisan_iron_ores - int3;
```

- **dead store**

```ts
  int3 = 0;
```

- **dead store**

```ts
  int3 = varbit_artisan_iron_ores - int3;
```

- **dead store**

```ts
  int3 = 0;
```

- **dead store**

```ts
  int4 = varbit_artisan_coal - int4;
```

- **dead store**

```ts
  int4 = 0;
```

- **dead store**

```ts
  int3 = varbit_artisan_mithril_ores - int3;
```

- **dead store**

```ts
  int3 = 0;
```

- **dead store**

```ts
  int4 = varbit_artisan_coal - int4;
```

- **dead store**

```ts
  int4 = 0;
```

- **dead store**

```ts
  int3 = varbit_artisan_adamant_ores - int3;
```

- **dead store**

```ts
  int3 = 0;
```

- **dead store**

```ts
  int4 = varbit_artisan_coal - int4;
```

- **dead store**

```ts
  int4 = 0;
```

- **dead store**

```ts
  int3 = varbit_artisan_rune_ores - int3;
```

- **dead store**

```ts
  int3 = 0;
```

- **dead store**

```ts
  int4 = varbit_artisan_coal - int4;
```

- **dead store**

```ts
  int4 = 0;
```

## cs2_593 (script 593)

- **dead store**

```ts
  int5 = stockmarketGetoffercount(intArg0);
```

## cs2_651 (script 651)

- **dead store**

```ts
  int4 = stockmarketGetoffertype(intArg0);
```

- **dead store**

```ts
  int6 = stockmarketGetoffercompletedcount(intArg0);
```

- **dead store**

```ts
  str1 = tostringLocalised(int5, 1);
```

- **dead store**

```ts
  int14 = cs2_625(intArg0);
```

- **dead store**

```ts
  int9 = int9 + 1;
```

## cs2_673 (script 673)

- **dead store**

```ts
  str1 = tostring_spacer(intArg2, ",");
```

- **dead store**

```ts
  int13 = int13 + 1;
```

- **dead store**

```ts
  int13 = int13 + 1;
```

## cs2_699 (script 699)

- **dead store**

```ts
  int2 = ccGetX();
```

## [proc,lightcombine_ratio] (script 715)

- **unused result** - no result is read

```ts
  [int4, int5, int6] = rgb_to_hsl(int7, int8, int9);
```

## cs2_736 (script 736)

- **dead store** - targets int slot 16, int slot 17

```ts
  [int16, int17] = [int12 + int14, int13 + int15];
```

## cs2_741 (script 741)

- **empty branch** - both arms are empty

```ts
  varc_scab_correct_choices_total != 12
```

## [clientscript,scab_thieve] (script 742)

- **dead store**

```ts
  int1 = 1;
```

## cs2_759 (script 759)

- **dead store**

```ts
  int8 = intArg4;
```

- **unused result** - targets int slot 21

```ts
  [int11, int12, int13, int14, int15, int16, int17, int18, int19, int20, int21, int22, int23, int24, int25, int26, int27, int28, int29, int30] = cs2_767(int32);
```

## cs2_765 (script 765)

- **dead store**

```ts
  int10 = intArg4;
```

## cs2_767 (script 767)

- **dead store**

```ts
  int1 = int1 + 1;
```

## [clientscript,lore_pouch_counter] (script 769)

- **unreachable** (instructions 66..66)

```ts
  return;
```

## cs2_789 (script 789)

- **dead store**

```ts
  int0 = random(3);
```

## cs2_802 (script 802)

- **dead store**

```ts
  int7 = 1;
```

- **dead store**

```ts
  int7 = 1;
```

- **dead store**

```ts
  int7 = 1;
```

- **dead store**

```ts
  int7 = 1;
```

- **dead store**

```ts
  int7 = 1;
```

- **dead store**

```ts
  int7 = 1;
```

- **dead store**

```ts
  int7 = 1;
```

- **dead store**

```ts
  int7 = 1;
```

- **dead store**

```ts
  int7 = 1;
```

- **dead store**

```ts
  int7 = 1;
```

- **dead store**

```ts
  int7 = 1;
```

- **dead store**

```ts
  int7 = 1;
```

- **dead store**

```ts
  int7 = 1;
```

## [proc,firedup_map_info] (script 813)

- **dead store**

```ts
  int1 = intArg0;
```

- **unused result** - targets int slot 3

```ts
  [int2, int3, int4] = cs2_814(intArg0);
```

## cs2_828 (script 828)

- **dead store**

```ts
  int0 = 0;
```

## cs2_846 (script 846)

- **dead store**

```ts
  int10 = ifGetModelAngleY(int2);
```

## cs2_936 (script 936)

- **dead store**

```ts
  int1 = ocParam(intArg0, Param.param_763);
```

## cs2_943 (script 943)

- **dead store**

```ts
  int4 = structParam(enumOp(type_int, type_struct, Enum.ql4_intstruct_lists, intArg0), Param.param_782);
```

## cs2_973 (script 973)

- **dead store**

```ts
  int9 = int8;
```

- **dead store**

```ts
  str1 = "null";
```

- **dead store**

```ts
  str1 = "";
```

- **dead store**

```ts
  int12 = 7620;
```

- **dead store**

```ts
  int13 = 0;
```

- **dead store**

```ts
  int9 = int8;
```

- **dead store**

```ts
  int12 = 7620;
```

- **dead store**

```ts
  str1 = "";
```

- **unused result** - targets string slot 0

```ts
  [str0, int14] = cs2_12(int0);
```

- **unused result** - no result is read

```ts
  [str0, int15] = cs2_13(int0, int11);
```

- **unused result** - targets string slot 2

```ts
  [int10, int9, str2, str1] = cs2_1567(int0, int11, int16);
```

- **unused result** - targets string slot 2

```ts
  [int10, int12, str2, str1] = cs2_14(int0, int11, int16);
```

## [proc,quickchat_tutorial_displaydata] (script 1033)

- **dead store**

```ts
  int2 = ifGetHeight(Component.interface_157.component_157_25);
```

## [clientscript,quickchat_onkey] (script 1059)

- **dead store**

```ts
  int8 = 0;
```

- **dead store**

```ts
  int9 = chatPhraseGetautoresponsecount(intArg5);
```

- **dead store**

```ts
  int8 = 0;
```

## [proc,clanwars_setup_createbox] (script 1086)

- **dead store**

```ts
  int6 = int6 + 1;
```

## cs2_1088 (script 1088)

- **dead store**

```ts
  int2 = ifGetWidth(intArg0);
```

- **dead store**

```ts
  int3 = ifGetHeight(intArg0);
```

- **dead store**

```ts
  int4 = int4 + 1;
```

## [proc,lobby_friends_join] (script 1182)

- **unused result** - targets int slot 1, int slot 1, int slot 1, string slot 0, string slot 0

```ts
  [int2, int1, int1, int1, str0, str0, str1] = worldListSpecific(intArg0);
```

## cs2_1212 (script 1212)

- **dead store**

```ts
  int2 = int2 + 1;
```

## cs2_1249 (script 1249)

- **unused result** - targets int slot 5

```ts
  [int4, int5] = cs2_1239(0);
```

- **unused result** - targets int slot 4

```ts
  [int4, int5] = cs2_1239(1);
```

## cs2_1328 (script 1328)

- **unused result** - targets string slot 1

```ts
  [str0, str1] = friendGetName(int2);
```

## cs2_1381 (script 1381)

- **dead store**

```ts
  int1 = cs2_1416(66650277, int1);
```

- **dead store**

```ts
  int0 = cs2_1403(66650277, int0);
```

## cs2_1456 (script 1456)

- **dead store**

```ts
  int4 = invSize(95);
```

## cs2_1465 (script 1465)

- **dead store**

```ts
  str2 = "Total number of " + "<col=ba1626>" + "demo" + "</col>" + " bank slots used";
```

## cs2_1467 (script 1467)

- **unused result** - targets int slot 1

```ts
  [int1, int2] = cs2_1467(9);
```

- **unused result** - targets int slot 3

```ts
  [int3, int2] = cs2_1467(1);
```

## cs2_1593 (script 1593)

- **dead store**

```ts
  int16 = int11;
```

## [proc,friendschat_kick] (script 1633)

- **dead store**

```ts
  strArg0 = cs2_1814(strArg0);
```

## cs2_1664 (script 1664)

- **unused result** - no result is read

```ts
  [int4, int5] = cs2_1467(varbit_4893);
```

## [proc,clanwars_updateside] (script 1784)

- **unused result** - targets int slot 2

```ts
  [int0, int2] = clanwars_updateside_textbox(structParam(enumOp(type_int, type_struct, Enum.clanwars_arena_options, varc_clanwars_rulevarc_arenachoice), Param.clanwars_arena_name), int0, int2, int1, 0);
```

## cs2_1930 (script 1930)

- **unreachable** (instructions 23..24)

```ts
  return -1;
```

## cs2_1999 (script 1999)

- **empty branch** - both arms are empty

```ts
  videoAdvertHasFinished() != 0
```

- **empty branch** - both arms are empty

```ts
  videoAdvertPlay(5) != 1
```

- **empty branch** - both arms are empty

```ts
  videoAdvertPlay(1) != 1
```

- **empty branch** - both arms are empty

```ts
  videoAdvertPlay(2) != 1
```

- **empty branch** - both arms are empty

```ts
  videoAdvertPlay(3) != 1
```

- **empty branch** - both arms are empty

```ts
  videoAdvertPlay(4) != 1
```

## [proc,ql4_init_general] (script 2160)

- **dead store**

```ts
  int9 = cs2_2193(int5);
```

## [proc,ql4_sort] (script 2162)

- **dead store**

```ts
  int20 = int24;
```

## cs2_2164 (script 2164)

- **dead store**

```ts
  int2 = structParam(int1, Param.param_61);
```

## [proc,fishcomp_reward_tackle_box_refresh] (script 2186)

- **dead store**

```ts
  int1 = (ifGetWidth(int0) - 36 * 4) / 3;
```

- **dead store**

```ts
  int2 = (ifGetHeight(int0) - 32 * 7) / 6;
```

## cs2_2223 (script 2223)

- **unused result** - no result is read

```ts
  [int3, int4] = userflowflagsOp();
```

- **dead store**

```ts
  str0 = "Continue To Buy";
```

- **dead store**

```ts
  str0 = "Continue";
```

## [clientscript,rand_load_shop] (script 2260)

- **dead store**

```ts
  int2 = ifGetHeight(intArg0);
```

## [proc,topstat_prayer_button_update] (script 2303)

- **dead store**

```ts
  int0 = 8645;
```

## [clientscript,lobbyscreen_email_validation_timer] (script 2354)

- **dead store**

```ts
  int0 = detailGetSoundVol();
```

- **dead store**

```ts
  int1 = detailGetMusicVol();
```

- **dead store**

```ts
  int2 = detailGetBgsoundvol();
```

- **dead store**

```ts
  int3 = detailGetSpeechvol();
```

- **dead store**

```ts
  int4 = detailGetLoginVol();
```

## cs2_2519 (script 2519)

- **dead store**

```ts
  int1 = cs2_2520();
```

## cs2_2606 (script 2606)

- **dead store**

```ts
  int1 = invGetobj(93, varc_930);
```

- **dead store**

```ts
  int1 = invGetobj(93, varc_931);
```

- **dead store**

```ts
  int1 = invGetobj(93, varc_932);
```

- **dead store**

```ts
  int1 = invGetobj(93, varc_933);
```

- **dead store**

```ts
  int1 = invGetobj(93, varc_934);
```

- **dead store**

```ts
  int1 = invGetobj(93, varc_935);
```

- **dead store**

```ts
  int1 = invGetobj(93, varc_936);
```

- **dead store**

```ts
  int1 = invGetobj(93, varc_937);
```

- **dead store**

```ts
  int1 = invGetobj(93, varc_938);
```

- **dead store**

```ts
  int1 = invGetobj(93, varc_939);
```

## cs2_2642 (script 2642)

- **dead store**

```ts
  int9 = 9999;
```

## cs2_2643 (script 2643)

- **dead store**

```ts
  int9 = 9999;
```

## cs2_2646 (script 2646)

- **dead store**

```ts
  int2 = 1;
```

## cs2_2710 (script 2710)

- **unused result** - targets int slot 7

```ts
  [int6, int7] = cs2_1239(0);
```

## cs2_2713 (script 2713)

- **unused result** - targets int slot 7

```ts
  [int6, int7] = cs2_1239(0);
```

## cs2_2781 (script 2781)

- **unused result** - targets int slot 2, int slot 3

```ts
  [int1, int2, int3] = userDetailLobbyMembership();
```

## cs2_2786 (script 2786)

- **dead store**

```ts
  int6 = int6 + 1;
```

## cs2_2848 (script 2848)

- **dead store**

```ts
  int6 = intArg4;
```

- **dead store**

```ts
  int7 = intArg5;
```

## [clientscript,ii_storage_update] (script 2905)

- **dead store**

```ts
  int4 = ifGetLayer(intArg0);
```

## [clientscript,ii_storage_update_side] (script 2906)

- **dead store**

```ts
  int3 = ifGetLayer(intArg0);
```

## cs2_2914 (script 2914)

- **dead store**

```ts
  int3 = ifGetWidth(intArg0);
```

- **dead store**

```ts
  int4 = ifGetHeight(intArg0);
```

- **dead store**

```ts
  int5 = int5 + 1;
```

## cs2_2921 (script 2921)

- **dead store**

```ts
  int6 = 0;
```

- **dead store**

```ts
  int6 = 14798;
```

## [proc,login_popup_close] (script 2954)

- **dead store**

```ts
  int6 = 63897611;
```

- **dead store**

```ts
  int7 = 63897612;
```

- **dead store**

```ts
  int8 = 63897613;
```

- **dead store**

```ts
  int9 = 63897607;
```

- **dead store**

```ts
  int10 = 63897608;
```

- **dead store**

```ts
  int11 = 63897609;
```

## cs2_2968 (script 2968)

- **dead store**

```ts
  int1 = ifGetWidth(intArg0);
```

- **dead store**

```ts
  int2 = ifGetHeight(intArg0);
```

- **dead store**

```ts
  int3 = int3 + 1;
```

## [proc,lobbyscreen_entergame] (script 3062)

- **unused result** - targets int slot 1, int slot 2, int slot 4, string slot 0, string slot 1, string slot 2

```ts
  [int1, int2, int3, int4, str0, str1, str2] = worldListSpecific(mapWorld());
```

- **dead store**

```ts
  int8 = ifGetWidth(Component.interface_906.component_906_113);
```

## [clientscript,lobbyscreen_entergametimer] (script 3063)

- **unused result** - targets int slot 9, int slot 10, int slot 11, int slot 12, string slot 1, string slot 2

```ts
  [int9, int10, int11, int12, str1, str2, str3] = worldListSpecific(intArg1);
```

- **dead store**

```ts
  int9 = 0;
```

## cs2_3067 (script 3067)

- **unused result** - targets int slot 2, int slot 3, int slot 4, int slot 5, string slot 0, string slot 1

```ts
  [int2, int3, int4, int5, int6, str0, str1, str2] = worldListStart();
```

- **unused result** - targets int slot 2, int slot 3, int slot 4, int slot 5, string slot 0, string slot 1

```ts
  [int2, int3, int4, int5, int6, str0, str1, str2] = worldListNext();
```

## cs2_3116 (script 3116)

- **unused result** - targets int slot 1, int slot 2, int slot 3, string slot 0, string slot 1, string slot 2

```ts
  [int0, int1, int2, int3, str0, str1, str2] = worldListSpecific(mapWorld());
```

## cs2_3117 (script 3117)

- **dead store**

```ts
  int12 = 1;
```

- **dead store**

```ts
  int12 = 0;
```

- **dead store**

```ts
  int13 = 1;
```

- **dead store**

```ts
  int13 = 0;
```

## cs2_3137 (script 3137)

- **dead store**

```ts
  str0 = "Your changes cannot be saved because" + "<br>" + "you are using the unsigned client.";
```

## cs2_3139 (script 3139)

- **dead store**

```ts
  str0 = "Your changes cannot be saved because" + "<br>" + "you are using the unsigned client.";
```

## cs2_3237 (script 3237)

- **dead store**

```ts
  str2 = subString(strArg0, varc_1099, int5);
```

## [clientscript,rand_interface_update_v2] (script 3267)

- **unused result** - targets int slot 3, int slot 4

```ts
  [int36, int37, int38] = cs2_3273();
```

- **dead store**

```ts
  int5 = scale(varc_rand_display_stage - (int6 + int7 + int8 + int9 + int10 + int11 + int12) + 1, int13, 100);
```

- **unused result** - targets int slot 4, int slot 5

```ts
  [int36, int37, int38] = cs2_3273();
```

- **unused result** - targets int slot 5

```ts
  [int36, int37, int38] = cs2_3273();
```

## cs2_3276 (script 3276)

- **unused result** - targets int slot 3, int slot 4

```ts
  [int34, int35, int36] = cs2_3273();
```

- **unused result** - no result is read

```ts
  [int34, int35, int36] = cs2_3273();
```

- **unused result** - targets int slot 5

```ts
  [int34, int35, int36] = cs2_3273();
```

## [proc,levelup_unlocks] (script 3337)

- **dead store**

```ts
  int6 = enumOp(type_stat, type_stat, Enum.stat_f2p_list, int6);
```

- **dead store**

```ts
  int11 = ccGetX();
```

## cs2_3367 (script 3367)

- **dead store**

```ts
  int3 = ccGetX();
```

## [proc,autosetup] (script 3384)

- **unused result** - targets int slot 2

```ts
  [int1, int2] = autosetupDosetup();
```

## cs2_3489 (script 3489)

- **dead store**

```ts
  int0 = enumOp(type_int, type_enum, Enum.enum_3088, varc_rand_player_tab);
```

## cs2_3977 (script 3977)

- **unused result** - no result is read

```ts
  [int7, str3, int8] = task_requirements(intArg0);
```

## cs2_3989 (script 3989)

- **dead store**

```ts
  int7 = structParam(int3, Param.task_area);
```

## cs2_3991 (script 3991)

- **unused result** - targets int slot 3

```ts
  [int3, str2, int4] = task_requirements(intArg0);
```

- **dead store**

```ts
  int6 = int5;
```

- **dead store**

```ts
  int5 = cs2_5797(intArg0, -1, 8, 1, 9, 104, 60096609, 60096610, 60096562, 60096561, 60096623);
```

## [clientscript,task_side_tab] (script 4000)

- **dead store**

```ts
  str0 = "Click on the Hints tab for more on how to complete this Task.";
```

- **dead store**

```ts
  str0 = "Remember, the Hints tab provides more details about a Task.";
```

- **dead store**

```ts
  int1 = 196;
```

- **dead store**

```ts
  int3 = 1;
```

- **dead store**

```ts
  int2 = int2 + 40;
```

- **dead store**

```ts
  int1 = 217;
```

- **dead store**

```ts
  int2 = 177;
```

- **dead store**

```ts
  str0 = "Click on the Hints tab for more on how to complete this Task.";
```

- **dead store**

```ts
  str0 = "Remember, the Hints tab provides more details about a Task.";
```

- **dead store**

```ts
  int1 = 196;
```

- **dead store**

```ts
  int3 = 1;
```

- **dead store**

```ts
  int2 = int2 + 40;
```

- **dead store**

```ts
  int1 = 217;
```

- **dead store**

```ts
  int2 = 177;
```

- **dead store**

```ts
  str0 = "The '?' icon will add an arrow to the screen which points to your destination.";
```

- **dead store**

```ts
  int1 = 196;
```

- **dead store**

```ts
  int3 = 1;
```

- **dead store**

```ts
  int2 = int2 + 40;
```

- **dead store**

```ts
  int1 = 208;
```

- **dead store**

```ts
  int2 = 78;
```

- **dead store**

```ts
  str0 = "";
```

## cs2_4089 (script 4089)

- **dead store**

```ts
  int0 = 48889965;
```

## [proc,guesscolour] (script 4126)

- **dead store**

```ts
  str1 = "grey";
```

- **dead store**

```ts
  str1 = "Grey";
```

## cs2_4202 (script 4202)

- **dead store**

```ts
  int2 = structParam(intArg1, Param.rs3tli_button_layer_type);
```

- **dead store**

```ts
  int10 = structParam(intArg1, Param.aif_button_effect_sequence);
```

## cs2_4212 (script 4212)

- **dead store**

```ts
  int4 = ifGetX(intArg0);
```

- **dead store**

```ts
  int5 = ifGetY(intArg0);
```

## [proc,clansettings_interface_refresh] (script 4295)

- **dead store**

```ts
  int1 = 1;
```

- **dead store**

```ts
  int1 = 0;
```

## cs2_4297 (script 4297)

- **empty branch** - both arms are empty

```ts
  activeClanSettingsFindAffined() != 1
```

## [proc,clansettings_list_build] (script 4301)

- **dead store**

```ts
  int31 = int30 * 2;
```

- **dead store**

```ts
  int25 = int36 / 2 * int15;
```

## cs2_4319 (script 4319)

- **dead store**

```ts
  int2 = intArg0 - 1;
```

## [proc,clan_time_tostring] (script 4341)

- **dead store**

```ts
  str0 = tostring(intArg1);
```

- **dead store**

```ts
  str1 = tostring(intArg2);
```

## cs2_4344 (script 4344)

- **dead store**

```ts
  int17 = 0;
```

## cs2_4441 (script 4441)

- **dead store**

```ts
  str0 = append(str0, "To join a clan channel as a" + "<br>" + "guest, use the button in the top-left" + "<br>" + "and enter the name of" + "<br>" + "the clan you wish to chat in." + "<br>" + "<br>" + "To talk, start your chat with ///." + "<br>" + "If you belong to another clan, you" + "<br>" + "can still talk to them by" + "<br>" + "starting your chat with //.");
```

## cs2_4444 (script 4444)

- **dead store**

```ts
  int16 = ifGetHeight(int3) / int15;
```

## cs2_4462 (script 4462)

- **dead store**

```ts
  int8 = int0 * 19;
```

## cs2_4527 (script 4527)

- **dead store**

```ts
  int10 = structParam(intArg1, Param.aif_button_effect_sequence);
```

## cs2_4617 (script 4617)

- **dead store**

```ts
  int2 = intArg0;
```

- **dead store**

```ts
  int3 = intArg1;
```

## cs2_4618 (script 4618)

- **dead store**

```ts
  int3 = intArg1;
```

## [proc,dhat_statue_populate] (script 4632)

- **dead store**

```ts
  str5 = "over " + tostringLocalised(250, 1);
```

## cs2_4653 (script 4653)

- **unreachable** (instructions 1..113)

```ts
  int0 = 11337808;
  if (varbit_fremsaga_current_saga == 1) {
      goto("L3");
  }
  goto("L4");
  int0 = 11337808;
  if (varbit_fremsaga_current_saga == 2) {
      goto("L6");
  }
  goto("L7");
  int0 = 11337789;
  if (varbit_fremsaga_current_saga == 4) {
      goto("L9");
  }
  goto("L18");
  if (varbit_fremsaga_floorset == 1) {
      goto("L11");
  }
  goto("L12");
  int0 = 11337735;
  if (varbit_fremsaga_floorset == 2) {
      goto("L14");
  }
  goto("L15");
  int0 = 11337766;
  if (varbit_fremsaga_floorset == 3) {
      goto("L17");
  }
  goto("L18");
  int0 = 11337769;
  int1 = cs2_284(coord());
  int2 = (coordX(coord()) - coordX(int1)) / 16;
  int3 = (coordZ(coord()) - coordZ(int1)) / 16;
  switch (varbit_fremsaga_current_saga) {
      case 1:
          goto("L20");
  }
  goto("L21");
  int3 = int3 - 1;
  ccDeleteAll(int0);
  ccCreate(int0, 5, 0);
  ccSetSize(11, 11, 0, 0);
  ccSetGraphic(Graphic.rand_map_player_pips_0);
  ccSetPosition(int2 * 32 + 10, int3 * 32 + 10, 0, 2);
  ccCreate(int0, 5, 1);
  ccSetSize(11, 11, 0, 0);
  ccSetGraphic(Graphic.rand_map_player_pips_1);
  ccSetPosition(int2 * 64 + 10, int3 * 64 + 10, 0, 2);
  return;
```

## cs2_4690 (script 4690)

- **dead store**

```ts
  int3 = varc_1549;
```

- **dead store**

```ts
  int3 = varc_1550;
```

- **dead store**

```ts
  int3 = varc_1551;
```

- **dead store**

```ts
  int3 = varc_1552;
```

## cs2_4717 (script 4717)

- **dead store**

```ts
  str0 = structParam(int7, Param.emotes2_name);
```

## cs2_4723 (script 4723)

- **dead store**

```ts
  int2 = structParam(int13, Param.clan_build_req_wall_tier);
```

- **dead store**

```ts
  int3 = structParam(int13, Param.clan_build_req_warehouse_tier);
```

- **dead store**

```ts
  int4 = structParam(int13, Param.clan_build_req_battlefield_tier);
```

## cs2_4724 (script 4724)

- **dead store**

```ts
  int26 = -1;
```

- **dead store**

```ts
  int26 = -1;
```

## [proc,clan_keep_theatre_actors_refresh_client] (script 4725)

- **dead store**

```ts
  str0 = "Add someone to the actor list. Click this, then click on the person you would like to add.";
```

## cs2_4726 (script 4726)

- **dead store**

```ts
  int16 = 0;
```

## cs2_4727 (script 4727)

- **dead store**

```ts
  int13 = 0;
```

## cs2_4731 (script 4731)

- **unused result** - targets int slot 2, int slot 4

```ts
  [int2, int3, int4] = getMousebuttons();
```

## cs2_4762 (script 4762)

- **dead store**

```ts
  int7 = structParam(intArg1, Param.param_1387);
```

## cs2_4768 (script 4768)

- **unused result** - no result is read

```ts
  [int0, int1, int2, int3, int4, int5, int6, int7] = cs2_4769(3, int0, int1, int2, int3, int4, int5, int6, int7);
```

## cs2_4769 (script 4769)

- **dead store**

```ts
  int9 = cs2_4771(int13, int9);
```

## cs2_4770 (script 4770)

- **unused result** - targets int slot 16, int slot 17

```ts
  [int11, str1, int12, int13, int14, int15, int16, int17] = clan_build_job_info(intArg2);
```

## cs2_4777 (script 4777)

- **dead store**

```ts
  int38 = intArg0;
```

- **unused result** - targets int slot 6, int slot 40, int slot 41

```ts
  [int3, str0, int4, int5, int6, int39, int40, int41] = clan_build_job_info(intArg0);
```

- **unused result** - targets int slot 38

```ts
  [int14, int15, int16, int17, int18, int19, int20, int21, int22, int23, int24, int25, int26, int27, int28, int29, int30, int31, int32, int33, int34, int35, int36, int37, int38] = cs2_4794(intArg2, int7, int8, int9, int10, int11, int12, int13);
```

## [proc,clan_build_job_info] (script 4791)

- **dead store**

```ts
  int9 = cs2_4820(int5);
```

## [proc,clan_build_job_cost] (script 4792)

- **unused result** - targets int slot 21, string slot 0, int slot 11, int slot 12, int slot 13, int slot 9

```ts
  [int15, str0, int16, int17, int18, int19, int20, int21] = clan_build_job_info(intArg0);
```

## cs2_4795 (script 4795)

- **unused result** - targets int slot 28, string slot 0, int slot 33

```ts
  [int21, str0, int22, int23, int24, int25, int26, int27] = clan_build_job_info(int20);
```

## cs2_4797 (script 4797)

- **unused result** - targets int slot 21, string slot 0, int slot 2, int slot 3, int slot 4

```ts
  [int21, str0, int5, int6, int7, int2, int3, int4] = clan_build_job_info(int0);
```

## cs2_4804 (script 4804)

- **dead store**

```ts
  int3 = -1;
```

## cs2_4805 (script 4805)

- **unused result** - targets int slot 3, int slot 4, int slot 5, int slot 6, int slot 7, int slot 8, int slot 9, int slot 10, int slot 11, int slot 12

```ts
  [int2, int3, int4, int5, int6, int7, int8, int9, int10, int11, int12] = cs2_4818(intArg0, intArg1);
```

## cs2_4806 (script 4806)

- **dead store**

```ts
  int18 = -1;
```

## cs2_4815 (script 4815)

- **unused result** - targets int slot 3, int slot 4, int slot 5, int slot 6, int slot 7, int slot 8, int slot 9, int slot 10, int slot 11, int slot 12

```ts
  [int2, int3, int4, int5, int6, int7, int8, int9, int10, int11, int12] = cs2_4818(intArg0, intArg1);
```

## cs2_4816 (script 4816)

- **unused result** - targets int slot 3, int slot 4, int slot 5, int slot 6, int slot 7, int slot 8, int slot 9, int slot 10, int slot 11, int slot 12

```ts
  [int2, int3, int4, int5, int6, int7, int8, int9, int10, int11, int12] = cs2_4818(intArg0, intArg1);
```

## cs2_4817 (script 4817)

- **unused result** - targets int slot 2, int slot 3, int slot 4, int slot 5, int slot 6, int slot 7, int slot 8, int slot 9, int slot 11, int slot 12

```ts
  [int2, int3, int4, int5, int6, int7, int8, int9, int10, int11, int12] = cs2_4818(intArg0, intArg1);
```

## [proc,cs2_d_underage] (script 4835)

- **dead store**

```ts
  int1 = cs2_4817(varbit_clan_custom_slot_1_tier_varp, 2);
```

- **dead store**

```ts
  int2 = cs2_4817(varbit_clan_custom_slot_1_tier_varp, 3);
```

- **dead store**

```ts
  int1 = cs2_4817(varbit_clan_custom_slot_2_tier_varp, 2);
```

- **dead store**

```ts
  int2 = cs2_4817(varbit_clan_custom_slot_2_tier_varp, 3);
```

- **dead store**

```ts
  int1 = cs2_4817(varbit_clan_custom_slot_3_tier_varp, 2);
```

- **dead store**

```ts
  int2 = cs2_4817(varbit_clan_custom_slot_3_tier_varp, 3);
```

## cs2_4841 (script 4841)

- **dead store**

```ts
  int3 = varbit_clan_custom_slot_1_destination_id_varp;
```

- **dead store**

```ts
  int3 = varbit_clan_custom_slot_2_destination_id_varp;
```

- **dead store**

```ts
  int3 = varbit_clan_custom_slot_3_destination_id_varp;
```

- **dead store**

```ts
  int9 = -1;
```

## cs2_4864 (script 4864)

- **unused result** - targets int slot 32, string slot 0, int slot 34, int slot 35, int slot 38

```ts
  [int32, str0, int33, int34, int35, int36, int37, int38] = clan_build_job_info(int4);
```

## cs2_4866 (script 4866)

- **dead store**

```ts
  int13 = pushVarClanBit<2581>();
```

## cs2_4880 (script 4880)

- **dead store**

```ts
  int6 = cs2_5171(int7);
```

- **dead store**

```ts
  int5 = 12303291;
```

- **dead store**

```ts
  int5 = 2236962;
```

## cs2_4914 (script 4914)

- **dead store**

```ts
  int1 = cs2_4824(intArg0);
```

## [clientscript,clan_stronghold_main_refresh_countdown] (script 4920)

- **empty branch** - both arms are empty

```ts
  varc_1557 == 0 && varc_1558 < 6 && varc_1558 == 0 && varc_1559 < 20
```

## cs2_4965 (script 4965)

- **dead store**

```ts
  int1 = cs2_4948(intArg0);
```

## cs2_4981 (script 4981)

- **dead store**

```ts
  int16 = enumOp(type_int, type_struct, Enum.clan_build_skillplot_costs, int14);
```

- **dead store**

```ts
  int10 = enumOp(type_int, type_struct, Enum.clan_build_skillplot_costs, int9);
```

- **dead store**

```ts
  int13 = enumOp(type_int, type_struct, Enum.clan_build_skillplot_costs, int11);
```

- **dead store**

```ts
  int24 = 0;
```

- **dead store**

```ts
  int24 = 1;
```

- **dead store**

```ts
  str6 = "You do not have permission from your clan to do that.";
```

- **dead store**

```ts
  str7 = "You do not have permission from your clan to do that.";
```

## cs2_4987 (script 4987)

- **unused result** - no result is read

```ts
  [int1, int2, int3, int4, int5] = cs2_4958(int6);
```

## cs2_4989 (script 4989)

- **unused result** - targets int slot 2, int slot 4, int slot 5, int slot 3

```ts
  [int1, int2, int3, int4, int5] = cs2_4958(intArg0);
```

## cs2_4990 (script 4990)

- **unused result** - targets int slot 4, int slot 5, int slot 3

```ts
  [int1, int2, int3, int4, int5] = cs2_4958(intArg0);
```

## cs2_4996 (script 4996)

- **dead store**

```ts
  int3 = cs2_4959(int1);
```

## [clientscript,clan_stronghold_main_next_building_button] (script 5005)

- **dead store**

```ts
  int3 = pushVarClanBit<2580>();
```

## [clientscript,clan_stronghold_main_previous_building_button] (script 5006)

- **dead store**

```ts
  int3 = pushVarClanBit<2580>();
```

## cs2_5145 (script 5145)

- **unreachable** (instructions 65..66)

```ts
  return 0;
```

## cs2_5147 (script 5147)

- **unreachable** (instructions 65..66)

```ts
  return 0;
```

## cs2_5194 (script 5194)

- **unused result** - targets int slot 1

```ts
  [int0, int1] = cs2_5172();
```

- **unused result** - targets int slot 1

```ts
  [int0, int1] = cs2_5173();
```

- **unused result** - targets int slot 1

```ts
  [int0, int1] = cs2_5174();
```

## cs2_5315 (script 5315)

- **dead store**

```ts
  int7 = enumGetoutputcount(Enum.clan_theatre_sounds_int2string);
```

- **dead store**

```ts
  int8 = enumGetoutputcount(Enum.clan_theatre_sounds_int2vorbis);
```

## cs2_5350 (script 5350)

- **unused result** - targets int slot 32, int slot 23

```ts
  [int32, int23, int24, int31, int21] = cs2_5352(int32, int23, int24, int31, int22, int21, int33, int27, int28, int29);
```

- **unused result** - targets int slot 32, int slot 23

```ts
  [int32, int23, int24, int31, int21] = cs2_5353(int32, int23, int24, int31, int22, int21, int33, int27, int28, int29);
```

- **unused result** - targets int slot 32, int slot 23

```ts
  [int32, int23, int24, int31, int21] = cs2_4727(int32, int23, int24, int31, int22, int21, int33, int27, int28, int29);
```

- **unused result** - targets int slot 32, int slot 23

```ts
  [int32, int23, int24, int31, int21] = cs2_4344(int32, int23, int24, int31, int22, int21, int33, int27, int28, int29);
```

- **unused result** - targets int slot 32, int slot 23

```ts
  [int32, int23, int24, int31, int21] = cs2_6000(int32, int23, int24, int31, int22, int21, int33, int27, int28, int29);
```

- **unused result** - targets int slot 32, int slot 23

```ts
  [int32, int23, int24, int31, int21] = cs2_4726(int2, int32, int23, int24, int31, int22, int21, int33, int27, int28, int29);
```

- **dead store**

```ts
  int21 = int21 + 1;
```

## cs2_5351 (script 5351)

- **dead store**

```ts
  int33 = int33 + 1;
```

## cs2_5352 (script 5352)

- **dead store**

```ts
  int13 = 0;
```

## cs2_5353 (script 5353)

- **dead store**

```ts
  int13 = 0;
```

## cs2_5392 (script 5392)

- **dead store**

```ts
  int3 = ifGetWidth(intArg0);
```

- **dead store**

```ts
  int4 = ifGetHeight(intArg0);
```

- **dead store**

```ts
  int5 = int5 + 1;
```

- **dead store**

```ts
  int5 = int5 + 1;
```

- **dead store**

```ts
  int5 = int5 + 1;
```

## [clientscript,dom_battle_overlay_setup] (script 5461)

- **dead store**

```ts
  int0 = int0 + 1;
```

## cs2_5486 (script 5486)

- **unused result** - targets int slot 2

```ts
  [int1, int2] = getMinimenuLength();
```

## [proc,tli_optext_build_tooltip] (script 5490)

- **dead store**

```ts
  int3 = int3 + 1;
```

- **dead store**

```ts
  int3 = int3 + 1;
```

## cs2_5511 (script 5511)

- **unused result** - targets int slot 2

```ts
  [int3, int4, int5] = getEntityScreenPosition(varc_1696);
```

- **unused result** - targets int slot 7, int slot 8, int slot 9, int slot 10

```ts
  [int6, int7, int8, int9, int10] = getEntityBoundingBox();
```

## cs2_5512 (script 5512)

- **unused result** - targets int slot 2

```ts
  [int3, int4, int5] = getEntityScreenPosition(varc_1696);
```

- **unused result** - targets int slot 7, int slot 8, int slot 9, int slot 10

```ts
  [int6, int7, int8, int9, int10] = getEntityBoundingBox();
```

## cs2_5513 (script 5513)

- **unused result** - targets int slot 2

```ts
  [int3, int4, int5] = getLocScreenPosition(varc_1696);
```

- **unused result** - targets int slot 7, int slot 8, int slot 9, int slot 10

```ts
  [int6, int7, int8, int9, int10] = getLocBoundingBox();
```

## cs2_5530 (script 5530)

- **dead store**

```ts
  int4 = int4 + 1;
```

## cs2_5531 (script 5531)

- **dead store**

```ts
  int4 = int4 + 1;
```

## cs2_5560 (script 5560)

- **dead store**

```ts
  int1 = intArg0;
```

- **dead store**

```ts
  int1 = invTotal(623, 995);
```

## cs2_5580 (script 5580)

- **dead store**

```ts
  str1 = "Select an item to buy";
```

- **dead store**

```ts
  str1 = "1.2k Thieving XP (x" + tostring(varbit_rden2_selected_quantity) + ")";
```

- **dead store**

```ts
  str1 = "12k Thieving XP (x" + tostring(varbit_rden2_selected_quantity) + ")";
```

- **dead store**

```ts
  str1 = "120k Thieving XP (x" + tostring(varbit_rden2_selected_quantity) + ")";
```

- **dead store**

```ts
  str1 = "1.2M Thieving XP (x" + tostring(varbit_rden2_selected_quantity) + ")";
```

- **dead store**

```ts
  str1 = "950 Agility XP (x" + tostring(varbit_rden2_selected_quantity) + ")";
```

- **dead store**

```ts
  str1 = "9.5k Agility XP (x" + tostring(varbit_rden2_selected_quantity) + ")";
```

- **dead store**

```ts
  str1 = "95k Agility XP (x" + tostring(varbit_rden2_selected_quantity) + ")";
```

- **dead store**

```ts
  str1 = "950k Agility XP (x" + tostring(varbit_rden2_selected_quantity) + ")";
```

- **dead store**

```ts
  str1 = "Factory mask";
```

- **dead store**

```ts
  str1 = "Factory body";
```

- **dead store**

```ts
  str1 = "Factory legs";
```

- **dead store**

```ts
  str1 = "Factory gloves";
```

- **dead store**

```ts
  str1 = "Factory boots";
```

- **dead store**

```ts
  str1 = "Vintage Rogues' Den multitool kit";
```

## cs2_5623 (script 5623)

- **dead store**

```ts
  int1 = 1800;
```

- **dead store**

```ts
  int1 = 2 * (ifGetHeight(Component.fmc_torch.base) / 3);
```

## [proc,xp_onstattransmit] (script 5662)

- **dead store**

```ts
  int43 = varp_1969;
```

- **dead store**

```ts
  int45 = varp_1994;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 1);
```

- **dead store**

```ts
  int43 = varp_1970;
```

- **dead store**

```ts
  int45 = varp_1995;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 2);
```

- **dead store**

```ts
  int43 = varp_1973;
```

- **dead store**

```ts
  int45 = varp_1998;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 5);
```

- **dead store**

```ts
  int43 = varp_1971;
```

- **dead store**

```ts
  int45 = varp_1996;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 3);
```

- **dead store**

```ts
  int43 = varp_1975;
```

- **dead store**

```ts
  int45 = varp_2000;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 7);
```

- **dead store**

```ts
  int43 = varp_1972;
```

- **dead store**

```ts
  int45 = varp_1997;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 4);
```

- **dead store**

```ts
  int43 = varp_1974;
```

- **dead store**

```ts
  int45 = varp_1999;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 6);
```

- **dead store**

```ts
  int43 = varp_1976;
```

- **dead store**

```ts
  int45 = varp_2001;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 8);
```

- **dead store**

```ts
  int43 = varp_1977;
```

- **dead store**

```ts
  int45 = varp_2002;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 9);
```

- **dead store**

```ts
  int43 = varp_1978;
```

- **dead store**

```ts
  int45 = varp_2003;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 10);
```

- **dead store**

```ts
  int43 = varp_1979;
```

- **dead store**

```ts
  int45 = varp_2004;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 11);
```

- **dead store**

```ts
  int43 = varp_1987;
```

- **dead store**

```ts
  int45 = varp_2012;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 19);
```

- **dead store**

```ts
  int43 = varp_1981;
```

- **dead store**

```ts
  int45 = varp_2006;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 13);
```

- **dead store**

```ts
  int43 = varp_1982;
```

- **dead store**

```ts
  int45 = varp_2007;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 14);
```

- **dead store**

```ts
  int43 = varp_1983;
```

- **dead store**

```ts
  int45 = varp_2008;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 15);
```

- **dead store**

```ts
  int43 = varp_1984;
```

- **dead store**

```ts
  int45 = varp_2009;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 16);
```

- **dead store**

```ts
  int43 = varp_1985;
```

- **dead store**

```ts
  int45 = varp_2010;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 17);
```

- **dead store**

```ts
  int43 = varp_1986;
```

- **dead store**

```ts
  int45 = varp_2011;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 18);
```

- **dead store**

```ts
  int43 = varp_1980;
```

- **dead store**

```ts
  int45 = varp_2005;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 12);
```

- **dead store**

```ts
  int43 = varp_1988;
```

- **dead store**

```ts
  int45 = varp_2013;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 20);
```

- **dead store**

```ts
  int43 = varp_1989;
```

- **dead store**

```ts
  int45 = varp_2014;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 21);
```

- **dead store**

```ts
  int43 = varp_1991;
```

- **dead store**

```ts
  int45 = varp_2016;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 23);
```

- **dead store**

```ts
  int43 = varp_1990;
```

- **dead store**

```ts
  int45 = varp_2015;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 22);
```

- **dead store**

```ts
  int43 = varp_1992;
```

- **dead store**

```ts
  int45 = varp_2017;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 24);
```

- **dead store**

```ts
  int43 = varp_1993;
```

- **dead store**

```ts
  int45 = varp_2018;
```

- **dead store**

```ts
  int44 = testBit(varp_1968, 25);
```

## [proc,skillguide_initialise] (script 5690)

- **dead store**

```ts
  int0 = 0;
```

- **unused result** - targets int slot 2

```ts
  [str0, int2, int0] = cs2_1023(enumOp(type_int, type_stat, Enum.int_to_stat, varc_skillguide_skill_clicked), int1);
```

## cs2_5734 (script 5734)

- **dead store**

```ts
  int1 = structParam(int0, Param.param_1268);
```

- **dead store**

```ts
  int4 = cs2_5739(1, ifGetY(Component.interface_1237.component_1237_0), str1, 81068032, 81068048);
```

## cs2_5735 (script 5735)

- **dead store**

```ts
  int3 = structParam(int1, Param.task_requirement_1_value);
```

- **dead store**

```ts
  int3 = structParam(int1, Param.task_requirement_2_value);
```

- **dead store**

```ts
  int3 = structParam(int1, Param.param_1299);
```

- **dead store**

```ts
  int3 = structParam(int1, Param.param_1301);
```

- **dead store**

```ts
  int3 = structParam(int1, Param.param_1303);
```

- **dead store**

```ts
  int3 = structParam(int1, Param.param_1305);
```

- **dead store**

```ts
  int3 = structParam(int1, Param.param_1307);
```

- **dead store**

```ts
  int3 = structParam(int1, Param.param_1309);
```

- **dead store**

```ts
  int3 = structParam(int1, Param.param_1311);
```

- **dead store**

```ts
  int3 = structParam(int1, Param.param_1313);
```

- **dead store**

```ts
  int3 = structParam(int1, Param.param_2228);
```

- **dead store**

```ts
  int3 = structParam(int1, Param.param_2230);
```

## cs2_5738 (script 5738)

- **dead store**

```ts
  int0 = cs2_5739(1, int0, str1, 81068037, 81068080);
```

- **dead store**

```ts
  int0 = cs2_5739(1, int0, "Explore the world of Runescape for new and exciting adventures!", 81068037, 81068080);
```

## cs2_5741 (script 5741)

- **dead store**

```ts
  int5 = enumOp(type_int, type_struct, Enum.enum_2252, int3);
```

## cs2_5746 (script 5746)

- **dead store**

```ts
  int9 = 0;
```

## cs2_5792 (script 5792)

- **dead store**

```ts
  int4 = cs2_5797(intArg0, -1, int2 - 1, 1, 2, 97, 79888390, 79888389, 79888391, 79888388, 79888399);
```

- **dead store**

```ts
  int5 = cs2_5798(intArg0, int1, 0, str0, 0, 79888398, 79888396, 79888399);
```

## cs2_5794 (script 5794)

- **dead store**

```ts
  int1 = task_get_data(intArg0);
```

## cs2_5799 (script 5799)

- **dead store**

```ts
  int7 = max(int6 + 5, 15 * paraheight(str1, int5, Graphic.verdana_11pt_regular));
```

## cs2_5815 (script 5815)

- **unused result** - no result is read

```ts
  [int0, int1] = cs2_5816(int0, int1, 19660905);
```

- **unused result** - no result is read

```ts
  [int0, int1] = cs2_5816(int0, int1, 19660817);
```

## cs2_5860 (script 5860)

- **dead store**

```ts
  int2 = 9923;
```

- **dead store**

```ts
  int2 = 9920;
```

- **dead store**

```ts
  int2 = 9922;
```

- **dead store**

```ts
  int2 = 9921;
```

## cs2_5861 (script 5861)

- **unused result** - targets int slot 1, int slot 2, int slot 3, int slot 4, string slot 0, string slot 1

```ts
  [int1, int2, int3, int4, str0, str1, str2] = worldListSpecific(intArg0);
```

- **dead store**

```ts
  int5 = worldListSwitch(intArg0, str2);
```

## cs2_5879 (script 5879)

- **dead store**

```ts
  int2 = max(0, varc_1800 + varbit_10862 + varbit_wof_earned_spins);
```

## cs2_5883 (script 5883)

- **dead store**

```ts
  int10 = structParam(int12, Param.param_2266);
```

- **dead store**

```ts
  int11 = structParam(int12, Param.param_2267);
```

## cs2_5894 (script 5894)

- **unused result** - targets int slot 4

```ts
  [int3, int4] = cs2_6188(intArg0);
```

## [clientscript,wof_reward_screen_delay] (script 5898)

- **dead store**

```ts
  int3 = cs2_5939(varc_1781);
```

## cs2_5902 (script 5902)

- **dead store**

```ts
  int5 = structParam(int4, Param.param_2268);
```

## cs2_5904 (script 5904)

- **unreachable** (instructions 5..27)

```ts
  int1 = ifGetX(intArg0);
  int1 = max(-162, int1 - 1);
  ifSetPosition(int1, -16, 1, 1, intArg0);
  if (int1 == -162) {
      goto("L3");
  }
  goto("L4");
  ifSetOnTimer(noHook(""), intArg0);
```

## cs2_5910 (script 5910)

- **dead store**

```ts
  int2 = 9943;
```

- **dead store**

```ts
  int2 = 9935;
```

- **dead store**

```ts
  int2 = 9931;
```

## cs2_5913 (script 5913)

- **dead store**

```ts
  int5 = 1;
```

## cs2_5938 (script 5938)

- **unused result** - targets int slot 2

```ts
  [int1, int2] = cs2_6188(intArg0);
```

## cs2_5948 (script 5948)

- **dead store**

```ts
  int4 = intArg2;
```

## cs2_5949 (script 5949)

- **dead store**

```ts
  int2 = 9939;
```

- **dead store**

```ts
  int2 = 9936;
```

- **dead store**

```ts
  int2 = 9938;
```

- **dead store**

```ts
  int2 = 9937;
```

## cs2_5964 (script 5964)

- **unreachable** (instructions 63..64)

```ts
  return 0;
```

## cs2_6000 (script 6000)

- **dead store**

```ts
  int17 = 0;
```

- **dead store**

```ts
  int17 = 0;
```

## cs2_6030 (script 6030)

- **unreachable** (instructions 36..37)

```ts
  return 0;
```

## [proc,shop_draw_list] (script 6087)

- **dead store**

```ts
  int9 = 0;
```

## cs2_6115 (script 6115)

- **dead store**

```ts
  int1 = 0;
```

- **dead store**

```ts
  int2 = 0;
```

## [clientscript,fremsaga_bilrach_mind_probe_create] (script 6135)

- **dead store**

```ts
  int6 = ccGetWidth();
```

## [proc,fremsaga_bilrach_mind_build_layers] (script 6138)

- **dead store**

```ts
  int17 = int14 - int14 / 20;
```

- **dead store**

```ts
  int18 = int14 + int14 / 20;
```

- **dead store**

```ts
  int19 = 0 - int19;
```

## cs2_6147 (script 6147)

- **unused result** - targets int slot 13, int slot 14

```ts
  [int7, int8, int9, int10] = cs2_6150(intArg1, intArg4, moveCoord(int5, 1, 0, 1));
```

## cs2_6168 (script 6168)

- **dead store**

```ts
  int1 = cs2_6178(int1, 83427343);
```

## cs2_6181 (script 6181)

- **dead store**

```ts
  int0 = -1;
```

- **dead store**

```ts
  int2 = -1;
```

## [clientscript,rcsiphonxp_recol_model] (script 6182)

- **dead store**

```ts
  int3 = -1;
```

## cs2_6215 (script 6215)

- **dead store**

```ts
  int0 = cs2_6233(0, 84082701);
```

## [proc,mtxrecol_recolour16_int_v2] (script 6234)

- **dead store**

```ts
  int3 = int3 + 1;
```

- **dead store**

```ts
  int2 = -1;
```

## [proc,fishcomp_rewards_shop_refresh] (script 6263)

- **unused result** - targets int slot 5, int slot 6, int slot 7

```ts
  [int0, int1, int2, int3, int4, int5, int6, int7] = cs2_6264();
```

## cs2_6306 (script 6306)

- **dead store**

```ts
  int1 = random(150);
```

- **dead store**

```ts
  int2 = random(50);
```

## cs2_6307 (script 6307)

- **dead store**

```ts
  int4 = random(150) + 150;
```

- **dead store**

```ts
  int5 = random(50) + 150;
```

## cs2_6308 (script 6308)

- **dead store**

```ts
  int4 = 255 / int3 + 1;
```

## cs2_6368 (script 6368)

- **unused result** - targets int slot 3

```ts
  [int2, int3] = cs2_6348(intArg0);
```

## [proc,ss_tab_click] (script 6400)

- **dead store**

```ts
  int4 = -1;
```

- **dead store**

```ts
  int5 = -1;
```

- **dead store**

```ts
  int6 = -1;
```

## cs2_6454 (script 6454)

- **dead store**

```ts
  int0 = 3872;
```

## [proc,mtxmgt_build_recolours] (script 6457)

- **dead store**

```ts
  int3 = int3 + 1;
```

- **dead store**

```ts
  int2 = -1;
```

## cs2_6490 (script 6490)

- **dead store**

```ts
  int0 = cs2_6233(0, 86179852);
```

## cs2_6492 (script 6492)

- **dead store**

```ts
  int3 = int3 + 1;
```

- **dead store**

```ts
  int2 = -1;
```

## cs2_6506 (script 6506)

- **dead store**

```ts
  int3 = invGetNum(665, varbit_10860);
```

- **dead store**

```ts
  str0 = "You must claim or discard your prize before spinning again.";
```

## cs2_6509 (script 6509)

- **unreachable** (instructions 1..49)

```ts
  int1 = -1;
  if (intArg0 == 0) {
      goto("L3");
  }
  goto("L4");
  int1 = 85327894;
  goto("L10");
  if (intArg0 == 1) {
      goto("L6");
  }
  goto("L7");
  int1 = 85327900;
  goto("L10");
  if (intArg0 == 2) {
      goto("L9");
  }
  goto("L10");
  int1 = 85327886;
  int2 = -1;
  switch (ifGetGraphic(int1)) {
      case 11642:
          goto("L12");
      case 11630:
          goto("L13");
      case 11633:
          goto("L14");
      case 11627:
          goto("L15");
      case 11636:
          goto("L16");
      case 11639:
          goto("L17");
  }
  goto("L18");
  int2 = 11643;
  goto("L18");
  int2 = 11631;
  goto("L18");
  int2 = 11634;
  goto("L18");
  int2 = 11628;
  goto("L18");
  int2 = 11637;
  goto("L18");
  int2 = 11640;
  ifSetGraphic(int2, int1);
  return;
```

## cs2_6510 (script 6510)

- **unreachable** (instructions 1..49)

```ts
  int1 = -1;
  if (intArg0 == 0) {
      goto("L3");
  }
  goto("L4");
  int1 = 85327894;
  goto("L10");
  if (intArg0 == 1) {
      goto("L6");
  }
  goto("L7");
  int1 = 85327900;
  goto("L10");
  if (intArg0 == 2) {
      goto("L9");
  }
  goto("L10");
  int1 = 85327886;
  int2 = -1;
  switch (ifGetGraphic(int1)) {
      case 11643:
          goto("L12");
      case 11631:
          goto("L13");
      case 11634:
          goto("L14");
      case 11628:
          goto("L15");
      case 11637:
          goto("L16");
      case 11640:
          goto("L17");
  }
  goto("L18");
  int2 = 11642;
  goto("L18");
  int2 = 11630;
  goto("L18");
  int2 = 11633;
  goto("L18");
  int2 = 11627;
  goto("L18");
  int2 = 11636;
  goto("L18");
  int2 = 11639;
  ifSetGraphic(int2, int1);
  return;
```

## cs2_6563 (script 6563)

- **dead store**

```ts
  int1 = ifGetY(intArg0);
```

- **dead store**

```ts
  int2 = ifGetX(intArg0);
```
