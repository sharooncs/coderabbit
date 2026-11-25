#!/usr/bin/env python3
"""Prints every failed test, with its message and the start of its stack trace, so that a CI log says what failed
without the reports having to be downloaded.

  tools/failures.py DIR_OR_XML... [--xcresult PATH]

Each DIR_OR_XML is a JUnit XML file, or a folder of them (Gradle's build/test-results/<task>). --xcresult reads an
Xcode result bundle through `xcrun xcresulttool` (Xcode 16 and later). Prints nothing for what is not there, and
always exits 0: the steps that ran the tests say whether the job fails.
"""
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

STACK_LINES = 25


def junit(paths):
    files = []
    for p in paths:
        if os.path.isdir(p):
            for root, _, names in os.walk(p):
                files += [os.path.join(root, n) for n in sorted(names) if n.endswith(".xml")]
        elif os.path.isfile(p):
            files.append(p)
    failed = total = 0
    for f in files:
        try:
            tree = ET.parse(f)
        except ET.ParseError as e:
            print(f"(could not read {f}: {e})")
            continue
        for case in tree.iter("testcase"):
            total += 1
            for kind in ("failure", "error"):
                for bad in case.findall(kind):
                    failed += 1
                    print(f"FAILED {case.get('classname', '')}.{case.get('name', '')}")
                    message = (bad.get("message") or "").strip()
                    if message:
                        print("  " + message.replace("\n", "\n  "))
                    stack = (bad.text or "").strip().splitlines()
                    for line in stack[:STACK_LINES]:
                        print("    " + line)
                    if len(stack) > STACK_LINES:
                        print(f"    ... {len(stack) - STACK_LINES} more lines")
                    print()
    if files:
        print(f"JUnit results: {total} tests, {failed} failed ({len(files)} files)")


def xcresult(path):
    if not os.path.isdir(path):
        return
    try:
        out = subprocess.run(
            ["xcrun", "xcresulttool", "get", "test-results", "tests", "--path", path],
            capture_output=True, text=True, timeout=120,
        )
        data = json.loads(out.stdout)
    except Exception as e:  # noqa: BLE001 - a summary that cannot be read is said, not raised
        print(f"(could not read {path}: {e})")
        return
    failed = []

    def walk(node, trail):
        name = node.get("name", "")
        kind = node.get("nodeType", "")
        if kind == "Test Case" and node.get("result") == "Failed":
            messages = [c.get("name", "") for c in node.get("children", []) if c.get("nodeType") == "Failure Message"]
            failed.append(("/".join(trail + [name]), messages))
        for c in node.get("children", []) or []:
            walk(c, trail + ([name] if kind in ("Test Suite", "Unit test bundle", "UI test bundle") else []))

    for n in data.get("testNodes", []):
        walk(n, [])
    for name, messages in failed:
        print(f"FAILED {name}")
        for m in messages:
            print("  " + m.replace("\n", "\n  "))
        print()
    print(f"Xcode tests: {len(failed)} failed")


def main(argv):
    paths, bundle = [], None
    i = 0
    while i < len(argv):
        if argv[i] == "--xcresult" and i + 1 < len(argv):
            bundle = argv[i + 1]
            i += 2
            continue
        paths.append(argv[i])
        i += 1
    junit(paths)
    if bundle:
        xcresult(bundle)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
