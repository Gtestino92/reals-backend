#!/usr/bin/env python3
"""Reject tracked Bruno request-level literal values for sensitive local vars."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


SENSITIVE_NAMES = {
    "firebase_app_check_debug_token",
    "firebase_admin_password",
    "firebase_admin_id_token",
    "firebase_admin_refresh_token",
}

PLACEHOLDER = re.compile(r"^\{\{\s*[\w.-]+\s*\}\}$")
ASSIGNMENT = re.compile(r"^\s*([\w.-]+)\s*:\s*(.*?)\s*$")


def tracked_bruno_request_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "bruno"],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )
    return [
        Path(line)
        for line in result.stdout.splitlines()
        if line.endswith(".bru") and Path(line).is_file()
    ]


def strip_matching_quotes(value: str) -> str:
    stripped = value.strip()
    if len(stripped) >= 2 and stripped[0] == stripped[-1] and stripped[0] in {'"', "'", "`"}:
        return stripped[1:-1].strip()
    return stripped


def is_allowed_value(value: str) -> bool:
    normalized = strip_matching_quotes(value)
    return normalized == "" or PLACEHOLDER.fullmatch(normalized) is not None


def scan_file(path: Path) -> list[str]:
    errors: list[str] = []
    in_pre_request_vars = False

    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip()
        if not in_pre_request_vars:
            if stripped == "vars:pre-request {":
                in_pre_request_vars = True
            continue

        if stripped == "}":
            in_pre_request_vars = False
            continue
        if not stripped or stripped.startswith("#") or stripped.startswith("//"):
            continue

        match = ASSIGNMENT.match(line)
        if match is None:
            continue

        name, value = match.groups()
        if name in SENSITIVE_NAMES and not is_allowed_value(value):
            errors.append(
                f"{path}:{line_number}: {name} must reference an environment variable, not a request literal"
            )

    return errors


def main() -> int:
    errors = [
        error
        for path in tracked_bruno_request_files()
        for error in scan_file(path)
    ]

    if errors:
        print("Tracked Bruno request files contain request-scoped literal sensitive values:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        print("Use ignored local environment files for secret values and keep request files templated.", file=sys.stderr)
        return 1

    print("Bruno request variable guard passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
