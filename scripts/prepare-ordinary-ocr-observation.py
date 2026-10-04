"""Rebuild exactly two existing public fictional image-only PDFs; no OCR or oracle adjustment."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import urllib.parse
import urllib.request

COMMIT = "3aab761f4aedb8e78fedf3868e952faff190ae70"
BASE = "https://raw.githubusercontent.com/n624-dev/takupoke-win/" + COMMIT + "/"
MANIFEST_SHA = "050b7640b6de309db2be78a932cf558f4d36b3b89c29b95f7737228e025079b9"
ASSEMBLER_SHA = "825cbb7d226585fc8e194f30484812b98b4d4edf00881e15521e14f2a5dcd3e9"
SELECTED = {
    "independent-Timetable-literal-乙": ("ordinary-literal.pdf", "233dec852ec7e43c93c70c42d8f88238a0c2e8aef146445c4b94d674bb6cc187"),
    "independent-Timetable-verifiedblank-丙": ("ordinary-verifiedblank.pdf", "2625bf49044d59088b53109fc15ba7813073fdedac71bde5eb82925c297050f8"),
}

def sha(data):
    return hashlib.sha256(data).hexdigest()

def download(path, expected):
    with urllib.request.urlopen(BASE + urllib.parse.quote(path, safe="/"), timeout=30) as response:
        data = response.read(2_000_001)
    if len(data) > 2_000_000 or sha(data) != expected:
        raise ValueError("Pinned asset hash/size mismatch: " + path)
    return data

def prepare(output):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("Asset output must be a fresh owned directory")
    manifest = json.loads(download("tools/localized-raster-native-probe/holdout/manifest.json", MANIFEST_SHA))
    with tempfile.TemporaryDirectory(prefix="ordinary-ocr-source-", dir=output.parent) as temporary:
        temporary = Path(temporary)
        script = temporary / "assemble.py"
        script.write_bytes(download("tools/raster-acquisition-native-probe/fixtures/assemble.py", ASSEMBLER_SHA))
        spec = importlib.util.spec_from_file_location("pinned_pdf_assembler", script)
        assembler = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(assembler)
        inputs = []
        for fixture_id, (filename, pdf_sha) in SELECTED.items():
            entry = next(f for f in manifest["fixtures"] if f["id"] == fixture_id)
            if entry["pdfSha256"] != pdf_sha:
                raise ValueError("Original PDF identity changed")
            # Read page hashes only. Literal answers/drawing/oracles never enter the APK.
            metadata = json.loads(download("tools/localized-raster-native-probe/holdout/" + entry["oracleFile"], entry["oracleSha256"]))
            if [p["page"] for p in metadata["pages"]] != list(range(1, 6)):
                raise ValueError("Expected five original pages")
            images = []; image_pins = []
            for page in metadata["pages"]:
                if page["imageFile"] != "page-" + str(page["page"]) + ".png":
                    raise ValueError("Unexpected page filename")
                path = temporary / (filename + "-" + page["imageFile"])
                data = download("tools/localized-raster-native-probe/holdout/" + fixture_id + "/" + page["imageFile"], page["imageSha256"])
                path.write_bytes(data)
                width, height, _ = assembler.png_stream(path)
                image_pins.append({"page": page["page"], "sha256": page["imageSha256"], "bytes": len(data), "width": width, "height": height})
                images.append(path)
            target = output / filename
            assembler.assemble(target, images)
            if sha(target.read_bytes()) != pdf_sha:
                raise ValueError("Rebuilt bytes differ from immutable original")
            inputs.append({"id": fixture_id, "file": filename, "sha256": pdf_sha, "bytes": target.stat().st_size, "plannedPages": 5, "originalImages": image_pins})
        receipt = {"version": 1, "sourceRepository": "n624-dev/takupoke-win", "sourceCommit": COMMIT,
                   "sourceManifestSha256": MANIFEST_SHA, "assemblerSha256": ASSEMBLER_SHA, "inputs": inputs}
        (output / "inputs.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if set(p.name for p in output.iterdir()) != {"inputs.json", *[v[0] for v in SELECTED.values()]}:
        raise ValueError("Unexpected packaged input; no gold/drawing asset permitted")
    return receipt

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.output), ensure_ascii=False))
