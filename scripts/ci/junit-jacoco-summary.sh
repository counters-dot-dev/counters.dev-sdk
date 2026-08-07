#!/usr/bin/env bash
# Markdown bullets from JUnit XML results (+ optional JaCoCo XML): used by the Java SDK CI comment.
# Usage: junit-jacoco-summary.sh <test-results-dir> [jacoco-report.xml] [github-output]
set -euo pipefail

python3 - "$1" "${2:-}" "${3:-}" <<'EOF'
import glob, sys
import xml.etree.ElementTree as ET

res, jac, output = sys.argv[1], sys.argv[2], sys.argv[3]
t = f = s = 0
for p in glob.glob(f"{res}/*.xml"):
    r = ET.parse(p).getroot()
    t += int(r.get("tests", 0))
    f += int(r.get("failures", 0)) + int(r.get("errors", 0))
    s += int(r.get("skipped", 0))
passed = t - f - s
skipped = f" / {s} skipped" if s else ""
print(f"- unit: **{passed} passed / {f} failed{skipped}**")
if jac:
    try:
        root = ET.parse(jac).getroot()
        c = {x.get("type"): (int(x.get("missed")), int(x.get("covered"))) for x in root.findall("counter")}
        m, cov = c["LINE"]
        print(f"- coverage: line {100 * cov / (m + cov):.1f}%")
    except Exception:
        print("- coverage: _no JaCoCo report produced_")
if output:
    with open(output, "a", encoding="utf-8") as out:
        print(f"passed={passed}", file=out)
        print(f"failed={f}", file=out)
EOF
