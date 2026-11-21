#!/usr/bin/env python3
"""Compare measured Linux x64 final output cache misses and hits using hosted package evidence."""

import runpy
from pathlib import Path


if __name__ == "__main__":
    comparator = Path(__file__).with_name("compare-package-cache-pilot.py")
    raise SystemExit(runpy.run_path(str(comparator))["main"](final_output=True))
