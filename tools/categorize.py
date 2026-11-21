"""Unified icon categorisation, shared by import_icons.py and its unit tests.

Resolution order for one icon (first hit wins):
  1. overrides.json            explicit per-icon fix ("tabler/brand-x": "social")
  2. brand map                 Simple Icons slugs, and Tabler "brand-<slug>" icons
  3. source category map       Tabler single category / Lucide categories by priority
  4. keyword rules             only if step 3 gave nothing or a *weak* bucket
                               (system, ui, productivity, other)
  5. step-3 result, else "other"

Why the "weak" refinement: Tabler files "lock" under System and Lucide files
many things under "layout" or "tools"; a name keyword ("lock" -> security) is a
better signal than such catch-all categories, but must never override a
specific source category such as "weather".
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

MAPPINGS_DIR = Path(__file__).resolve().parent / "mappings"
_TOKEN_SPLIT = re.compile(r"[^a-z0-9]+")
SUBSTRING_MIN_LEN = 5


def tokens(*texts: str) -> list[str]:
    out: list[str] = []
    for t in texts:
        if t:
            out.extend(x for x in _TOKEN_SPLIT.split(t.lower()) if x)
    return out


@dataclass
class Rules:
    categories: list[str]
    weak: set[str]
    tabler: dict[str, str]
    lucide_map: dict[str, str]
    lucide_priority: list[str]
    brands: dict[str, str]            # slug -> category
    keyword_rules: list[tuple[str, list[str]]]
    overrides: dict[str, str] = field(default_factory=dict)

    @classmethod
    def load(cls, root: Path = MAPPINGS_DIR) -> "Rules":
        def read(name: str) -> dict:
            return json.loads((root / name).read_text(encoding="utf-8"))

        cats = read("categories.json")
        keys = [c["key"] for c in cats["categories"]]
        tabler = {k: v for k, v in read("tabler.json").items() if not k.startswith("_")}
        lucide = read("lucide.json")
        brands: dict[str, str] = {}
        for cat, slugs in read("simpleicons_brands.json").items():
            if cat.startswith("_"):
                continue
            for slug in slugs:
                brands.setdefault(slug, cat)  # first category in file order wins
        kw = [(r["category"], r["keywords"]) for r in read("keywords.json")["rules"]]
        overrides_path = root / "overrides.json"
        overrides = {}
        if overrides_path.exists():
            overrides = {k: v for k, v in read("overrides.json").items() if not k.startswith("_")}
        rules = cls(keys, set(cats["weak"]), tabler, lucide["map"], lucide["priority"], brands, kw, overrides)
        rules.validate()
        return rules

    def validate(self) -> None:
        """Fail fast on typos in the mapping files (a typo would silently become 'other')."""
        valid = set(self.categories)
        bad = []
        bad += [f"tabler:{k}->{v}" for k, v in self.tabler.items() if v not in valid and v != "@brand"]
        bad += [f"lucide:{k}->{v}" for k, v in self.lucide_map.items() if v not in valid]
        bad += [f"lucide.priority:{v}" for v in self.lucide_priority if v not in valid]
        bad += [f"brand:{k}->{v}" for k, v in self.brands.items() if v not in valid]
        bad += [f"keywords:{c}" for c, _ in self.keyword_rules if c not in valid]
        bad += [f"override:{k}->{v}" for k, v in self.overrides.items() if v not in valid]
        if bad:
            raise ValueError("Unknown categories in mapping files: " + ", ".join(bad))

    # ------------------------------------------------------------------ rules

    def match_keywords(self, words: list[str], slug: str | None = None) -> str | None:
        """First keyword rule matching a whole token, or (for long keywords) a substring of [slug]."""
        wordset = set(words)
        for category, keywords in self.keyword_rules:
            for kw in keywords:
                if kw in wordset:
                    return category
                if slug and len(kw) >= SUBSTRING_MIN_LEN and kw in slug:
                    return category
        return None

    def source_category(self, source: str, name: str, source_categories: list[str]) -> str | None:
        if source == "tabler":
            if not source_categories:
                return None
            mapped = self.tabler.get(source_categories[0])
            if mapped == "@brand":
                return self.brands.get(name.removeprefix("brand-").removesuffix("-filled"))
            return mapped
        if source == "lucide":
            mapped = {self.lucide_map[c] for c in source_categories if c in self.lucide_map}
            for cat in self.lucide_priority:
                if cat in mapped:
                    return cat
            return None
        if source == "simpleicons":
            return self.brands.get(name)
        return None

    def categorize(self, source: str, name: str, title: str = "", tags: list[str] | None = None,
                   source_categories: list[str] | None = None) -> str:
        tags = tags or []
        icon_id = f"{source}/{name}"
        if icon_id in self.overrides:
            return self.overrides[icon_id]

        base = self.source_category(source, name, source_categories or [])
        if base is not None and base not in self.weak:
            return base

        is_brand = source == "simpleicons" or (source_categories or [""])[0] == "Brand"
        slug = name.removeprefix("brand-") if is_brand else None
        # Name/title first; tags only when the source gave us nothing at all,
        # because tags are noisy ("heart" is tagged on many unrelated icons).
        kw = self.match_keywords(tokens(name, title), slug)
        if kw is None and base is None:
            kw = self.match_keywords(tokens(*tags))
        if kw is not None:
            return kw
        return base or "other"
