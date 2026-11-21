#!/usr/bin/env python3
"""Download the pinned icon packs, normalise their SVGs and build the offline index.

Usage (from the repository root, Python 3.10+, stdlib only):
    python tools/import_icons.py                 # build app/src/main/assets/icons
    python tools/import_icons.py --update-hashes # after bumping a version in icons.config.json
    python tools/import_icons.py --report        # also print per-category statistics

Outputs (all committed, so the Android build itself never touches the network):
    app/src/main/assets/icons/<source>/<name>.svg
    app/src/main/assets/icons/index.json      [{id, source, name, title, tags[], category, color?}]
    app/src/main/assets/icons/sources.json    pack metadata for the About screen
    app/src/main/assets/licenses/<source>.txt  upstream licence texts (attribution)

Security notes: archives are verified against the SHA-256 pinned in icons.config.json
and are never extracted with extractall(); members are read in memory and written
under a sanitised file name, so a crafted archive cannot escape the output folder.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import shutil
import sys
import tarfile
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from categorize import Rules  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "tools" / "icons.config.json"
CACHE = ROOT / "tools" / ".cache"
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUT_ICONS = ASSETS / "icons"
OUT_LICENSES = ASSETS / "licenses"
SAFE_NAME = re.compile(r"^[a-z0-9][a-z0-9-]*$")


# --------------------------------------------------------------------------- io

def fetch(src: dict, update_hashes: bool) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / f"{src['id']}-{src['version']}.tgz"
    if not path.exists():
        print(f"  downloading {src['url']}")
        req = urllib.request.Request(src["url"], headers={"User-Agent": "cutz-icon-import"})
        with urllib.request.urlopen(req, timeout=120) as r:
            path.write_bytes(r.read())
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if update_hashes:
        src["sha256"] = digest
    elif digest != src.get("sha256"):
        path.unlink()
        raise SystemExit(f"SHA-256 mismatch for {src['id']}: got {digest}. "
                         "If you bumped the version on purpose, run with --update-hashes.")
    return path


class Archive:
    """Read-only view over a .tgz, members keyed by path without the top-level folder."""

    def __init__(self, path: Path):
        self.files: dict[str, bytes] = {}
        with tarfile.open(path, "r:gz") as tf:
            for m in tf.getmembers():
                if not m.isfile():
                    continue
                rel = m.name.split("/", 1)[1] if "/" in m.name else m.name
                f = tf.extractfile(m)
                if f is not None:
                    self.files[rel] = f.read()

    def text(self, rel: str) -> str:
        return self.files[rel].decode("utf-8")

    def json(self, rel: str):
        return json.loads(self.text(rel))

    def glob(self, prefix: str, suffix: str) -> list[str]:
        return sorted(k for k in self.files if k.startswith(prefix) and k.endswith(suffix) and "/" not in k[len(prefix):])


# ------------------------------------------------------------------------- svg

_COMMENT = re.compile(r"<!--.*?-->", re.S)
_TITLE = re.compile(r"<title>.*?</title>", re.S)
_WS = re.compile(r">\s+<")
_SPACES = re.compile(r"\s+")
_CLASS = re.compile(r' class="[^"]*"')


def normalise_svg(svg: str, force_fill: bool) -> str:
    """Minify and make every glyph colourable through the literal `currentColor`.

    At runtime SvgRenderer replaces `currentColor` with the chosen glyph colour,
    which works for stroke-based (Tabler, Lucide) and fill-based (Simple Icons) packs.
    Simple Icons have no fill attribute (default black), so we add fill="currentColor"
    on the root element.
    """
    svg = _COMMENT.sub("", svg)
    svg = _TITLE.sub("", svg)
    svg = _WS.sub("><", svg.strip())
    svg = _SPACES.sub(" ", svg)
    svg = _CLASS.sub("", svg).replace(' role="img"', "").replace(" >", ">")
    if force_fill and "fill=" not in svg.split(">", 1)[0]:
        svg = svg.replace("<svg ", '<svg fill="currentColor" ', 1)
    return svg


def write_svg(source: str, name: str, svg: str) -> None:
    if not SAFE_NAME.match(name):
        raise ValueError(f"unsafe icon name {name!r}")
    d = OUT_ICONS / source
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{name}.svg").write_text(svg, encoding="utf-8", newline="\n")


def slug_name(raw: str) -> str:
    return re.sub(r"[^a-z0-9-]+", "-", raw.lower()).strip("-")


# --------------------------------------------------------------------- sources

def import_simpleicons(ar: Archive, rules: Rules, stats: dict) -> list[dict]:
    data = ar.json("data/simple-icons.json")
    data = data["icons"] if isinstance(data, dict) else data
    known = set()
    out = []
    for icon in data:
        slug = icon["slug"]
        rel = f"icons/{slug}.svg"
        if rel not in ar.files:
            continue
        name = slug_name(slug)
        known.add(slug)
        aliases = icon.get("aliases", {})
        tags = list(aliases.get("aka", []))
        tags += [d.get("title", "") for d in aliases.get("dup", [])]
        tags += list(aliases.get("loc", {}).values())
        tags = sorted({t for t in tags if t})
        cat = rules.categorize("simpleicons", name, icon["title"], tags)
        write_svg("simpleicons", name, normalise_svg(ar.text(rel), force_fill=True))
        out.append({"id": f"simpleicons/{name}", "source": "simpleicons", "name": name,
                    "title": icon["title"], "tags": tags, "category": cat, "color": "#" + icon["hex"].upper()})
    stats["unknown_brand_slugs"] = sorted(s for s in rules.brands if s not in known)
    return out


def import_tabler(ar: Archive, rules: Rules, stats: dict) -> list[dict]:
    meta = ar.json("icons.json")
    out = []
    for name, icon in sorted(meta.items()):
        cat_src = [icon.get("category") or ""]
        if cat_src[0] and cat_src[0] not in rules.tabler:
            stats.setdefault("unmapped_tabler", Counter())[cat_src[0]] += 1
        tags = [str(t) for t in icon.get("tags", [])]
        for style, suffix in (("outline", ""), ("filled", "-filled")):
            if style not in icon.get("styles", {}):
                continue
            rel = f"icons/{style}/{name}.svg"
            if rel not in ar.files:
                continue
            n = slug_name(name + suffix)
            cat = rules.categorize("tabler", n, name.replace("-", " "), tags, cat_src)
            write_svg("tabler", n, normalise_svg(ar.text(rel), force_fill=False))
            out.append({"id": f"tabler/{n}", "source": "tabler", "name": n,
                        "title": (name + suffix).replace("-", " "), "tags": tags, "category": cat})
    return out


def lucide_categories(ar: Archive, name: str, seen: frozenset = frozenset()) -> list[str]:
    """Lucide metadata may say "$extends:<other-icon>" to inherit that icon's categories."""
    rel = f"icons/{name}.json"
    if rel not in ar.files or name in seen:
        return []
    out: list[str] = []
    for c in ar.json(rel).get("categories", []):
        if c.startswith("$extends:"):
            out += lucide_categories(ar, c.split(":", 1)[1], seen | {name})
        else:
            out.append(c)
    return list(dict.fromkeys(out))


def import_lucide(ar: Archive, rules: Rules, stats: dict) -> list[dict]:
    out = []
    for rel in ar.glob("icons/", ".svg"):
        name = rel[len("icons/"):-4]
        meta_rel = f"icons/{name}.json"
        meta = ar.json(meta_rel) if meta_rel in ar.files else {}
        if meta.get("deprecated"):
            continue
        cats = lucide_categories(ar, name)
        for c in cats:
            if c not in rules.lucide_map:
                stats.setdefault("unmapped_lucide", Counter())[c] += 1
        tags = [str(t) for t in meta.get("tags", [])]
        n = slug_name(name)
        cat = rules.categorize("lucide", n, name.replace("-", " "), tags, cats)
        write_svg("lucide", n, normalise_svg(ar.text(rel), force_fill=False))
        out.append({"id": f"lucide/{n}", "source": "lucide", "name": n,
                    "title": name.replace("-", " "), "tags": tags, "category": cat})
    return out


IMPORTERS = {"simpleicons": import_simpleicons, "tabler": import_tabler, "lucide": import_lucide}
LICENSE_FILES = {"simpleicons": "LICENSE.md", "tabler": "LICENSE", "lucide": "LICENSE"}


# ------------------------------------------------------------------------ main

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--update-hashes", action="store_true", help="accept and store new archive hashes")
    ap.add_argument("--report", action="store_true", help="print per-source/category statistics")
    args = ap.parse_args()

    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    rules = Rules.load()

    if OUT_ICONS.exists():
        shutil.rmtree(OUT_ICONS)
    OUT_ICONS.mkdir(parents=True)
    OUT_LICENSES.mkdir(parents=True, exist_ok=True)

    index: list[dict] = []
    stats: dict = {}
    sources_meta = []
    for src in config["sources"]:
        print(f"[{src['id']}] {src['name']} {src['version']}")
        ar = Archive(fetch(src, args.update_hashes))
        entries = IMPORTERS[src["id"]](ar, rules, stats)
        print(f"  {len(entries)} icons")
        index.extend(entries)
        lic = LICENSE_FILES[src["id"]]
        (OUT_LICENSES / f"{src['id']}.txt").write_text(ar.text(lic), encoding="utf-8", newline="\n")
        sources_meta.append({k: src[k] for k in ("id", "name", "version", "license", "repository")} | {"count": len(entries)})

    # Same name in several packs is kept on purpose: ids are "<source>/<name>",
    # and the UI shows the source badge to tell them apart.
    ids = [e["id"] for e in index]
    dupes = [i for i, c in Counter(ids).items() if c > 1]
    if dupes:
        raise SystemExit(f"duplicate ids inside a source: {dupes[:10]}")

    (OUT_ICONS / "index.json").write_text(
        json.dumps({"version": 1, "categories": rules.categories, "icons": index},
                   ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8", newline="\n")
    (OUT_ICONS / "sources.json").write_text(json.dumps(sources_meta, indent=2), encoding="utf-8", newline="\n")

    if args.update_hashes:
        CONFIG.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")

    print(f"total: {len(index)} icons -> {OUT_ICONS.relative_to(ROOT)}")
    if args.report:
        per = defaultdict(Counter)
        for e in index:
            per[e["source"]][e["category"]] += 1
        for s, c in per.items():
            print(f"  {s}: " + ", ".join(f"{k}={v}" for k, v in sorted(c.items(), key=lambda x: -x[1])))
        for key in ("unmapped_tabler", "unmapped_lucide"):
            if stats.get(key):
                print(f"  {key}: {dict(stats[key])}")
        if stats.get("unknown_brand_slugs"):
            print(f"  brand slugs not in Simple Icons ({len(stats['unknown_brand_slugs'])}): "
                  + " ".join(stats["unknown_brand_slugs"]))


if __name__ == "__main__":
    main()
