"""Fail on bare Compose text buttons, including whitespace, qualified calls and import aliases."""
from pathlib import Path
import re
import sys

ROOT = Path("app/src/main/java")
APPROVED = ROOT / "app/tavernbridge/launcher/ui/components/V2ActionComponents.kt"
CALL = re.compile(r"\bTextButton\s*\(")
IMPORT = re.compile(r"(?m)^\s*import\s+androidx\.compose\.material(?:3)?\.TextButton\b")
def main():
    issues = []
    for path in sorted(ROOT.rglob("*.kt")):
        if path == APPROVED:
            continue
        source = path.read_text(encoding="utf-8")
        for pattern in (CALL, IMPORT):
            for match in pattern.finditer(source):
                line = source.count("\n", 0, match.start()) + 1
                issues.append(f"{path}:{line}: use a semantic action component")
    if not APPROVED.is_file():
        issues.append("action components not found; run from the repository root")
    if issues:
        print("\n".join(issues), file=sys.stderr)
        return 1
    print("0 direct Material text-button calls or imports outside the approved wrappers.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
