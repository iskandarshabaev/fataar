#!/usr/bin/env python3
"""Compatibility entry point: the modular baseline has advanced to the merged SDK."""
import runpy
from pathlib import Path
print("The baseline now verifies the merged SDK; running verify-merged.py", flush=True)
runpy.run_path(str(Path(__file__).with_name("verify-merged.py")), run_name="__main__")
