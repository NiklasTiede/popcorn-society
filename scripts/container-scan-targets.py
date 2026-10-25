#!/usr/bin/env python3
"""Select immutable GitOps app images without contacting or changing the cluster."""

import argparse
from datetime import date, datetime, timezone
import json
from pathlib import Path
import re

SERVICES = ("backend", "frontend", "agent")
APPS = Path(__file__).resolve().parents[1] / "infrastructure/clusters/home/apps"


def scan_due(today: date) -> bool:
    # Unlike cron's */3 day-of-month field, this preserves spacing at month end.
    return (today - date(1970, 1, 1)).days % 3 == 0


def production_images(apps: Path) -> list[dict[str, str]]:
    images = []
    for service in SERVICES:
        references = re.findall(r"^\s+image:\s+([^\s#]+)\s*$", (apps / f"{service}.yaml").read_text(), re.MULTILINE)
        expected = rf"niklastiede/popcorn-society-{service}:v[0-9]+\.[0-9]+\.[0-9]+@sha256:[a-f0-9]{{64}}"
        if len(references) != 1 or not re.fullmatch(expected, references[0]):
            raise ValueError(f"{service}.yaml must contain exactly one digest-pinned {service} release image")
        images.append({"service": service, "image": f"registry:{references[0]}"})
    return images


def scan_matrix(apps: Path, *, scheduled: bool, today: date) -> dict[str, list[dict[str, str]]]:
    return {"include": production_images(apps) if not scheduled or scan_due(today) else []}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scheduled", action="store_true", help="Only scan every third UTC day")
    args = parser.parse_args()
    matrix = scan_matrix(APPS, scheduled=args.scheduled, today=datetime.now(timezone.utc).date())
    print(f"scan={'true' if matrix['include'] else 'false'}")
    print("matrix=" + json.dumps(matrix, separators=(",", ":")))


if __name__ == "__main__":
    main()
