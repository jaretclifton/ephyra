#!/usr/bin/env python3
"""Checks that the docs agents and people start from still match the tree (#928).

For each file in CHECKED it reports:
  - a path (a Markdown link, or a path in backticks) that does not exist;
  - a Kotlin name in backticks (`DownloadQueue`, `PlayState.positionTicks`) that no source file has
    (each part is looked up as a word: it does not check that `positionTicks` is a member of
    `PlayState`);
  - a rule number (R18) that docs/architecture/rules.md does not list;
  - a Gradle task on a module that does not exist, or a `testDebugUnitTest`-style task on a module
    with flavours (it needs `testLibreDebugUnitTest`); other task names are not checked;
  - a test class pattern (`DownloadQueue*Test`) that matches no test file;
and for docs/areas.yaml: unknown or missing fields, 3 to 8 `paths` that exist and are not a whole
module (unless the module is the area, such as `downloads/`), `docs` that exist, `rules` that are
R-numbers rules.md lists, `tests` that are Gradle commands passing the checks above, and a guide in
docs/areas/ without an entry.

Run from anywhere: `python3 .claude/skills/start-work/scripts/check-docs.py`. It reads only files git tracks, needs no
packages, and takes about a second. CI runs it on every pull request (.github/workflows/docs.yml).

When it fails, fix the doc: the code is right. A path may use `...` or `…` for folders left out
(`downloads/.../DownloadQueue.kt`), `*` and `<lang>`-style placeholders. If a name is an example
or belongs to a library the code never names, put it in IGNORED_NAMES below, with the reason.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[4]

# The docs that are entry points: what CLAUDE.md, the start-work skill and the site's front pages
# send a reader to. Research and history (docs/*-research/, docs/architecture/[1-5]-*.md, the
# ADRs) describe the code as it was and are not checked.
CHECKED = [
    "CLAUDE.md",
    "docs/areas.yaml",
    "docs/areas/*.md",
    "docs/architecture/README.md",
    "docs/architecture/model/*.c4",
    "docs/dev-environment.md",
    "docs/mpv-checklist.md",
    "docs/track-test-series.md",
    "devsite/README.md",
    "devsite/src/content/docs/*.mdx",
    "devsite/src/content/docs/architecture/*.mdx",
    ".claude/skills/*/SKILL.md",
    "README.md",
    "docs/performance.md",
    "docs/upstream-sync.md",
    "docs/play-store.md",
    "docs/screenshots.md",
    "docs/open-decisions.md",
]

# Names in backticks that are not in the Kotlin sources, and why.
IGNORED_NAMES = {
    # The pattern names of CLAUDE.md's "Architecture" section.
    "FooState", "FooAction", "FooViewModel", "FooScreen", "FooScreenLayout", "FooEvent",
    # Upstream's names, quoted to say they are gone (R11).
    "JellyfinRepository", "FindroidTheme",
    # Keys of F-Droid's build recipe (fdroid-update skill).
    "CurrentVersion", "CurrentVersionCode", "AllowedAPKSigningKeys",
    # A Macrobenchmark metric and a Jellyfin user policy field, set outside the code.
    "TotalTime", "EnableContentDownloading",
}

# Checked by CHECKED's patterns, but left out: why.
NOT_CHECKED = {
    # A table of upstream's paths and names next to ours: most of it is not in this tree.
    ".claude/skills/upstream-sync/SKILL.md",
}

AREA_FIELDS = {"id", "title", "summary", "keywords", "paths", "docs", "rules", "tests"}
AREA_REQUIRED = ("title", "summary", "keywords", "paths")


def git_files() -> list[str]:
    """The files of the working tree: tracked or new, not ignored, and still on disk.

    So a file moved in an editor counts at its new place before it is staged, as in a clean
    checkout.
    """
    out = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=ROOT,
        capture_output=True,
        check=True,
    ).stdout.decode("utf-8")
    return sorted({f for f in out.split("\0") if f and (ROOT / f).is_file()})


FILES = git_files()
FILE_SET = set(FILES)
DIRS = {""}
for f in FILES:
    parts = f.split("/")
    for i in range(1, len(parts)):
        DIRS.add("/".join(parts[:i]))
TOP = {f.split("/")[0] for f in FILES}
ALL_PATHS = sorted(FILE_SET | DIRS)
PATHS_TEXT = "\n".join(ALL_PATHS)

SOURCES = [f for f in FILES if f.endswith((".kt", ".kts", ".java"))]
TEST_CLASSES: dict[str, list[str]] = {}
for f in SOURCES:
    if "/src/test" in f or "/src/androidTest" in f:
        TEST_CLASSES.setdefault(Path(f).stem, []).append(f)


def words_of_sources() -> set[str]:
    words: set[str] = set()
    for f in SOURCES + [f for f in FILES if f.endswith(".xml") and "/res/" not in f]:
        try:
            words.update(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", (ROOT / f).read_text("utf-8", "replace")))
        except OSError:
            pass
    return words


WORDS = words_of_sources() | {Path(f).stem for f in FILES}

MODULES = {
    str(Path(f).parent).replace("\\", "/")
    for f in FILES
    if f.endswith("build.gradle.kts") and "/" in f and not f.startswith("buildSrc/")
}
FLAVOURED = {
    m for m in MODULES if "productFlavors" in (ROOT / m / "build.gradle.kts").read_text("utf-8")
}


def rules() -> set[str]:
    text = (ROOT / "docs/architecture/rules.md").read_text("utf-8")
    return set(re.findall(r"^\| (R\d+) \|", text, re.M))


RULES = rules()


def path_exists(pattern: str, tail: bool = False) -> bool:
    """Whether a tracked file or folder matches [pattern] (`...`, `…`, `*`, `<name>` are wild).

    With [tail], the pattern may also be the end of a path (`domain/TrackPicks.kt`)."""
    p = pattern.strip().rstrip("/")
    if p in FILE_SET or p in DIRS:
        return True
    if tail and any(path.endswith("/" + p) for path in ALL_PATHS):
        return True
    if not re.search(r"\.\.\.|…|\*|<[^>/]+>", p):
        return False
    rx = ""
    for piece in re.split(r"(\.\.\.|…|\*\*|\*|<[^>/]+>)", p):
        if piece in ("...", "…", "**"):
            rx += ".*"
        elif piece == "*" or re.fullmatch(r"<[^>/]+>", piece or ""):
            rx += "[^/\n]*"
        else:
            rx += re.escape(piece)
    start = "^(?:.*/)?" if tail else "^"
    return re.search(rf"{start}{rx}$", PATHS_TEXT, re.M) is not None


SOURCE_ENDINGS = (".kt", ".kts")  # Kotlin paths are ours; other endings also name files elsewhere
PATH_LIKE = re.compile(r"^[\w.\-/…*<>]+$")
NAME = re.compile(r"^[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+$")
NAME_EXPR = re.compile(r"^([A-Za-z_]\w*)(?:\.([A-Za-z_]\w*))*(?:\(\))?$")


def check_span(span: str, problems: list[str]) -> None:
    """A piece of text in backticks: a path, a name, or neither."""
    s = span.strip()
    if "/" in s and PATH_LIKE.match(s) and not s.startswith(("/", "http", "./gradlew")):
        built = "/build/" in s + "/"  # a build output, not in the tree
        # A path of this repository starts with one of its top-level names, or, when that
        # folder is gone, still ends like a Kotlin file.
        ours = s.split("/")[0] in TOP or s.endswith(SOURCE_ENDINGS)
        if ours and not built and not path_exists(s, tail=True):
            problems.append(f"path `{s}` does not exist")
        return
    m = NAME_EXPR.match(s)
    if m:
        parts = re.sub(r"\.(kt|kts|md|py|sh|xml|yaml|yml|mjs|c4)$", "", s.removesuffix("()")).split(".")
        if NAME.match(parts[0]):
            for part in parts:
                if part not in WORDS and part not in IGNORED_NAMES:
                    problems.append(f"name `{part}` (in `{s}`) is in no source file")
                    return


def check_gradle(text: str, problems: list[str]) -> None:
    for module, task in re.findall(r"(?<![\w/])((?::[a-z][\w-]*)+):(\w+)(?![\w:])", text):
        path = module.strip(":").replace(":", "/")
        if path not in MODULES:
            if (path.split("/")[0]) in TOP or task.startswith(("test", "compile", "assemble")):
                problems.append(f"Gradle module `{module}` does not exist (`{module}:{task}`)")
            continue
        unflavoured = re.fullmatch(r"(test|compile|assemble)(Debug|Release)\w*", task)
        if path in FLAVOURED and unflavoured:
            problems.append(
                f"`{module}:{task}`: {path} has flavours, the task is "
                f"`{unflavoured.group(1)}Libre{task[len(unflavoured.group(1)):]}`"
            )
    for quoted1, quoted2, bare in re.findall(r"""--tests\s+(?:'([^']+)'|"([^"]+)"|([\w.*]+))""", text):
        pattern = quoted1 or quoted2 or bare
        cls = pattern.split(".")[-1]  # the class of `a.b.FooTest` and of `*.FooTest`
        rx = re.compile("^" + ".*".join(map(re.escape, cls.split("*"))) + "$")
        if not any(rx.match(name) for name in TEST_CLASSES):
            problems.append(f"--tests '{pattern}' matches no test class")


def check_test_names(text: str, problems: list[str]) -> None:
    """`FooTest` and `Foo*Test` anywhere in a text: each must match a test file."""
    for name in set(re.findall(r"(?<![\w*.])([A-Z][\w*]*Test)(?![\w*])", text)):
        if name in IGNORED_NAMES:
            continue
        rx = re.compile("^" + ".*".join(map(re.escape, name.split("*"))) + "$")
        if not any(rx.match(c) for c in TEST_CLASSES):
            problems.append(f"test `{name}` matches no test file")


def strip_code_blocks(text: str) -> tuple[str, str]:
    """The text outside ``` blocks, and the text inside them."""
    parts = re.split(r"^```.*?$\n(.*?)^```$", text, flags=re.M | re.S)
    return "\n".join(parts[0::2]), "\n".join(parts[1::2])


def check_markdown(rel: str, text: str) -> list[str]:
    problems: list[str] = []
    prose, code = strip_code_blocks(text)
    for target in re.findall(r"\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)", re.sub(r"`[^`\n]*`", "", prose)):
        if re.match(r"^([a-z]+:|#|//)", target, re.I) or target.startswith("/ephyra/"):
            continue
        file = unquote(target.split("#")[0])
        if not file:
            continue
        resolved = (Path(rel).parent / file).as_posix()
        norm = []
        for part in resolved.split("/"):
            if part == "..":
                if norm:
                    norm.pop()
            elif part not in (".", ""):
                norm.append(part)
        if "/".join(norm) not in FILE_SET and "/".join(norm) not in DIRS:
            problems.append(f"link `{target}` points at nothing")
    for span in re.findall(r"`([^`\n]+)`", prose):
        check_span(span, problems)
    check_gradle(text, problems)
    check_test_names(prose, problems)
    for rule in set(re.findall(r"\bR(\d+)\b", prose)):
        if f"R{rule}" not in RULES:
            problems.append(f"rule R{rule} is not in docs/architecture/rules.md")
    return problems


def check_repo_links(text: str) -> list[str]:
    """Links to this repository's files on GitHub (the model's `link` lines)."""
    problems = []
    for path in re.findall(r"github\.com/midasvo/ephyra/(?:tree|blob)/main/([^\s'\")#]+)", text):
        if not path_exists(path):
            problems.append(f"GitHub link to `{path}`, which does not exist")
    return problems


def area_list(entry: str, field: str) -> list[str]:
    """The items of a list field of an entry: `field: [a, b]` or `      - a` lines below it."""
    m = re.search(rf"^    {field}:(.*(?:\n      - .*)*)", entry, re.M)
    if not m:
        return []
    text = m.group(1).strip()
    if text.startswith("["):
        items = text.strip("[]").split(",")
    else:
        items = [line.strip().removeprefix("- ") for line in text.splitlines()]
    items = [item.strip() for item in items if item.strip()]
    return [i[1:-1] if len(i) > 1 and i[0] == i[-1] and i[0] in "'\"" else i for i in items]


def check_areas(rel: str, text: str) -> list[str]:
    """docs/areas.yaml, read without a YAML package: the fields, then each list."""
    problems: list[str] = []
    entries = re.split(r"^  - id: ", text, flags=re.M)[1:]
    ids = []
    for entry in entries:
        area = entry.split("\n", 1)[0].strip()
        ids.append(area)
        fields = set(re.findall(r"^    (\w+):", entry, re.M)) | {"id"}
        for unknown in sorted(fields - AREA_FIELDS):
            problems.append(f"area {area}: unknown field `{unknown}` (it belongs in the guide)")
        for needed in AREA_REQUIRED:
            if needed not in fields:
                problems.append(f"area {area}: no `{needed}`")
        paths = area_list(entry, "paths")
        if paths and not 3 <= len(paths) <= 8:
            problems.append(f"area {area}: {len(paths)} paths, keep 3 to 8")
        for value in paths:
            bare = value.rstrip("/")
            whole = bare in MODULES or ("/" not in bare and bare in DIRS)
            if whole and bare.replace("/", "-") != area:
                problems.append(f"area {area}: `{value}` is a whole module; name the files in it")
        for value in paths + area_list(entry, "docs"):
            if not path_exists(value):
                problems.append(f"area {area}: `{value}` in paths or docs does not exist")
        for rule in area_list(entry, "rules"):
            if not re.fullmatch(r"R\d+", rule):
                problems.append(f"area {area}: rule `{rule}` is not an R-number")
            elif rule not in RULES:
                problems.append(f"area {area}: rule {rule} is not in docs/architecture/rules.md")
        tests = area_list(entry, "tests")
        sub: list[str] = []
        for command in tests:
            if not command.startswith("./gradlew "):
                sub.append(f"test `{command}` is not a Gradle command")
        check_gradle("\n".join(tests), sub)
        problems += [f"area {area}: {p}" for p in sub]
    for dup in sorted({i for i in ids if ids.count(i) > 1}):
        problems.append(f"area id `{dup}` is used twice")
    for guide in sorted(f for f in FILE_SET if re.fullmatch(r"docs/areas/[^/]+\.md", f)):
        if Path(guide).stem not in ids:
            problems.append(f"{guide} has no entry in {rel}")
    return problems


def main() -> int:
    checked: list[str] = []
    for pattern in CHECKED:
        rx = re.compile("^" + "[^/]*".join(map(re.escape, pattern.split("*"))) + "$")
        found = sorted(f for f in FILES if rx.match(f) and f not in NOT_CHECKED)
        if not found:
            print(f"check-docs.py: CHECKED names {pattern}, which matches no file")
            return 1
        checked += found
    failed = 0
    for rel in checked:
        text = (ROOT / rel).read_text("utf-8").replace("\r\n", "\n")
        if rel.endswith(".yaml"):
            problems = check_areas(rel, text)
        elif rel.endswith(".c4"):
            problems = check_repo_links(text)
        else:
            problems = check_markdown(rel, text) + check_repo_links(text)
        for p in dict.fromkeys(problems):
            print(f"{rel}: {p}")
        failed += len(set(problems))
    if failed:
        print(
            f"\n{failed} problem(s). The code is right: fix the doc. "
            "See the top of .claude/skills/start-work/scripts/check-docs.py for what is checked and how to mark an exception."
        )
        return 1
    print(f"check-docs: {len(checked)} files, nothing stale")
    return 0


if __name__ == "__main__":
    sys.exit(main())
