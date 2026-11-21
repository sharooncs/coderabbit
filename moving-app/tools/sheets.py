"""Contact sheets from the pictures the audit tests write. See tools/audit.sh.

    python3 tools/sheets.py                 every sheet
    python3 tools/sheets.py people,sync     only these screens (names are routes with / replaced by _)

Reads app/build/audit/ and writes app/build/audit/sheets/:
    tall-N.png            whole screens, top to bottom       (AuditShotsTest)
    fold-N.png            what a phone shows on opening      (AuditShotsTest)
    empty-N.png           the same screens with no data yet  (AuditShotsTest)
    dialogs-N.png         dialogs, menus, the camera views   (AuditShotsTest)
    roles-<who>-N.png     every screen as one kind of person sees it (RoleWalkTest),
                          when app/build/audit/roles/ is there
Only the sheets folder is rewritten; the pictures themselves, the roles folder
included, are left alone.
"""
import pathlib
import sys

from PIL import Image, ImageChops, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent / "app" / "build" / "audit"

# The order screens are laid out in: the journey of a move, then the people screens, then the variants.
# A picture whose name is not listed here is still put on a sheet, after these.
ORDER = [
    "home", "overview", "items", "item_wardrobe", "item_fridge", "item_sofa", "item_table", "item_mirror", "item_bike",
    "walk", "triage", "plan", "disposal", "electronics",
    "boxes", "box_box1", "box_box2", "box_box5", "pack", "labels", "lists", "packorder", "tasks",
    "moveday", "load", "unload", "reconcile", "sweep", "records_ORIGIN", "records_DEST", "unpack", "budget", "reports",
    "rooms", "more", "sync", "settings", "search", "search_results", "scan", "scan_typing",
    "people", "people_add", "person", "person_priya", "person_alex", "approvals", "approvals_selected", "approvals_decline", "activity",
    "home_decider", "approvals_decider", "people_decider", "settings_decider", "sync_decider", "more_decider",
    "home_helper", "approvals_helper", "people_helper", "settings_helper", "sync_helper", "more_helper",
    "home_alone", "people_alone", "settings_alone",
    "setup", "setup_2", "setup_3", "setup_joined", "home_movers", "home_inventory",
    "home_nodate", "more_nodate", "settings_nodate",
]
only = [n for n in (sys.argv[1].split(",") if len(sys.argv) > 1 and sys.argv[1] else []) if n]
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


def names_in(folder):
    """The pictures of a folder, in ORDER, then any others by name; narrowed to the ones asked for."""
    found = {p.stem for p in folder.glob("*.png")}
    names = [n for n in ORDER if n in found] + sorted(found - set(ORDER))
    return [n for n in names if not only or n in only]


def sheets(folder, prefix, per, scale=1.0, tall=False):
    names = names_in(folder)
    for start in range(0, len(names), per):
        shots = []
        for n in names[start:start + per]:
            im = Image.open(folder / f"{n}.png").convert("RGB")
            if tall:
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
        path = out / f"{prefix}-{start // per + 1}.png"
        sheet.save(path)
        print(path, sheet.size)


if (ROOT / "tall").is_dir():
    sheets(ROOT / "tall", "tall", per=5, tall=True)
if (ROOT / "fold").is_dir():
    sheets(ROOT / "fold", "fold", per=4, scale=0.5)
if (ROOT / "empty").is_dir():
    sheets(ROOT / "empty", "empty", per=5)
if (ROOT / "dialogs").is_dir():
    sheets(ROOT / "dialogs", "dialogs", per=5, scale=0.6)
if (ROOT / "roles").is_dir():
    for who in sorted(p for p in (ROOT / "roles").iterdir() if p.is_dir()):
        sheets(who, f"roles-{who.name}", per=5, tall=True)
