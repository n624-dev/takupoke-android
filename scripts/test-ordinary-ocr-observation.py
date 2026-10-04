import base64
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("collector", Path(__file__).with_name("collect-ordinary-ocr-observation.py"))
collector = importlib.util.module_from_spec(spec); spec.loader.exec_module(collector)

def transport(value, key="final"):
    data = json.dumps(value, ensure_ascii=False, allow_nan=False).encode()
    compressed = gzip.compress(data); encoded = base64.b64encode(compressed).decode()
    return "\n".join([f"TKPK_OCR_RECEIPT_BEGIN id={key} chunks=1 gzipBytes={len(compressed)} jsonBytes={len(data)} sha256={hashlib.sha256(data).hexdigest()}",
                       f"TKPK_OCR_RECEIPT_CHUNK id={key} index=0 data={encoded}", f"TKPK_OCR_RECEIPT_END id={key}"])

class IntegrityTests(unittest.TestCase):
    def test_nonfinite_and_original_ids_survive(self):
        original = {"sources": [{"id": "ocr-1-7", "text": "架空乙"}], "confidenceScores": [{"value": None, "nonFinite": "NaN"}, {"value": None, "nonFinite": "Infinity"}]}
        result = collector.decode_log(transport(original)); self.assertTrue(result["transportComplete"]); self.assertEqual(original, result["final"])
    def test_duplicate_chunk_is_unassessed(self):
        lines = transport({"x": 1}).splitlines(); lines.insert(2, lines[1]); result = collector.decode_log("\n".join(lines))
        self.assertFalse(result["transportComplete"]); self.assertIsNone(result["final"])
    def test_wrong_checksum_is_unassessed(self):
        log = transport({"x": 1}); log = log.replace("sha256=", "sha256=" + "0" * 64 + "bad")
        self.assertFalse(collector.decode_log(log)["transportComplete"])
    def test_whole_read_error_keeps_all_planned_pages(self):
        value = {"readReturned": False, "pages": [{"page": p, "executionAssessed": False} for p in range(1, 6)], "formalQuality": "unassessed"}
        self.assertEqual(value, collector.decode_log(transport(value))["final"])
    def test_interrupted_run_preserves_checkpoint_without_final_credit(self):
        result = collector.decode_log(transport({"remainingOriginalsUnassessed": 1}, "file-1"))
        self.assertFalse(result["transportComplete"]); self.assertEqual(1, result["lastCompleteSnapshot"]["remainingOriginalsUnassessed"])

if __name__ == "__main__":
    unittest.main()
