#!/usr/bin/env bash
# Markdown bullets from JUnit XML results (+ optional JaCoCo XML): used by the Java SDK CI comment.
# Usage: junit-jacoco-summary.sh <test-results-dir> [jacoco-report.xml]
set -euo pipefail

python3 - "$1" "${2:-}" <<'EOF'
import glob, sys
import xml.etree.ElementTree as ET

res, jac = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "")
t = f = 0
for p in glob.glob(f"{res}/*.xml"):
    r = ET.parse(p).getroot()
    t += int(r.get("tests", 0))
    f += int(r.get("failures", 0)) + int(r.get("errors", 0))
print(f"- unit: **{t - f} passed / {f} failed**")
if jac:
    try:
        root = ET.parse(jac).getroot()
        c = {x.get("type"): (int(x.get("missed")), int(x.get("covered"))) for x in root.findall("counter")}
        m, cov = c["LINE"]
        print(f"- coverage: line {100 * cov / (m + cov):.1f}%")
    except Exception:
        print("- coverage: _no JaCoCo report produced_")
EOF
