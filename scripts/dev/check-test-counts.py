#!/usr/bin/env python3
"""Fail when a module ran fewer unit tests than its floor.

A suite whose tests silently stop running still passes: on 2026-10-05 every JUnit 4 and
Robolectric test in :manager was skipped for want of the vintage engine and the build was
green. Run after the unit-test tasks (CI does); raise a floor when tests are added, lower it
only on purpose when tests are removed.
"""

import glob
import sys
import xml.etree.ElementTree as ET

FLOORS = {"manager": 168, "server": 113, "database": 8}


def main() -> int:
    short = False
    for module, floor in FLOORS.items():
        ran = 0
        for path in glob.glob(f"{module}/build/test-results/*/TEST-*.xml"):
            suite = ET.parse(path).getroot()
            ran += int(suite.get("tests", 0)) - int(suite.get("skipped", 0))
        print(f"{module}: {ran} tests ran (floor {floor})")
        if ran < floor:
            print(
                f"::error::{module} ran {ran} unit tests, fewer than its floor of {floor}"
            )
            short = True
    return 1 if short else 0


if __name__ == "__main__":
    sys.exit(main())
