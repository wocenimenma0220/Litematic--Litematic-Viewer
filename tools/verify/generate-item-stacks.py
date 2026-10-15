"""Generate the shipped stack limits from the local 26.3 item registrations, including collections."""
from pathlib import Path
import hashlib, json, re

root = Path(__file__).resolve().parents[2]
source_root = root.parents[1] / "26.3-client-src/net/minecraft"
source = source_root / "world/item/Items.java"
text = source.read_text(encoding="utf-8")
colors = "white orange magenta light_blue yellow lime pink gray light_gray cyan purple blue brown green red black".split()
items = {"minecraft:" + p.stem: 64 for p in (root / "assets/vanilla/assets/minecraft/items").glob("*.json")}
references = {}
for kind in ("ItemIds", "BlockItemIds"):
    reference_text = (source_root / f"references/{kind}.java").read_text(encoding="utf-8")
    for field, value in re.findall(r'public static final [^;=]+? (\w+)\s*=\s*(?:BlockItemId\.)?create\("([^\"]+)"\)', reference_text):
        references[kind + "." + field] = value

def maximum(expression):
    values = re.findall(r"\.stacksTo\((\d+)\)", expression)
    if values:
        return int(values[-1])
    if re.search(r"\.(durability|humanoidArmor|wolfArmor|horseArmor|nautilusArmor|pickaxe|axe|hoe|shovel|sword|spear|tool)\(", expression):
        return 1
    return 64

for name, expression in re.findall(r"public static final Item (\w+)\s*=\s*(.*?);", text, re.S):
    ref = re.search(r"(?:ItemIds|BlockItemIds)\.\w+", expression)
    identifier = references.get(ref[0], name.lower()) if ref else name.lower()
    key = "minecraft:" + identifier
    assert key in items, f"Registration has no item definition: {name} -> {key}"
    items[key] = maximum(expression)
for name, expression in re.findall(r"public static final ColorCollection<Item> (\w+)\s*=\s*(.*?);", text, re.S):
    suffix = name.removeprefix("DYED_").lower()
    for color in colors:
        key = "minecraft:" + color + "_" + suffix
        assert key in items, f"Unknown colored registration: {key}"
        items[key] = maximum(expression)

checks = {"stone": 64, "snowball": 16, "bucket": 16, "white_banner": 16, "oak_sign": 16,
          "diamond_sword": 1, "white_bed": 1, "white_shulker_box": 1, "white_bundle": 1,
    "white_harness": 1, "white_cushion": 16, "straw_bed": 16,
    "cut_sandstone_slab": 64, "short_dry_grass": 64, "tall_dry_grass": 64}
for name, value in checks.items():
    assert items["minecraft:" + name] == value, name
(root / "assets/item-stacks.json").write_text(json.dumps({
    "source": "26.3-client-src Items.java, Item.Properties and reference IDs; includes ColorCollection registrations",
    "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "items": dict(sorted(items.items())),
    "blockEstimates": {"minecraft:frosted_ice": "minecraft:ice"}
}, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"PASS: {len(items)} known stack limits, literal properties, tools/armor, colored collections, and boundary checks")
