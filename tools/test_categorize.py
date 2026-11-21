"""Unit tests for the unified categorisation (run: python -m unittest discover tools)."""
import json
import unittest
from pathlib import Path

from categorize import Rules, tokens

RULES = Rules.load()


class CategorizeTest(unittest.TestCase):
    def test_all_mapping_targets_are_valid_categories(self):
        RULES.validate()  # raises on typo
        self.assertEqual(18, len(RULES.categories))
        self.assertEqual("other", RULES.categories[-1])

    def test_tabler_category_map(self):
        self.assertEqual("nature", RULES.categorize("tabler", "sun", "sun", [], ["Weather"]))
        self.assertEqual("finance", RULES.categorize("tabler", "currency-euro", "", [], ["Currencies"]))

    def test_weak_source_category_refined_by_name_keyword(self):
        # Tabler files "lock" under System (weak) -> refined to security.
        self.assertEqual("security", RULES.categorize("tabler", "lock", "lock", [], ["System"]))

    def test_specific_source_category_not_overridden_by_keywords(self):
        # "cloud" keyword must not turn a Weather icon into storage.
        self.assertEqual("nature", RULES.categorize("tabler", "cloud-storm", "", [], ["Weather"]))

    def test_tabler_brand_icons_use_brand_map(self):
        self.assertEqual("development", RULES.categorize("tabler", "brand-github", "", [], ["Brand"]))
        self.assertEqual("media", RULES.categorize("tabler", "brand-spotify-filled", "", [], ["Brand"]))

    def test_lucide_priority_between_multiple_categories(self):
        self.assertEqual("home", RULES.categorize("lucide", "house", "", [], ["buildings", "home", "navigation"]))

    def test_simpleicons_brand_map_then_keywords_then_other(self):
        self.assertEqual("communication", RULES.categorize("simpleicons", "signal", "Signal"))
        # Not in the brand map, matched by a long keyword inside the concatenated slug.
        self.assertEqual("finance", RULES.categorize("simpleicons", "examplebanking", "Example Banking"))
        self.assertEqual("other", RULES.categorize("simpleicons", "zzqx", "Zzqx"))

    def test_short_keywords_only_match_whole_tokens(self):
        # "car" must not match inside "carrot"-like slugs.
        self.assertEqual("other", RULES.categorize("simpleicons", "carrotz", "Carrotz"))

    def test_override_wins(self):
        rules = Rules(RULES.categories, RULES.weak, RULES.tabler, RULES.lucide_map, RULES.lucide_priority,
                      RULES.brands, RULES.keyword_rules, {"tabler/lock": "other"})
        self.assertEqual("other", rules.categorize("tabler", "lock", "", [], ["System"]))

    def test_categories_match_kotlin_enum(self):
        kt = Path(__file__).resolve().parent.parent / "app/src/main/java/fr/cutz/data/icons/IconCategory.kt"
        text = kt.read_text(encoding="utf-8")
        for key in RULES.categories:
            self.assertIn(f'"{key}"', text, f"IconCategory.kt is missing {key}")

    def test_tokens(self):
        self.assertEqual(["brand", "github", "filled"], tokens("brand-github-filled"))


if __name__ == "__main__":
    unittest.main()
