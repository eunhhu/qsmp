# Managed Plugins

The plugin manager installs these server-side plugins from Modrinth:

- `Chunky`: pre-generates chunks to avoid exploration-time generation spikes.
- `AntiVillagerLag`: disables AI only for villagers explicitly named `Optimize`.
- `AttributeSwapFix`: restores vanilla attribute swapping behavior on Paper forks.
- `AxGraves`: creates owner-only 30-minute graves and preserves half of death XP.
- `AxInventoryRestore`: keeps bounded, operator-only inventory recovery snapshots.
- `TrialChamberPro`: discovers natural trial chambers and turns them into
  repeatable raids with snapshots, resets, wave boss bars, and per-player loot.
- `QSMPCompanions`: gives owned animals levels, three armor tiers, cryopods,
  owner-safe combat, and out-of-combat regeneration.

All downloads are pinned in `plugins.lock` and checked with the SHA-512 hash
published by Modrinth. Vanilla clients do not need to install anything.

Do not add ClearLag-style plugins without profiling first. Purpur already
contains Paper's performance patches and bundled `spark`; overlapping entity
cleaners and dynamic tick controls can change gameplay or make lag worse.

`QSMPCompanions.jar` is built from `custom-plugins/qsmp-companions` immediately
before every server start. It is server-side only, so vanilla clients can join.
