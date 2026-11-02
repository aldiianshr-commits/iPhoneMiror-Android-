#!/usr/bin/env python3
"""Prints a per-suite summary of JUnit XML results found under */build/test-results."""
import glob
import os
import sys
import xml.etree.ElementTree as ET

root = sys.argv[1] if len(sys.argv) > 1 else "."
total = fail = err = skip = 0
rows = []
for path in sorted(glob.glob(os.path.join(root, "**/build/test-results/**/*.xml"), recursive=True)):
    try:
        suite = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    t, f, e, s = (int(suite.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
    total += t; fail += f; err += e; skip += s
    module = path.split("/build/")[0].lstrip("./") or "."
    rows.append(f"{'FAIL' if f or e else 'ok  '}  {module:<20} {suite.get('name')}: {t} tests, {f} failures, {e} errors, {s} skipped")
    for case in suite.findall("testcase"):
        for bad in list(case.findall("failure")) + list(case.findall("error")):
            rows.append(f"      ✗ {case.get('name')}: {(bad.get('message') or '')[:300]}")
print("\n".join(rows))
print(f"\nTOTAL: {total} tests, {fail} failures, {err} errors, {skip} skipped")
