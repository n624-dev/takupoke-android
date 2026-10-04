#!/usr/bin/env python3
"""Load pinned research prompts and validate shared ID-output boundaries, offline."""
import argparse
import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ROLES = ("subject", "teacher", "room")
# Intentional immutable allowlist: changing a manifest cannot approve new text
# or relabel a COPY task as a semantic HEAD decision.
PINS = {
    "single_header_role": ("prompts/single-header-role-ja-v1.txt", "4827fa46956a14d375792000e9bdba62a7c0bc153a19ceb4c6202063877debc7", 1422, "research_component_control"),
    "deterministic_body_id_copy_control": ("prompts/deterministic-body-id-copy-v1.txt", "c6d1410ebe5de98ad1934627b3f5115ae396087d758814dbc538daafc750998c", 449, "research_component_control"),
    "fieldExtraction_reference": ("prompts/field-extraction-user-reference-v1.txt", "23f711aa564233963fd1a259d0403b45e3d891d6903fc0009dd6853a32371d9c", 3927, "archived_evaluated_reference"),
}
CONTROL_PINS = {
    "manifest.json": "8078bf92dad7b36cc4501e4a0535d29acd62d91f2d6b6b3a37020326645c37a3",
    "protocol.json": "4b71a37451fbb8ca0f10cb0182c4018b50d87b0f6aeccc611da8e867027e199e",
    "validation-fixtures.json": "fd04b85bd8688959b50b9a21bebfe92e4b910e712ac899c6b038331272d4f783",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key")
        result[key] = value
    return result


def no_nonfinite(value):
    raise ValueError("Nonfinite JSON number: " + value)


def finite_float(raw):
    value = float(raw)
    require(math.isfinite(value), "Nonfinite JSON number")
    return value


def strict_json(raw):
    return json.loads(raw, object_pairs_hook=unique_object, parse_constant=no_nonfinite, parse_float=finite_float)


def inventory(root=ROOT):
    root = Path(root)
    for path, digest in CONTROL_PINS.items():
        require(hashlib.sha256((root / path).read_bytes()).hexdigest() == digest, "Unapproved contract or fixture bytes: " + path)
    manifest = strict_json((root / "manifest.json").read_text(encoding="utf-8"))
    require(manifest.get("contractVersion") == 1, "Unknown contract version")
    require(set(manifest.get("recipes", {})) == set(PINS), "Unknown or missing task")
    require(manifest.get("sharedAcross") == ["ios", "android", "windows"], "OS scope changed")
    for task, (path, digest, size, status) in PINS.items():
        entry = manifest["recipes"][task]
        require(entry.get("path") == path and entry.get("sha256") == digest, "Unapproved prompt pin")
        require(entry.get("promptVersion") == 1 and entry.get("utf8Bytes") == size, "Version or size changed")
        require(entry.get("status") == status and entry.get("productionEnabled") is False, "Task scope or activation changed")
        require(entry.get("inputSchema") == task + "_input", "Task input schema changed")
        expected_output = "field_extraction_output" if task == "fieldExtraction_reference" else "original_ids_output"
        require(entry.get("outputSchema") == expected_output, "Task output schema changed")
        raw = (root / path).read_bytes()
        require(not raw.startswith(b"\xef\xbb\xbf") and b"\r" not in raw, "BOM or newline normalization")
        require(len(raw) == size and hashlib.sha256(raw).hexdigest() == digest, "Prompt bytes changed")
        raw.decode("utf-8", errors="strict")
        require(entry.get("terminalNewline") == raw.endswith(b"\n"), "Terminal newline differs")
    protocol = strict_json((root / "protocol.json").read_text(encoding="utf-8"))
    require(protocol.get("contractVersion") == 1, "Unknown protocol version")
    ids = protocol["schemas"]["original_ids_output"]
    require(ids == {"type": "object", "properties": {"ids": {"type": "array", "items": {"type": "string"}, "maxItems": 48}}, "required": ["ids"], "additionalProperties": False}, "ID schema changed")
    body = protocol["dynamicConstraints"]["deterministic_body_id_copy_control_input"]
    require(body.get("usefulAIChoices") == 0, "Predetermined COPY cannot be AI recovery")
    limits = protocol["dynamicConstraints"]["original_ids_output"]
    require(limits.get("maxRawUTF8Bytes") == 16384 and limits.get("maxItems") == 48, "Output bounds changed")
    return manifest


def load_prompt(task, root=ROOT, *, allow_archived=False):
    manifest = inventory(root)
    require(task in PINS, "Unknown task")
    require(allow_archived or manifest["recipes"][task]["status"] != "archived_evaluated_reference", "Reference is archived; no new cohort authorized")
    return (Path(root) / PINS[task][0]).read_bytes().decode("utf-8")


def decode_ids(raw, all_source_ids):
    require(type(raw) is str and len(raw.encode("utf-8")) <= 16384, "Output exceeds UTF-8 cap")
    require(type(all_source_ids) is list and all(type(i) is str for i in all_source_ids), "Invalid original ID list")
    require(len(set(all_source_ids)) == len(all_source_ids), "Duplicate original source ID")
    value = strict_json(raw)
    require(type(value) is dict and list(value) == ["ids"], "One ids key required")
    ids = value["ids"]
    require(type(ids) is list and len(ids) <= 48 and all(type(i) is str for i in ids), "Invalid ID array")
    require(len(set(ids)) == len(ids) and all(i in all_source_ids for i in ids), "Duplicate or unknown ID")
    require(ids == [i for i in all_source_ids if i in ids], "Wrong original source-array order")
    return ids  # Validated as returned; never sorted, filtered or repaired.


def request(task, payload, all_source_ids=None, root=ROOT):
    instruction = load_prompt(task, root)
    require(type(payload) is dict, "Input object required")
    if task == "single_header_role":
        require(set(payload) == {"targetRole", "cellData"} and payload["targetRole"] in ROLES, "HEAD envelope changed")
        cell = payload["cellData"]
        require(type(cell) is dict and set(cell) == {"sources", "allowedRoleLabels"}, "Full HEAD cell payload required")
        sources = cell["sources"]
        labels = cell["allowedRoleLabels"]
        require(type(labels) is dict and set(labels) == set(ROLES), "Public role-label catalog required")
        require(all(type(labels[r]) is list and all(type(s) is str for s in labels[r]) for r in ROLES), "Invalid public labels")
        require(type(sources) is list and all(type(s) is dict and type(s.get("id")) is str and type(s.get("text")) is str and (s.get("box") is None or type(s["box"]) is dict) for s in sources), "Original sources required")
        derived = [s["id"] for s in sources]
        require(all_source_ids is None or all_source_ids == derived, "HEAD allowlist must be all sources in original order")
        all_source_ids = derived
    elif task == "deterministic_body_id_copy_control":
        require(set(payload) == {"mode", "lessonIndex", "role", "bodyCandidates"}, "COPY envelope changed")
        require(payload["mode"] == "deterministicBodyIdCopy" and payload["role"] in ROLES, "COPY task changed")
        require(type(payload["lessonIndex"]) is int and payload["lessonIndex"] >= 0, "Invalid lesson index")
        body = payload["bodyCandidates"]
        require(type(body) is list and all(type(s) is dict and set(s) == {"id", "text"} and type(s["id"]) is str and type(s["text"]) is str for s in body), "Known BODY candidates required")
        require(type(all_source_ids) is list, "COPY native schema must retain the full original cell ID enum")
        candidates = [s["id"] for s in body]
        require(candidates == decode_ids(json.dumps({"ids": candidates}), all_source_ids), "BODY candidates differ from original source order")
    else:
        raise ValueError("Archived reference cannot create a new request")
    # Validate the unfiltered enum, including the empty-list case.
    decode_ids('{"ids":[]}', all_source_ids)
    require(bool(all_source_ids), "No original IDs: do not create an invalid empty native enum or schedule a model")
    schema = {"type": "object", "properties": {"ids": {"type": "array", "items": {"type": "string", "enum": all_source_ids}, "maxItems": 48}}, "required": ["ids"], "additionalProperties": False}
    return {"task": task, "instruction": instruction, "user": payload, "outputSchema": schema,
            "scope": "Research component request only; existing per-OS certificate and Validator required"}


def check(root=ROOT):
    inventory(root)
    fixture_file = strict_json((Path(root) / "validation-fixtures.json").read_text(encoding="utf-8"))
    require(fixture_file.get("contractVersion") == 1, "Unknown fixture version")
    fixtures = fixture_file["fixtures"]
    require(len(fixtures) == 30 and len({f["name"] for f in fixtures}) == 30, "Fixture set changed")
    for fixture in fixtures:
        require(fixture["task"] in PINS and fixture["task"] != "fieldExtraction_reference", "Invalid fixture task")
        require(fixture.get("emptyProvesState") is False, "Empty array cannot prove EMPTY")
        try:
            decode_ids(fixture["raw"], fixture["allSourceIds"])
            accepted = True
        except (ValueError, TypeError, UnicodeError):
            accepted = False
        require(accepted == fixture["expectedStrict"], "Boundary mismatch: " + fixture["name"])
    return {"contractVersion": 1, "pinnedPrompts": len(PINS), "strictFixtures": len(fixtures),
            "nativeCalls": 0, "productionEnabled": False, "scope": "Offline byte/protocol checks only"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--request", choices=list(PINS))
    parser.add_argument("--input", type=Path)
    parser.add_argument("--all-source-ids", type=Path)
    args = parser.parse_args()
    if args.request:
        require(args.input is not None, "--input is required")
        payload = strict_json(args.input.read_text(encoding="utf-8"))
        ids = strict_json(args.all_source_ids.read_text(encoding="utf-8")) if args.all_source_ids else None
        print(json.dumps(request(args.request, payload, ids), ensure_ascii=False))
    else:
        require(args.input is None and args.all_source_ids is None, "Unexpected input without request")
        print(json.dumps(check(), ensure_ascii=False))
