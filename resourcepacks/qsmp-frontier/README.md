# QSMP Frontier Resource Pack

Required server resource pack for QSMP Frontier.

Build:

```bash
python3 scripts/build_resource_pack.py --apply-server-properties
```

Output:

- `resourcepacks/qsmp-frontier/build/qsmp-frontier-pack.zip`
- `resourcepacks/qsmp-frontier/build/qsmp-frontier-pack.sha1`
- `resourcepacks/qsmp-frontier/build/qsmp-frontier-pack.json`

The pack uses generated textures and generated OGG layers. Item textures are
rendered as high-resolution sprite icons and downsampled to Minecraft-sized
PNGs so edges, glow, glass, and gem facets survive in game. No third-party
texture or sound assets are bundled.
