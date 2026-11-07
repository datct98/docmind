from pathlib import Path
import unittest
from unittest.mock import patch

from prepare_knowledge import CHUNK, parse_values, prepare


class KnowledgePreparationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.package = Path("artifacts/v2-contract")
        cls.bundle = prepare(cls.package)

    def test_every_record_has_traceable_source_and_no_semantic_readiness_claim(self):
        self.assertEqual(114, len(self.bundle["chunks"]))
        self.assertEqual("FTS_ONLY", self.bundle["readiness"])
        for chunk in self.bundle["chunks"]:
            self.assertTrue(chunk["source_cells"])
            self.assertEqual(64, len(chunk["metadata"]["source_sha256"]))
        self.assertEqual(43, sum(bool(c["structured"]) for c in self.bundle["chunks"]))

    def test_same_sku_does_not_merge_branch_inclusions_or_rooms(self):
        records = {c["metadata"]["seed_key"]: c for c in self.bundle["chunks"]}
        self.assertNotEqual(records["1:bich_cau:NS04"]["structured"]["inclusion"],
                            records["1:long_bien:NS04"]["structured"]["inclusion"])
        for branch in ("bich_cau", "long_bien", "my_dinh", "quan_1"):
            for code in ("NS01", "NS02"):
                self.assertEqual("Phòng chung", records[f"1:{branch}:{code}"]["structured"]["room"])

    def test_regional_prices_are_not_promoted_to_confirmed_branch_inventory(self):
        regional = [c for c in self.bundle["chunks"] if c["metadata"]["record_kind"] == "REGIONAL_PRICE"]
        self.assertEqual(12, len(regional))
        for chunk in regional:
            self.assertIn("chưa", chunk["text"].lower())

    def test_parser_rejects_sql_outside_the_literal_values_grammar(self):
        sql = (self.package / "assets/db/nang_sen_replace_knowledge.sql").read_text(encoding="utf-8")
        changed = sql.replace("::uuid,0,ARRAY", "::uuid,malicious(),ARRAY", 1)
        with self.assertRaises(ValueError):
            parse_values(changed, "_nangsen_seed_chunks", CHUNK)

    def test_hash_guard_rejects_drift_before_emitting_bundle(self):
        read = Path.read_bytes
        with patch.object(Path, "read_bytes", lambda path: read(path) + b"drift"):
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                prepare(self.package)


if __name__ == "__main__":
    unittest.main()
