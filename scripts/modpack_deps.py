"""Checks the required dependencies of the mods of the modpack.

The script reads each mod jar of the modpack: the jar of each metafile of modpack/mods (download URL and hash), and
the jars of the folders that the option --jars gives (the Folkhold mods). Each jar gives its mods and the mods of its
embedded jars (META-INF/jarjar/metadata.json), with their required dependencies (META-INF/neoforge.mods.toml).

The check fails when a required dependency is not a mod of the modpack, or when the version of this mod is not in
the version range of the dependency. The dependencies on minecraft, neoforge and java have no check: the launcher
gives them.

Usage: python3 scripts/modpack_deps.py [--pack modpack] [--jars <folder>]... [--cache <folder>]
The folder of --cache keeps the downloaded jars between the runs.
"""
import argparse
import glob
import hashlib
import io
import json
import os
import re
import sys
import tomllib
import urllib.request
import zipfile

# The launcher or the game gives these dependencies.
PLATFORM = frozenset({"minecraft", "neoforge", "java"})
MODS_TOML = ("META-INF/neoforge.mods.toml", "META-INF/mods.toml")
JARJAR = "META-INF/jarjar/metadata.json"
MANIFEST = "META-INF/MANIFEST.MF"
# The time limit of each download, in seconds.
TIMEOUT = 120
USER_AGENT = "Nicolas-s-minecraft-mods/Folkhold modpack check"
# The order of the known qualifiers of a Maven version. A release has the empty qualifier.
QUALIFIERS = ("alpha", "beta", "milestone", "rc", "snapshot", "", "sp")
ALIASES = {"ga": "", "final": "", "release": "", "cr": "rc"}
# A qualifier of one letter directly before a number: "1.0a1" is "1.0-alpha-1".
SHORT_ALIASES = {"a": "alpha", "b": "beta", "m": "milestone"}


class Mod:
    """A mod of a jar: its id, its version, the ids that it provides and its required dependencies."""

    def __init__(self, mod_id, version, source, provides=(), dependencies=()):
        self.mod_id = mod_id
        self.version = version
        self.source = source
        self.provides = tuple(provides)
        # Pairs (mod id, version range). The version range is "" when the dependency accepts each version.
        self.dependencies = tuple(dependencies)


def qualifier_rank(qualifier):
    """Returns the sort key of a qualifier: its position in QUALIFIERS, or a position after them with its text."""
    if qualifier in QUALIFIERS:
        return (QUALIFIERS.index(qualifier), "")
    return (len(QUALIFIERS), qualifier)


def parse_version(version):
    """Returns the items of a Maven version, as ComparableVersion of Maven: a list of numbers (int), qualifiers (str)
    and lists. A dot separates the items of a list. A hyphen, or a change between digits and other characters,
    starts a list in the current list. A list has no zero number, empty qualifier or empty list at its end, before
    its lists."""
    root = []
    stack = [root]
    current = root
    token = ""

    def flush(before_digit=False):
        nonlocal token
        if token.isdigit():
            current.append(int(token))
        else:
            text = token.lower()
            if before_digit and text in SHORT_ALIASES:
                text = SHORT_ALIASES[text]
            current.append(ALIASES.get(text, text))
        token = ""

    def sublist():
        nonlocal current
        current = []
        stack[-1].append(current)
        stack.append(current)

    for char in version:
        if char == ".":
            if token:
                flush()
            else:
                current.append(0)
        elif char == "-":
            if token:
                flush()
            else:
                current.append(0)
            sublist()
        elif token and char.isdigit() != token.isdigit():
            flush(before_digit=char.isdigit())
            sublist()
            token = char
        else:
            token += char
    if token:
        flush()
    for items in reversed(stack):
        index = len(items) - 1
        while index >= 0:
            if items[index] in (0, "", []):
                del items[index]
            elif not isinstance(items[index], list):
                break
            index -= 1
    return root


def compare_items(left, right):
    """Compares two items of a Maven version, or an item and None (no item at this position)."""
    if left is None:
        return 0 if right is None else -compare_items(right, None)
    if isinstance(left, int):
        if right is None or isinstance(right, int):
            return (left > (right or 0)) - (left < (right or 0))
        return 1
    if isinstance(left, str):
        if isinstance(right, int):
            return -1
        if isinstance(right, list):
            return -1
        a, b = qualifier_rank(left), qualifier_rank(right or "")
        return (a > b) - (a < b)
    if isinstance(right, int):
        return -1
    if isinstance(right, str):
        return 1
    other = right or []
    for index in range(max(len(left), len(other))):
        order = compare_items(left[index] if index < len(left) else None,
                              other[index] if index < len(other) else None)
        if order:
            return order
    return 0


def compare_versions(left, right):
    """Compares two Maven versions. Returns a negative number, 0 or a positive number. "1.0-beta" is lower than
    "1.0", "1.0.1" is higher than "1.0", and "1.21.1-3.7" is higher than "1.21-2.29"."""
    return compare_items(parse_version(left), parse_version(right))


def in_range(version, version_range):
    """True if `version` is in the Maven version range `version_range`. A range is a list of intervals with commas
    between them, for example "[1.0,2.0)" or "(,1.0],[1.2,)". "[1.0]" accepts only this version. A range with no
    bracket ("1.0", "*" or "") is a recommendation: it accepts each version."""
    text = (version_range or "").strip()
    if not text or text[0] not in "[(":
        return True
    intervals = re.findall(r"([\[(])([^\[\]()]*)([\])])", text)
    if not intervals:
        raise ValueError(f"The version range is not valid: {version_range}")
    for opening, body, closing in intervals:
        if "," not in body:
            if compare_versions(version, body.strip()) == 0:
                return True
            continue
        low, high = (part.strip() for part in body.split(",", 1))
        if low:
            order = compare_versions(version, low)
            if order < 0 or (order == 0 and opening == "("):
                continue
        if high:
            order = compare_versions(version, high)
            if order > 0 or (order == 0 and closing == ")"):
                continue
        return True
    return False


def manifest_version(jar):
    """Returns the Implementation-Version of the manifest of a jar, or "" when the manifest has no such line."""
    try:
        manifest = jar.read(MANIFEST).decode("utf-8", "replace")
    except KeyError:
        return ""
    # A manifest line of more than 72 bytes continues on the next line, after one space.
    match = re.search(r"^Implementation-Version: *(.*)$", manifest.replace("\r\n ", "").replace("\n ", ""),
                      re.MULTILINE)
    return match.group(1).strip() if match else ""


def is_required(dependency):
    """True if a dependency of a mods.toml file is required: type "required", or mandatory = true with no type."""
    if "type" in dependency:
        return str(dependency["type"]).lower() == "required"
    return bool(dependency.get("mandatory", False))


def read_jar(data, source):
    """Returns the mods of a jar (bytes) and of its embedded jars. `source` names the jar in the messages."""
    mods = []
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        names = set(jar.namelist())
        toml_name = next((name for name in MODS_TOML if name in names), None)
        if toml_name:
            toml = tomllib.loads(jar.read(toml_name).decode("utf-8-sig"))
            dependencies = toml.get("dependencies", {})
            for entry in toml.get("mods", []):
                mod_id = entry["modId"]
                version = str(entry.get("version", ""))
                if "${" in version:
                    version = manifest_version(jar)
                required = [(dependency["modId"], str(dependency.get("versionRange", "")))
                            for dependency in dependencies.get(mod_id, []) if is_required(dependency)]
                mods.append(Mod(mod_id, version, source, entry.get("provides", ()), required))
        if JARJAR in names:
            for embedded in json.loads(jar.read(JARJAR)).get("jars", []):
                path = embedded["path"]
                if path in names:
                    mods.extend(read_jar(jar.read(path), f"{source} ({os.path.basename(path)})"))
    return mods


def check(mods):
    """Returns the problems of a list of mods: one text for each required dependency that no mod provides, or whose
    version is not in the range. A mod id with more than one version passes if one of its versions is in the range,
    as the game selects the version of an embedded jar from the ranges."""
    provided = {}
    for mod in mods:
        for mod_id in (mod.mod_id, *mod.provides):
            provided.setdefault(mod_id, []).append(mod)
    problems = []
    for mod in mods:
        for mod_id, version_range in mod.dependencies:
            if mod_id in PLATFORM:
                continue
            candidates = provided.get(mod_id)
            if not candidates:
                problems.append(f"{mod.mod_id} ({mod.source}) requires {mod_id} {version_range or '(each version)'}."
                                f" The modpack does not have {mod_id}.")
            elif not any(in_range(candidate.version, version_range) for candidate in candidates):
                versions = ", ".join(sorted({candidate.version or "(no version)" for candidate in candidates}))
                problems.append(f"{mod.mod_id} ({mod.source}) requires {mod_id} {version_range}."
                                f" The modpack has {mod_id} {versions}.")
    return sorted(set(problems))


def download(url, hash_format, expected, cache):
    """Returns the bytes of the file of a metafile. Reads the folder `cache` first when it is not None. Fails when
    the hash of the file is not `expected`."""
    path = os.path.join(cache, f"{expected}.jar") if cache else None
    if path and os.path.exists(path):
        with open(path, "rb") as file:
            data = file.read()
    else:
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            data = response.read()
    actual = hashlib.new(hash_format, data).hexdigest()
    if actual != expected:
        raise SystemExit(f"The {hash_format} of {url} is {actual}. The metafile gives {expected}.")
    if path and not os.path.exists(path):
        os.makedirs(cache, exist_ok=True)
        with open(path, "wb") as file:
            file.write(data)
    return data


def pack_mods(pack, cache=None, fetch=download):
    """Returns the mods of the jars of the metafiles of the folder `pack`/mods. `fetch` downloads a jar."""
    mods = []
    for metafile in sorted(glob.glob(os.path.join(pack, "mods", "*.pw.toml"))):
        with open(metafile, "rb") as file:
            meta = tomllib.load(file)
        source = meta["download"]
        data = fetch(source["url"], source["hash-format"], source["hash"], cache)
        mods.extend(read_jar(data, meta["filename"]))
    return mods


def folder_mods(folder):
    """Returns the mods of the jars of a folder and of its subfolders. A sources jar has no mod."""
    mods = []
    for path in sorted(glob.glob(os.path.join(folder, "**", "*.jar"), recursive=True)):
        if path.endswith("-sources.jar"):
            continue
        with open(path, "rb") as file:
            mods.extend(read_jar(file.read(), os.path.basename(path)))
    return mods


def main(argv=None):
    parser = argparse.ArgumentParser(description="Checks the required dependencies of the mods of the modpack.")
    parser.add_argument("--pack", default="modpack", help="the folder of the packwiz pack")
    parser.add_argument("--jars", action="append", default=[], help="a folder with other mod jars of the modpack")
    parser.add_argument("--cache", help="a folder that keeps the downloaded jars")
    args = parser.parse_args(argv)
    mods = pack_mods(args.pack, args.cache)
    for folder in args.jars:
        mods.extend(folder_mods(folder))
    problems = check(mods)
    for problem in problems:
        print(f"ERROR: {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"OK: {len(mods)} mods, with their embedded mods. Each required dependency is in the modpack.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
