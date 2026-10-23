import importlib.util
import pathlib
import sys
import unittest


SCRIPT = pathlib.Path(__file__).with_name("catalog-transfer.py")
SPEC = importlib.util.spec_from_file_location("catalog_transfer", SCRIPT)
catalog = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = catalog
assert SPEC.loader is not None
SPEC.loader.exec_module(catalog)


class CatalogMappingTest(unittest.TestCase):
    def test_collision_preserves_production_identity_and_remaps_dependents(self):
        dev = {
            "partners": [[444, "KT Direct Shop"], [484, "도그마루"], [487, "New Partner"]],
            "benefits": [[914, "444:kt-direct", 444, "KT Benefit"], [969, "484:dog-lgu", 484, "Dog Benefit"]],
            "policies": [[1053, 914, "KT", "source-b", "url-b"], [1054, 969, "LGU", "source-a", "url-a"]],
        }
        prod = {
            "partners": [[444, "도그마루"]],
            "benefits": [[914, "444:dog-lgu", 444, "Dog Benefit"]],
            "policies": [[1053, 914, "LGU", "source-a", "url-a"]],
        }

        maps = catalog.build_catalog_maps(dev, prod)

        self.assertEqual(maps["partners"].source_to_target[484], 444)
        self.assertGreater(maps["partners"].source_to_target[444], 487)
        self.assertEqual(maps["partners"].source_to_target[487], 487)
        self.assertEqual(maps["benefits"].source_to_target[969], 914)
        self.assertGreater(maps["benefits"].source_to_target[914], 969)
        self.assertEqual(maps["policies"].source_to_target[1054], 1053)
        self.assertGreater(maps["policies"].source_to_target[1053], 1054)

    def test_duplicate_natural_identity_fails_closed(self):
        rows = [[1, "same"], [2, "same"]]
        with self.assertRaisesRegex(ValueError, "duplicate identity"):
            catalog.build_id_map(rows, [], lambda r: r[0], lambda r: r[0], lambda r: (r[1],), lambda r: (r[1],))

    def test_generated_and_identity_always_columns_are_not_exported_for_write(self):
        metadata = [
            ["partnerid", "NEVER", "YES", "BY DEFAULT"],
            ["partnername", "NEVER", "NO", ""],
            ["mapnormalizedname", "ALWAYS", "NO", ""],
            ["computed_token", "NEVER", "YES", "ALWAYS"],
        ]
        self.assertEqual(catalog._writable_columns(metadata), ["partnerid", "partnername"])


if __name__ == "__main__":
    unittest.main()
