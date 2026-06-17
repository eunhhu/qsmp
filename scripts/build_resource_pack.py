#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import random
import shutil
import zipfile
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

from PIL import Image, ImageChops, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
PACK_ROOT = ROOT / "resourcepacks" / "qsmp-frontier"
BUILD_DIR = PACK_ROOT / "build"
STAGE_DIR = BUILD_DIR / "stage"
PACK_ZIP = BUILD_DIR / "qsmp-frontier-pack.zip"
PACK_SHA1 = BUILD_DIR / "qsmp-frontier-pack.sha1"
PACK_META = BUILD_DIR / "qsmp-frontier-pack.json"
PACK_ID = uuid5(NAMESPACE_URL, "qsmp-frontier-resource-pack")
PACK_URL = os.environ.get(
    "RESOURCE_PACK_PUBLIC_URL",
    os.environ.get(
        "QSMP_RESOURCE_PACK_URL",
        "http://127.0.0.1:25566/qsmp-frontier-pack.zip",
    ),
)
PROMPT = (
    "QSMP Frontier required pack: realistic frontier textures, cinematic UI, "
    "raid/dragon sound layers."
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--apply-server-properties",
        action="store_true",
        help="Update server.properties with the generated URL and SHA-1.",
    )
    args = parser.parse_args()

    rebuild_stage()
    write_pack_metadata()
    write_textures()
    write_sounds()
    build_zip()
    sha1 = digest(PACK_ZIP, "sha1")
    sha256 = digest(PACK_ZIP, "sha256")
    PACK_SHA1.write_text(sha1 + "\n", encoding="ascii")
    PACK_META.write_text(
        json.dumps(
            {
                "id": str(PACK_ID),
                "url": PACK_URL,
                "sha1": sha1,
                "sha256": sha256,
                "zip": str(PACK_ZIP.relative_to(ROOT)),
                "pack_format": 84,
            },
            indent=2,
        )
        + "\n",
        encoding="ascii",
    )
    if args.apply_server_properties:
        update_server_properties(sha1)
    print(f"Built {PACK_ZIP.relative_to(ROOT)}")
    print(f"SHA1 {sha1}")


def rebuild_stage() -> None:
    if STAGE_DIR.exists():
        shutil.rmtree(STAGE_DIR)
    STAGE_DIR.mkdir(parents=True)


def write_pack_metadata() -> None:
    write_json(
        STAGE_DIR / "pack.mcmeta",
        {
            "pack": {
                "pack_format": 84,
                "supported_formats": [84, 84],
                "description": "QSMP Frontier: realistic textures, cinematic UI, raid sounds",
            }
        },
    )
    icon = Image.new("RGBA", (128, 128), (8, 9, 13, 255))
    draw = ImageDraw.Draw(icon)
    for radius, color in [
        (58, (170, 124, 62, 255)),
        (48, (26, 31, 37, 255)),
        (34, (85, 43, 126, 255)),
        (20, (30, 140, 170, 255)),
    ]:
        draw.ellipse((64 - radius, 64 - radius, 64 + radius, 64 + radius), outline=color, width=4)
    draw.polygon([(64, 10), (75, 59), (118, 64), (75, 70), (64, 118), (53, 70), (10, 64), (53, 59)],
                 fill=(204, 160, 80, 230), outline=(245, 220, 140, 255))
    save_png(icon.filter(ImageFilter.UnsharpMask(radius=1, percent=135)), STAGE_DIR / "pack.png")


def write_textures() -> None:
    block_dir = STAGE_DIR / "assets" / "minecraft" / "textures" / "block"
    item_dir = STAGE_DIR / "assets" / "minecraft" / "textures" / "item"
    particle_dir = STAGE_DIR / "assets" / "minecraft" / "textures" / "particle"
    gui_dir = STAGE_DIR / "assets" / "minecraft" / "textures" / "gui" / "sprites"

    blocks = {
        "stone": ((94, 94, 88), (143, 142, 130), "stone"),
        "deepslate": ((38, 43, 49), (91, 98, 105), "slate"),
        "deepslate_bricks": ((35, 39, 45), (92, 98, 108), "bricks"),
        "deepslate_tiles": ((31, 35, 42), (87, 96, 105), "tiles"),
        "cracked_deepslate_bricks": ((28, 32, 39), (85, 91, 98), "cracked"),
        "chiseled_deepslate": ((34, 38, 45), (98, 105, 112), "chiseled"),
        "polished_blackstone": ((16, 16, 20), (62, 58, 70), "polished"),
        "polished_blackstone_bricks": ((18, 18, 22), (70, 66, 78), "bricks"),
        "obsidian": ((12, 9, 20), (44, 29, 63), "stone"),
        "crying_obsidian": ((13, 8, 22), (104, 42, 168), "crying"),
        "lodestone_side": ((55, 58, 61), (145, 128, 88), "metal"),
        "lodestone_top": ((53, 56, 60), (168, 142, 92), "metal"),
        "barrel_side": ((86, 58, 34), (151, 102, 58), "wood"),
        "barrel_top": ((92, 63, 36), (164, 115, 68), "wood"),
        "iron_bars": ((76, 78, 78), (179, 181, 173), "bars"),
    }
    for name, (base, accent, mode) in blocks.items():
        save_png(block_texture(name, base, accent, mode), block_dir / f"{name}.png")

    items = {
        "written_book": ((64, 33, 86), (215, 188, 104), "book"),
        "book": ((71, 46, 32), (198, 163, 100), "book"),
        "goat_horn": ((72, 61, 49), (220, 207, 174), "horn"),
        "netherite_scrap": ((38, 35, 42), (166, 100, 78), "shard"),
        "echo_shard": ((17, 31, 39), (72, 211, 220), "shard"),
        "amethyst_shard": ((76, 35, 112), (212, 147, 255), "shard"),
        "dragon_breath": ((70, 25, 104), (226, 106, 255), "bottle"),
        "experience_bottle": ((32, 82, 70), (206, 250, 126), "bottle"),
    }
    for name, (base, accent, mode) in items.items():
        save_png(item_texture(name, base, accent, mode), item_dir / f"{name}.png")
    for frame in range(32):
        save_png(compass_texture(frame), item_dir / f"compass_{frame:02d}.png")

    for name, color in {
        "critical_hit": (240, 224, 180),
        "enchanted_hit": (160, 96, 230),
        "end_rod": (180, 245, 255),
        "firework": (255, 180, 80),
        "smoke_0": (100, 100, 100),
        "sonic_boom": (95, 210, 255),
    }.items():
        save_png(particle_texture(color), particle_dir / f"{name}.png")

    save_png(button_texture((28, 31, 36), (176, 132, 68)), gui_dir / "widget" / "button.png")
    save_png(button_texture((58, 45, 72), (225, 173, 92)), gui_dir / "widget" / "button_highlighted.png")
    save_png(button_texture((23, 23, 25), (76, 76, 78)), gui_dir / "widget" / "button_disabled.png")
    save_png(slot_texture(), gui_dir / "container" / "slot.png")


def block_texture(name: str, base: tuple[int, int, int], accent: tuple[int, int, int], mode: str) -> Image.Image:
    rng = random.Random(hashlib.sha256(name.encode("utf-8")).digest())
    size = 64
    img = Image.new("RGBA", (size, size))
    pix = img.load()
    for y in range(size):
        for x in range(size):
            grain = (
                rng.random() * 0.18
                + math.sin((x * 1.7 + y * 0.4) / 7.0) * 0.08
                + math.sin((y * 2.1 - x * 0.3) / 11.0) * 0.07
            )
            shade = 0.72 + grain
            if mode in {"bricks", "cracked"}:
                mortar = x % 32 in (0, 1) or y % 16 in (0, 1) or ((y // 16) % 2 == 1 and x % 32 in (15, 16))
                if mortar:
                    shade *= 0.42
            if mode == "tiles":
                if x % 16 in (0, 1) or y % 16 in (0, 1):
                    shade *= 0.36
            if mode == "chiseled":
                if abs(x - 32) < 3 or abs(y - 32) < 3 or abs((x - 32) ** 2 + (y - 32) ** 2 - 310) < 70:
                    shade *= 1.28
            if mode == "metal":
                if x % 17 in (0, 1) or y % 17 in (0, 1):
                    shade *= 1.25
            if mode == "wood":
                shade += math.sin((x + y * 0.35) / 4.5) * 0.12
            color = mix(base, accent, max(0.0, min(1.0, shade - 0.45)))
            if mode == "crying" and (abs(x - 18 - math.sin(y / 5) * 7) < 2 or abs(x - 44 + math.cos(y / 6) * 5) < 2):
                color = mix(color, (188, 73, 255), 0.72)
            if mode == "bars":
                alpha = 255 if x in range(28, 36) or y in range(28, 36) or x in range(4, 8) or x in range(56, 60) else 0
                pix[x, y] = (*color, alpha)
                continue
            pix[x, y] = (*color, 255)
    return img.filter(ImageFilter.UnsharpMask(radius=1, percent=120))


def item_texture(name: str, base: tuple[int, int, int], accent: tuple[int, int, int], mode: str) -> Image.Image:
    size = 64
    scale = 4
    canvas = size * scale
    img = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img, "RGBA")

    def c(value: int | float) -> int:
        return int(round(value * scale))

    def box(values: tuple[int | float, int | float, int | float, int | float]) -> tuple[int, int, int, int]:
        return tuple(c(value) for value in values)

    def pts(values: list[tuple[int | float, int | float]]) -> list[tuple[int, int]]:
        return [(c(x), c(y)) for x, y in values]

    def line(values: tuple[int | float, int | float, int | float, int | float], fill: tuple[int, int, int, int], width: float) -> None:
        draw.line(box(values), fill=fill, width=max(1, c(width)))

    if mode == "book":
        cover = pts([(14, 11), (47, 7), (53, 52), (18, 58)])
        pages = pts([(18, 58), (53, 52), (49, 58), (16, 62)])
        draw.polygon(pages, fill=(224, 207, 168, 220), outline=(64, 45, 35, 210))
        draw.polygon(cover, fill=(*base, 255), outline=(22, 17, 21, 255))
        draw.polygon(pts([(18, 14), (25, 12), (29, 55), (21, 57)]), fill=darken(accent, 0.45) + (230,))
        for x, y in [(15, 12), (47, 7), (53, 52), (18, 58)]:
            draw.rounded_rectangle(box((x - 2, y - 2, x + 4, y + 4)), radius=c(1.2),
                                   fill=(*accent, 245), outline=(252, 226, 142, 220), width=c(0.8))
        draw.rounded_rectangle(box((29, 22, 46, 40)), radius=c(4),
                               fill=(*mix(base, accent, 0.22), 225),
                               outline=(*accent, 255), width=c(1.5))
        draw.ellipse(box((34, 26, 41, 33)), fill=(75, 225, 240, 235))
        line((37.5, 24, 37.5, 39), (190, 255, 255, 210), 1.2)
        line((31, 48, 48, 45), (255, 232, 158, 160), 1.0)
    elif mode == "horn":
        draw.arc(box((5, 10, 62, 58)), start=194, end=18, fill=(18, 14, 13, 230), width=c(15))
        draw.arc(box((6, 11, 61, 57)), start=194, end=18, fill=(*accent, 255), width=c(12))
        draw.arc(box((13, 20, 52, 53)), start=198, end=16, fill=(*base, 255), width=c(6))
        draw.ellipse(box((45, 22, 62, 38)), fill=(232, 218, 184, 245), outline=(81, 61, 43, 250), width=c(2))
        draw.polygon(pts([(8, 43), (18, 39), (21, 49), (11, 53)]), fill=(*darken(base, 0.75), 255),
                     outline=(23, 17, 13, 255))
        for offset in [22, 33, 43]:
            line((offset, 24, offset + 4, 37), (204, 150, 70, 240), 2.2)
            line((offset + 1, 25, offset + 5, 37), (255, 226, 142, 135), 0.8)
    elif mode == "shard":
        if name == "netherite_scrap":
            shard = pts([(34, 4), (54, 25), (42, 58), (15, 45), (23, 16)])
            draw.polygon(shard, fill=(36, 33, 39, 255), outline=(11, 9, 12, 255))
            draw.polygon(pts([(34, 4), (43, 25), (29, 29), (23, 16)]),
                         fill=(87, 74, 72, 240))
            draw.polygon(pts([(43, 25), (54, 25), (42, 58), (35, 38)]),
                         fill=(74, 45, 43, 245))
            for values in [(30, 12, 40, 28), (38, 30, 30, 44), (25, 24, 34, 34)]:
                line(values, (255, 112, 54, 230), 1.5)
                line(values, (255, 207, 96, 130), 0.55)
        else:
            shard = pts([(35, 3), (53, 28), (39, 61), (14, 42), (24, 14)])
            draw.polygon(shard, fill=(*accent, 245), outline=(*darken(base, 0.58), 255))
            draw.polygon(pts([(35, 3), (44, 29), (31, 33), (24, 14)]),
                         fill=(*lighten(accent, 0.18), 210))
            draw.polygon(pts([(44, 29), (53, 28), (39, 61), (33, 39)]),
                         fill=(*darken(accent, 0.68), 230))
            draw.polygon(pts([(14, 42), (31, 33), (33, 39), (39, 61)]),
                         fill=(*mix(base, accent, 0.45), 230))
            line((35, 4, 39, 60), (255, 255, 255, 120), 1.4)
            if name == "amethyst_shard":
                line((21, 45, 48, 25), (241, 204, 106, 180), 1.1)
    elif mode == "bottle":
        draw.rounded_rectangle(box((22, 19, 43, 57)), radius=c(7),
                               fill=(*base, 105), outline=(210, 240, 244, 210), width=c(2.2))
        draw.rectangle(box((28, 8, 37, 22)), fill=(*mix(base, accent, 0.35), 180),
                       outline=(190, 230, 232, 190), width=c(1.2))
        draw.rounded_rectangle(box((26, 5, 39, 11)), radius=c(2),
                               fill=(123, 84, 44, 255), outline=(55, 35, 23, 240), width=c(1))
        draw.ellipse(box((25, 33, 40, 50)), fill=(*accent, 165))
        draw.arc(box((26, 31, 40, 46)), 195, 18, fill=(255, 255, 255, 115), width=c(1.1))
        line((27, 24, 29, 52), (255, 255, 255, 95), 1.0)
        for px, py, radius in [(35, 38, 1.3), (31, 45, 0.9), (38, 45, 0.8)]:
            draw.ellipse(box((px - radius, py - radius, px + radius, py + radius)),
                         fill=(255, 255, 255, 120))
    img = halo(img, accent)
    img = outline_alpha(img, (9, 8, 12, 215), 2)
    img = img.filter(ImageFilter.UnsharpMask(radius=scale * 0.55, percent=135))
    return img.resize((size, size), Image.Resampling.LANCZOS)


def outline_alpha(image: Image.Image, color: tuple[int, int, int, int], radius: int) -> Image.Image:
    alpha = image.getchannel("A")
    expanded = alpha.filter(ImageFilter.MaxFilter(radius * 2 + 1))
    ring = ImageChops.subtract(expanded, alpha)
    outline = Image.new("RGBA", image.size, color)
    outline.putalpha(ring)
    return Image.alpha_composite(outline, image)


def halo(image: Image.Image, color: tuple[int, int, int], radius: int = 5) -> Image.Image:
    alpha = image.getchannel("A").filter(ImageFilter.GaussianBlur(radius))
    glow = Image.new("RGBA", image.size, (*color, 0))
    glow.putalpha(alpha.point(lambda value: int(value * 0.18)))
    return Image.alpha_composite(glow, image)


def darken(color: tuple[int, int, int], factor: float) -> tuple[int, int, int]:
    return tuple(max(0, min(255, int(channel * factor))) for channel in color)


def lighten(color: tuple[int, int, int], factor: float) -> tuple[int, int, int]:
    return tuple(max(0, min(255, int(channel + (255 - channel) * factor))) for channel in color)


def compass_texture(frame: int) -> Image.Image:
    size = 64
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.ellipse((9, 9, 55, 55), fill=(34, 32, 30, 255), outline=(210, 166, 82, 255), width=4)
    draw.ellipse((18, 18, 46, 46), outline=(78, 184, 200, 180), width=2)
    angle = (frame / 32.0) * math.tau - math.pi / 2
    tip = (32 + math.cos(angle) * 20, 32 + math.sin(angle) * 20)
    tail = (32 - math.cos(angle) * 14, 32 - math.sin(angle) * 14)
    draw.line((tail[0], tail[1], tip[0], tip[1]), fill=(234, 76, 72, 255), width=4)
    draw.ellipse((28, 28, 36, 36), fill=(230, 207, 126, 255))
    return img


def particle_texture(color: tuple[int, int, int]) -> Image.Image:
    size = 32
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    pix = img.load()
    for y in range(size):
        for x in range(size):
            distance = math.dist((x, y), (size / 2, size / 2)) / (size / 2)
            alpha = int(max(0.0, 1.0 - distance) ** 2 * 255)
            pix[x, y] = (*color, alpha)
    return img.filter(ImageFilter.GaussianBlur(0.8))


def button_texture(base: tuple[int, int, int], accent: tuple[int, int, int]) -> Image.Image:
    img = Image.new("RGBA", (200, 20), (*base, 255))
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((0, 0, 199, 19), radius=3, fill=(*base, 255), outline=(*accent, 255), width=2)
    draw.line((4, 3, 196, 3), fill=(255, 255, 255, 45), width=1)
    draw.line((4, 17, 196, 17), fill=(0, 0, 0, 90), width=1)
    return img


def slot_texture() -> Image.Image:
    img = Image.new("RGBA", (18, 18), (23, 24, 27, 230))
    draw = ImageDraw.Draw(img)
    draw.rectangle((0, 0, 17, 17), outline=(168, 124, 66, 170), width=1)
    draw.rectangle((2, 2, 15, 15), outline=(0, 0, 0, 90), width=1)
    return img


def write_sounds() -> None:
    write_json(
        STAGE_DIR / "assets" / "minecraft" / "sounds.json",
        {
            "event.raid.horn": layer([
                ("item/goat_horn/sound/0", 0.42, 0.72),
                ("entity/ravager/roar", 0.18, 0.55),
            ]),
            "entity.ender_dragon.growl": layer([
                ("entity/warden/sonic_charge", 0.36, 0.62),
                ("entity/ender_dragon/growl", 0.3, 0.8),
            ]),
            "block.beacon.deactivate": layer([
                ("block/amethyst_block/chime", 0.38, 0.55),
                ("entity/warden/sonic_boom", 0.18, 0.75),
            ]),
            "block.respawn_anchor.deplete": layer([
                ("block/deepslate/break1", 0.42, 0.72),
                ("block/respawn_anchor/deplete", 0.3, 0.7),
            ]),
            "entity.player.attack.sweep": layer([
                ("block/anvil/place", 0.16, 1.55),
                ("item/trident/hit", 0.22, 1.35),
            ]),
            "block.vault.open_shutter": layer([
                ("block/vault/open_shutter", 0.46, 0.78),
                ("block/heavy_core/place", 0.2, 0.58),
            ]),
            "ui.toast.challenge_complete": layer([
                ("block/amethyst_block/chime", 0.26, 1.1),
                ("ui/toast/challenge_complete", 0.32, 0.86),
            ]),
            "item.lodestone_compass.lock": layer([
                ("block/amethyst_block/chime", 0.2, 0.88),
                ("item/lodestone_compass/lock", 0.24, 0.8),
            ]),
            "entity.ravager.roar": layer([
                ("entity/ravager/roar", 0.35, 0.74),
                ("entity/warden/roar", 0.14, 0.55),
            ]),
        },
    )


def layer(sounds: list[tuple[str, float, float]]) -> dict:
    return {
        "replace": False,
        "sounds": [
            {"name": name, "volume": volume, "pitch": pitch}
            for name, volume, pitch in sounds
        ],
    }


def build_zip() -> None:
    PACK_ZIP.parent.mkdir(parents=True, exist_ok=True)
    if PACK_ZIP.exists():
        PACK_ZIP.unlink()
    with zipfile.ZipFile(PACK_ZIP, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for path in sorted(STAGE_DIR.rglob("*")):
            if not path.is_file():
                continue
            rel = path.relative_to(STAGE_DIR).as_posix()
            info = zipfile.ZipInfo(rel, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, path.read_bytes())


def update_server_properties(sha1: str) -> None:
    path = ROOT / "server.properties"
    lines = path.read_text(encoding="utf-8").splitlines()
    replacements = {
        "require-resource-pack": "true",
        "resource-pack": PACK_URL,
        "resource-pack-id": str(PACK_ID),
        "resource-pack-prompt": json.dumps({"text": PROMPT}, separators=(",", ":")),
        "resource-pack-sha1": sha1,
    }
    seen: set[str] = set()
    next_lines: list[str] = []
    for line in lines:
        key = line.split("=", 1)[0] if "=" in line and not line.startswith("#") else None
        if key in replacements:
            next_lines.append(f"{key}={replacements[key]}")
            seen.add(key)
        else:
            next_lines.append(line)
    for key, value in replacements.items():
        if key not in seen:
            next_lines.append(f"{key}={value}")
    path.write_text("\n".join(next_lines) + "\n", encoding="utf-8")


def write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)


def mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return tuple(int(a[i] * (1.0 - t) + b[i] * t) for i in range(3))


def digest(path: Path, algorithm: str) -> str:
    h = hashlib.new(algorithm)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


if __name__ == "__main__":
    main()
