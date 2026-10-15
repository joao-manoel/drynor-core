---
name: drynor-shops
description: Identify, wire and repair Drynor shops using inventory definitions, shop JSON, NPC options and spawn evidence. Use for dead Trade clicks, shop stock and identifying raw inventory dumps.
---

# Shops

Resolve the world checkout and read ShopsHandler plus the matching NPC click route. Confirm the current JSON schema from a working sibling shop before editing. The shop filename/key must agree with any explicit openShop call.

Follow the evidence chain:

1. Identify the inventory in the active gameval catalog, then read its real ids and amounts from the source definition/current InventoryDecoder. A generic inv is not necessarily a shop.
2. Confirm the shopkeeper's definition has the intended Trade/Shop option and that its spawn exists at the reported location.
3. Confirm how npcIds or an explicit handler maps that NPC to the shop key. A missing key may fail silently.
4. Preserve currency, restock amounts, buyOnly/generalStore flags and Drynor-specific prices/restrictions. Zero stock can intentionally mean sell-to-only.
5. Check for an existing identical shop before adding another. Do not delete an unidentified dump until its identity and callers are established within the user's task.

Use meaningful shop names rather than Inventory N or raw gameval placeholders. A cross-revision catalog name is a lead requiring confirmation against 727 contents. Twin locations can require separate shops despite identical stock.

Shop data may load only at startup: confirm the loader and restart the local world when authorized. Validate the Trade click and inventory contents. Data-only edits do not require an unrelated full build, but any changed engine/loader must satisfy repository checks.
