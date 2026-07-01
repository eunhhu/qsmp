# Datapacks

Locked Modrinth datapacks are downloaded here automatically from
`../datapacks.lock` when the world manager runs. You can also place local
datapack ZIP files or unpacked datapack directories here. Each pack must contain
`pack.mcmeta` at its root.

Before every server start, managed packs are validated and synchronized into
the active world's `datapacks` directory. Removing a pack from this directory
also removes that managed copy from the world on the next synchronization.

Datapacks can execute server commands. Only install packs from sources you
trust and confirm that they support Minecraft Java Edition 26.1.2.

## Managed adventure world set

The current server uses:

- Terralith 2.6.2: expanded Overworld biomes and features
- Terratonic 3.0.22: Tectonic terrain shaping adapted for Terralith
- Continents 1.1.13: ocean-separated large landmasses for long expeditions
- Incendium 5.4.12: overhauled Nether biomes, structures, and progression
- Nether Expanse Incendium 1.0: Incendium-compatible open Nether ceiling exploration
- Dungeons and Taverns 5.2.0: dungeons, taverns, and adventure structures
- Explorify 1.6.5: additional vanilla-style exploration structures
- Structory 1.3.15: atmospheric ruins, towers, and settlements
- Nullscape 1.2.18: overhauled End terrain
- True Ending 1.1.4d: Ender Dragon phases, particles, attacks, and final hit
- True Ending 26 Compat: fixes 26.1 `time_check` predicate schema for True Ending
- QSMP Rules: skips the night when 50% of online players are sleeping

Stellarity is intentionally not installed because its full datapack also changes
dragon functions, loot, advancements, and boss mechanics. That conflicts with the
server's True Ending boss-fight requirement. The official True Ending music pack
is served as an optional server resource pack so failed downloads do not block
joining.

Exact Modrinth version IDs and SHA-512 hashes are recorded in
`../datapacks.lock`; missing locked ZIPs are fetched from Modrinth and verified
before injection. The numeric filename prefixes preserve the intended priority:
Terratonic loads after Terralith. QSMP Rules and compatibility overrides are
tracked directly as source files instead of as downloaded ZIPs.

These packs affect world generation. Update them only while the server is
stopped and back up the world first. Do not remove Terralith or Terratonic from
an existing generated world.
