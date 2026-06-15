# Datapacks

Place datapack ZIP files or unpacked datapack directories here. Each pack must
contain `pack.mcmeta` at its root.

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
- Dungeons and Taverns 5.2.0: dungeons, taverns, and adventure structures
- Explorify 1.6.5: additional vanilla-style exploration structures
- Structory 1.3.15: atmospheric ruins, towers, and settlements
- Nullscape 1.2.18: overhauled End terrain
- QSMP Rules: skips the night when 50% of online players are sleeping

Exact Modrinth version IDs and SHA-512 hashes are recorded in
`../datapacks.lock`. The numeric filename prefixes preserve the intended
priority: Terratonic loads after Terralith. QSMP Rules is tracked directly as
source files instead of as a downloaded ZIP.

These packs affect world generation. Update them only while the server is
stopped and back up the world first. Do not remove Terralith or Terratonic from
an existing generated world.
