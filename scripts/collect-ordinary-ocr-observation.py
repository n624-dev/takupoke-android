"""Decode only checksummed acquisition snapshots; incomplete transport is unassessed."""
import argparse
import base64
import gzip
import hashlib
import json
import io
from pathlib import Path
import re

def decode_log(log):
    records = {}; complete = {}; errors = []
    for raw in log.splitlines():
        line = raw.strip().removeprefix("INSTRUMENTATION_STATUS: stream=").strip()
        if line.startswith("TKPK_OCR_RECEIPT_BEGIN "):
            match = re.fullmatch(r"TKPK_OCR_RECEIPT_BEGIN id=([a-z0-9-]{1,32}) chunks=(\d+) gzipBytes=(\d+) jsonBytes=(\d+) sha256=([0-9a-f]{64})", line)
            if not match:
                errors.append("Invalid receipt header"); continue
            key, chunks, compressed, plain, digest = match.groups()
            if key in records or not 1 <= int(chunks) <= 10000 or not 1 <= int(compressed) <= 16_000_000 or not 1 <= int(plain) <= 64_000_000:
                errors.append("Duplicate or oversized receipt: " + key); continue
            records[key] = {"chunks": int(chunks), "compressed": int(compressed), "plain": int(plain), "sha": digest, "data": {}, "invalid": False}
        elif line.startswith("TKPK_OCR_RECEIPT_CHUNK "):
            match = re.fullmatch(r"TKPK_OCR_RECEIPT_CHUNK id=([a-z0-9-]{1,32}) index=(\d+) data=([A-Za-z0-9+/=]+)", line)
            if not match:
                errors.append("Invalid chunk"); continue
            key, index, data = match.groups(); index = int(index); record = records.get(key)
            if record is None:
                errors.append("Chunk without header"); continue
            if index >= record["chunks"] or index in record["data"] or len(data) > 2800:
                record["invalid"] = True; errors.append("Duplicate/out-of-range chunk: " + key); continue
            record["data"][index] = data
        elif line.startswith("TKPK_OCR_RECEIPT_END "):
            key = line.removeprefix("TKPK_OCR_RECEIPT_END id="); record = records.get(key)
            try:
                if record is None or record["invalid"] or key in complete or set(record["data"]) != set(range(record["chunks"])):
                    raise ValueError("Missing/duplicate chunks or receipt")
                compressed = base64.b64decode("".join(record["data"][i] for i in range(record["chunks"])), validate=True)
                if len(compressed) != record["compressed"]:
                    raise ValueError("Compressed length mismatch")
                with gzip.GzipFile(fileobj=io.BytesIO(compressed)) as stream:
                    plain = stream.read(record["plain"] + 1)
                if len(plain) != record["plain"] or hashlib.sha256(plain).hexdigest() != record["sha"]:
                    raise ValueError("Receipt length/hash mismatch")
                complete[key] = json.loads(plain)
            except Exception as failure:
                errors.append(key + ": " + str(failure))
    missing = [key for key in records if key not in complete]
    final = complete.get("final")
    return {"transportComplete": final is not None and not errors and not missing,
            "counterScope": "Final complete receipt has exact caller counts; without it later attempted/completed calls are unknown and checkpoint counts are lower bounds",
            "errors": errors, "incompleteSnapshots": missing, "final": final,
            "lastCompleteSnapshot": complete[next(reversed(complete))] if complete else None,
            "nativeQuality": "unassessed by transport; original literal scorer must assess raw observations separately"}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("log"); parser.add_argument("--output", required=True)
    args = parser.parse_args(); result = decode_log(Path(args.log).read_text(encoding="utf-8", errors="replace"))
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(0 if result["transportComplete"] else 1)
