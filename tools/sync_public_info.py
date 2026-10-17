#!/usr/bin/env python3
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STATUS_PATH = ROOT / "config" / "public-status.json"
README_PATH = ROOT / "README.md"
START = "<!-- OMNICORE_PUBLIC_STATUS_START -->"
END = "<!-- OMNICORE_PUBLIC_STATUS_END -->"

SYSTEMS = {
    "ps1": ("PS1 device-test", "PlayStation 1"),
    "n64": ("N64 device-test", "Nintendo 64"),
    "ps2": ("PS2 device-test", "PlayStation 2"),
    "psp": ("PSP device-test", "PSP"),
    "wii": ("Wii / GameCube device-test", "Wii / GameCube"),
    "switch": ("Nintendo Switch device-test", "Nintendo Switch"),
}


def parse_release(tag: str):
    stable = re.fullmatch(r"v(?P<version>\d+(?:\.\d+)+)-dev", tag)
    if stable:
        return "stable_dev", "Stable DEV", stable.group("version"), "Stable DEV"

    alpha = re.fullmatch(
        r"v(?P<version>\d+(?:\.\d+)+)-(?P<system>ps1|n64|ps2|psp|wii|switch)-alpha(?P<alpha>\d+)",
        tag,
    )
    if not alpha:
        return None

    system = alpha.group("system")
    number = alpha.group("alpha")
    version = alpha.group("version")
    label, pretty = SYSTEMS[system]
    return system, label, f"{version} Alpha {number}", f"{pretty} Alpha {number}"


def render(data: dict) -> str:
    channels = data["channels"]
    order = ["stable_dev", "n64", "ps2", "psp", "wii", "switch", "ps1"]
    rows = []
    for key in order:
        item = channels.get(key)
        if not item:
            continue
        rows.append(f"| {item['label']} | **{item['version']}** | {item['status']} |")

    latest = data["latest"]
    downloads = [
        f"**Latest release**  \n{latest['release_url']}"
    ]
    if latest.get("apk_url"):
        downloads.append(f"**Direct APK**  \n{latest['apk_url']}")

    return "\n".join([
        START,
        "## Current status",
        "",
        "| Channel | Version | Status |",
        "|---|---:|---|",
        *rows,
        "",
        f"### Latest milestone — {latest['title']}",
        "",
        f"**OmniCore {latest['version']}** — {latest['summary']}",
        "",
        *(
            [
                f"### Next PSP update — {data['next_update']['title']}",
                "",
                f"**Status:** {data['next_update']['status']}",
                "",
                data['next_update']['summary'],
                "",
            ]
            if data.get("next_update")
            else []
        ),
        "### Downloads",
        "",
        *downloads,
        "",
        "### Public information automation",
        "",
        "This section is maintained from `config/public-status.json` by the release automation. A successful GitHub Release updates the public status page automatically; `[skip ci]` prevents documentation-only update loops.",
        END,
    ])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--release-url", required=True)
    parser.add_argument("--apk-url", default="")
    parser.add_argument("--release-name", default="")
    parser.add_argument("--summary", default="")
    args = parser.parse_args()

    parsed = parse_release(args.tag)
    if parsed is None:
        print(f"Unsupported release tag for automatic public sync: {args.tag}")
        return 0

    key, label, version, default_title = parsed
    data = json.loads(STATUS_PATH.read_text(encoding="utf-8"))
    channels = data.setdefault("channels", {})

    previous = channels.get(key, {})
    if key == "stable_dev":
        status = previous.get("status", "Stable device-tested development channel")
    elif key == "n64" and "ps2" in channels:
        label = "N64 maintenance"
        status = "Protected gameplay baseline; maintenance updates only unless device testing requires a targeted correction"
    else:
        status = "Active device testing; latest release validated and published by GitHub Actions"

    channels[key] = {
        "label": label,
        "version": version,
        "status": status,
        "tag": args.tag,
    }

    pretty_title = args.release_name.strip() or default_title
    summary = args.summary.strip() or f"Latest {pretty_title} release validated and published by the OmniCore release pipeline."
    data["latest"] = {
        "system": key,
        "title": pretty_title,
        "version": version,
        "tag": args.tag,
        "release_url": args.release_url,
        "apk_url": args.apk_url,
        "summary": summary,
    }

    STATUS_PATH.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    readme = README_PATH.read_text(encoding="utf-8")
    pattern = re.compile(re.escape(START) + r".*?" + re.escape(END), re.S)
    if not pattern.search(readme):
        raise SystemExit("README public status markers were not found")
    README_PATH.write_text(pattern.sub(render(data), readme), encoding="utf-8")
    print(f"Public information synchronized for {args.tag}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
