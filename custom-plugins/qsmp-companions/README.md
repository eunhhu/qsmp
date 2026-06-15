# QSMPCompanions

Server-side companion progression for Purpur 26.1. Vanilla clients do not need
mods or a resource pack.

## Features

- Every vanilla tamed animal can reach level 30.
- When an owner attacks a hostile mob or is attacked, nearby companions pursue
  and fight that enemy. Sitting and mounted companions stay put.
- The nearest owned companion within 24 blocks gains XP when its owner kills a
  hostile mob. A companion also gains XP for its own kills.
- Each level adds 4% maximum health and 3% attack damage where the entity has an
  attack attribute.
- Iron, diamond, and netherite companion armor works on every supported animal.
- Companions regenerate 4% maximum health every five seconds after ten seconds
  out of combat.
- Owners and their companions cannot damage each other.
- Cryopods preserve the complete entity, including inventory, variant, owner,
  level, armor tier, and Happy Ghast equipment.
- Happy Ghasts can be bonded by right-clicking one with a crafted Ghast Bonding
  Charm. `/companion claim` is retained only as an operator repair tool.
- Sneak-right-click an owned companion with an empty hand to inspect its level,
  XP, health, and armor without a command.

Armor tiers change real server-side stats. Vanilla clients do not have custom
models for every animal, so non-horse companions do not display a new armor
model.

## Commands

- `/companion info`: inspect the companion under the crosshair.
- `/companion claim`: operator repair fallback for an unowned Happy Ghast.
- `/companion give <player> <cryopod|iron|diamond|netherite|charm>`: operator tool.
- `/companion reload`: reload `plugins/QSMPCompanions/config.yml`.

## Recipes

- Cryopod: copper ingots in the corners, amethyst shards on the sides, an echo
  shard in the center, and an ender pearl at the bottom center.
- Iron armor: eight iron ingots around leather.
- Diamond armor: eight diamonds around exact iron companion armor.
- Netherite armor: four netherite scraps and four gold ingots around exact
  diamond companion armor.
- Ghast Bonding Charm: a Heart of the Sea surrounded by amethyst, a ghast tear,
  leads, and an ender eye.

Sneak-right-click an owned companion with an empty cryopod to store it.
Right-click a block with the filled cryopod to release it.
