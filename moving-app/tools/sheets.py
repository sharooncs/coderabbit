"""Contact sheets from the pictures AuditShotsTest writes. See tools/audit.sh."""
import pathlib
import sys

from PIL import Image, ImageChops, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent / "app" / "build" / "audit"
ORDER = [
    "home", "overview", "items", "item_wardrobe", "item_fridge", "item_sofa", "walk", "triage", "plan", "disposal", "electronics",
    "boxes", "box_box1", "box_box2", "box_box5", "pack", "labels", "lists", "packorder", "tasks",
    "moveday", "load", "unload", "reconcile", "sweep", "records_ORIGIN", "unpack", "budget", "reports",
    "rooms", "more", "sync", "settings", "search", "scan",
    "setup", "setup_2", "setup_3", "home_helper", "home_movers", "home_inventory",
]
wanted = [n for n in (sys.argv[1].split(",") if len(sys.argv) > 1 and sys.argv[1] else ORDER) if n]
out = ROOT / "sheets"
out.mkdir(parents=True, exist_ok=True)
for old in out.glob("*.png"):
    old.unlink()


def trim(im):
    """Cut the empty space below the content of a tall capture.

    The ground is a soft gradient with glows, not one flat colour, so "empty" means
    a row with no sharp step along it: content has edges, the ground has none.
    """
    grey = im.convert("L")
    steps = ImageChops.difference(grey, ImageChops.offset(grey, 1, 0)).point(lambda v: 255 if v > 10 else 0)
    box = steps.crop((2, 0, im.width, im.height)).getbbox()
    return im if not box else im.crop((0, 0, im.width, min(im.height, box[3] + 24)))


for kind, per, scale in (("tall", 5, 1.0), ("fold", 4, 0.5), ("empty", 5, 1.0)):
    names = [n for n in wanted if (ROOT / kind / f"{n}.png").exists()]
    for start in range(0, len(names), per):
        shots = []
        for n in names[start:start + per]:
            im = Image.open(ROOT / kind / f"{n}.png").convert("RGB")
            if kind == "tall":
                # The dock stays at the bottom of the tall page, far below the content: leave it out.
                im = trim(im.crop((0, 0, im.width, im.height - 290)))
            if scale != 1.0:
                im = im.resize((int(im.width * scale), int(im.height * scale)), Image.LANCZOS)
            shots.append((n, im))
        width = sum(i.width for _, i in shots) + 16 * (len(shots) + 1)
        height = max(i.height for _, i in shots) + 44
        sheet = Image.new("RGB", (width, height), (120, 120, 120))
        draw = ImageDraw.Draw(sheet)
        x = 16
        for n, im in shots:
            draw.text((x, 6), n, fill=(255, 255, 255))
            sheet.paste(im, (x, 28))
            x += im.width + 16
        path = out / f"{kind}-{start // per + 1}.png"
        sheet.save(path)
        print(path, sheet.size)
