# Drynor Guide cache import

The Guide uses a new NPC definition, ID `16000`, cloned from Drynor NPC `3088` and renamed `Drynor Guide`, plus Gielenor interface archive `3073` (index `3`, 722 components). The Gielenor checkout at `A:/dev/gielenor/gielenor-source/data/cache` is a read-only donor. No Gielenor NPC ID is reused.

Build on an **exact copy of the packed cache mounted by production**. For the 2026-10-31 import, its `main_file_cache.dat2` SHA-256 was `1211bea5f5930f997e1a87665d2e004eeee1540fb7ae9da1f228251b23bcd0e3`. Never build the runtime cache from an older `cache-unpacked` tree.

The resulting packed `main_file_cache.dat2` SHA-256 is `0531cda029318b0ab819dd474a7e69f8c04e0011cd7e609568cdecf1afe1e2c1`. The deployed world commit is `b4f0a58`.

1. Unpack the production cache into an isolated source tree with `cache unpack <tree> <production-packed>`.
2. Import only interface archive `3073` with `cache import-archive 3 3073 <Gielenor-packed> <tree>`. Confirm archive `3073` is absent in the production tree first.
3. Clone `config_npc/3088.json` to `config_npc/16000.json`. Change `id` to `16000`, `name` to `Drynor Guide`, and set the fourth option and `rawOptions` entry to `Teleport`. Add archive `125` to `config_npc/index.json` with gzip compression; check that ID and archive are unused first.
4. Pack into a separate directory. Use `cache compare <production-packed> <new-packed>` to verify that **only** index `3` archive `3073` and index `18` archive `125` were added. In this import, the CS2 round trip changed index `12` archive `5538`; `cache preserve-archive 12 5538 <production-packed> <tree>` retained its original container, followed by `cache build <tree> <new-packed>` and a clean comparison. Run `cache verify <tree> <new-packed>` and read back the NPC name, options, and interface component count.
5. Promote the new packed cache only with lobby and world stopped and a verified rollback copy available. Validate login, Guide option, interface navigation, teleport, and return to world.

The world implementation owns destinations and excludes unvalidated bosses, raids, and Wilderness locations. Changes to the donor cache must be reviewed separately from this import.

## Appearance correction (2026-11-01)

The initial NPC `16000` used Drynor `3088` (Ronan), which rendered as a fairy in the client. Gielenor `2253` is the Wise Old Man with a renamed label; Drynor's native `2253` already has the Wise Old Man model. The corrected `16000` copies native `2253` (model `49461`, dialogue model `49476`, render emote `4`), then changes only its ID, name and fourth option to `Teleport` in both `options` and `rawOptions`. Do not replace native NPC `2253` or import unrelated donor models.

Build this correction from the exact cache deployed above, in an isolated source and packed output. The resulting `main_file_cache.dat2` SHA-256 is `7c034c97454c45ccd4370a03a0726b662ff11b08b44bdabbd2d15cbcb9254102`. A container comparison with the prior packed cache found **only index 18, archive 125** changed. The full `cache verify` passed through models and other early indices but stopped on pre-existing clientscripts without source (`cs2_0.ts`); it is not a complete verification result. Confirm the appearance and Teleport interaction in a real client after deployment.
