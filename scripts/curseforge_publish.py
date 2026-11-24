"""Uploads the jar of each Folkhold mod to its CurseForge project.

The file docs/curseforge/projects.json gives, for each module, the id and the slug of its CurseForge project and the
slugs of the projects that it requires. The workflow Release calls this script after the GitHub Release, so the
CurseForge jars are the jars of the GitHub Release. The workflow CurseForge calls this script with the jars of an
existing GitHub Release.

For each module, the script sends <module>/build/libs/folkhold_<module>-<version>.jar with its metadata: the display
name, the changelog, the release type, the game versions and the required projects. With --jars <folder>, the script
reads folkhold_<module>-<version>.jar from this folder. The game versions are the ids of "1.21.1", "NeoForge",
"Java 21", "Client" and "Server" in the list of CurseForge. The release type is the value of --release-type, or else
the value of release_type in gradle.properties: "alpha", "beta" or "release" (see scripts/version.py). The modules go
in the order of the file, so a required Folkhold project gets its file first.

When CurseForge refuses the file of a module, the script continues with the next module. At the end, it names each
refused module and exits with the status 1. A missing jar or a missing game version stops the script before the first
upload. In GitHub Actions, the script also writes a notice for each file and an error for each refused module.

Usage: python3 scripts/curseforge_publish.py <version> <changelog file (Markdown)> [--dry-run] [--only <module>]
       [--jars <folder>] [--release-type alpha|beta|release]
The environment variable CURSEFORGE_TOKEN gives the API token. With --dry-run, the script reads no token, sends
nothing and prints the metadata. --only can repeat, and each value can be a list of modules with commas.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

from version import RELEASE_TYPES, release_type

ROOT = os.path.join(os.path.dirname(__file__), "..")
PROJECTS = os.path.join(ROOT, "docs", "curseforge", "projects.json")
API = "https://minecraft.curseforge.com/api"
# The game versions of a file: the name in the list of CurseForge, and the start of the slug of its version type.
GAME_VERSIONS = (("1.21.1", "minecraft-"), ("NeoForge", "modloader"), ("Java 21", "java"), ("Client", "environment"),
                 ("Server", "environment"))
# The time limit of each HTTP request, in seconds.
TIMEOUT = 300


def game_version_ids(versions, types):
    """Returns the ids of GAME_VERSIONS. `versions` and `types` are the lists of the Game Versions API."""
    slugs = {t["id"]: t["slug"] for t in types}
    ids = []
    for name, prefix in GAME_VERSIONS:
        found = [v["id"] for v in versions
                 if v["name"] == name and slugs.get(v["gameVersionTypeID"], "").startswith(prefix)]
        if len(found) != 1:
            raise ValueError(f"The game version {name!r} ({prefix}*) has {len(found)} ids in the list of CurseForge")
        ids.append(found[0])
    return ids


def metadata(project, version, changelog, version_ids, kind=None):
    """Returns the metadata of the file of a project. `kind` is the release type, or None for gradle.properties."""
    data = {
        "changelog": changelog,
        "changelogType": "markdown",
        "displayName": f"{project['name']} {version}",
        "gameVersions": version_ids,
        "releaseType": kind or release_type(),
    }
    if project["requires"]:
        data["relations"] = {"projects": [{"slug": slug, "type": "requiredDependency"}
                                          for slug in project["requires"]]}
    return data


def multipart(data, file_name, content):
    """Returns the content type and the body of the upload request (multipart/form-data)."""
    boundary = uuid.uuid4().hex
    body = b"".join((
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n".encode(),
        json.dumps(data).encode(), b"\r\n",
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\n"
        "Content-Type: application/java-archive\r\n\r\n".encode(),
        content, f"\r\n--{boundary}--\r\n".encode()))
    return f"multipart/form-data; boundary={boundary}", body


class CurseForge:
    """The CurseForge upload API with an API token."""

    def __init__(self, token):
        self.token = token

    def request(self, path, body=None, content_type=None):
        headers = {"X-Api-Token": self.token, "User-Agent": "folkhold-release"}
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(API + path, data=body, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", "replace")[:500]
            raise RuntimeError(f"CurseForge refused {path}: HTTP {error.code} {detail}") from None

    def versions(self):
        return self.request("/game/versions")

    def version_types(self):
        return self.request("/game/version-types")

    def upload(self, project_id, data, file_name, content):
        content_type, body = multipart(data, file_name, content)
        return self.request(f"/projects/{project_id}/upload-file", body, content_type)["id"]


def jar_path(module, version, folder=None):
    """Returns the path of the jar of a module: in `folder`, or else in the build folder of the module."""
    name = f"folkhold_{module}-{version}.jar"
    if folder:
        return os.path.join(folder, name)
    return os.path.join(ROOT, module, "build", "libs", name)


def annotate(level, message):
    """Writes a GitHub Actions annotation ("notice" or "error") when the script runs in GitHub Actions."""
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::{level} title=CurseForge::{message}")


class Result:
    """The result of the uploads: the file id of each sent module, and the error of each refused module."""

    def __init__(self):
        self.files = {}
        self.refused = {}


def publish(api, projects, version, changelog, read=None, folder=None, kind=None):
    """Uploads the jar of each project and returns a Result. `read` returns the bytes of a jar.

    A refused upload (RuntimeError) does not stop the next uploads.
    """
    if read is None:
        def read(path):
            with open(path, "rb") as file:
                return file.read()
    jars = {module: read(jar_path(module, version, folder)) for module in projects}
    version_ids = game_version_ids(api.versions(), api.version_types())
    result = Result()
    for module, project in projects.items():
        name = os.path.basename(jar_path(module, version, folder))
        try:
            file_id = api.upload(project["id"], metadata(project, version, changelog, version_ids, kind), name,
                                 jars[module])
        except RuntimeError as error:
            result.refused[module] = str(error)
            print(f"Refused {name} for the project {project['slug']} ({project['id']}): {error}", file=sys.stderr)
            annotate("error", f"{module}: {error}")
            continue
        result.files[module] = file_id
        print(f"Uploaded {name} to the project {project['slug']} ({project['id']}): file {file_id}")
        annotate("notice", f"{module}: {name} is the file {file_id} of the project {project['slug']}")
    return result


def selected(projects, only):
    """Returns the projects of the modules in `only`, in the order of the file. Each value can have commas."""
    if not only:
        return projects
    modules = [module for value in only for module in value.replace(",", " ").split()]
    unknown = [module for module in modules if module not in projects]
    if unknown:
        raise ValueError(f"Unknown module: {', '.join(unknown)}")
    if not modules:
        raise ValueError("--only has no module")
    return {module: project for module, project in projects.items() if module in modules}


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("version")
    parser.add_argument("changelog")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--only", action="append", help="upload only these modules (can repeat, commas allowed)")
    parser.add_argument("--jars", help="the folder of the jars (default: the build folder of each module)")
    parser.add_argument("--release-type", choices=RELEASE_TYPES,
                        help="the release type (default: release_type in gradle.properties)")
    args = parser.parse_args()
    with open(PROJECTS, encoding="utf-8") as file:
        projects = json.load(file)
    try:
        projects = selected(projects, args.only)
    except ValueError as error:
        sys.exit(str(error))
    with open(args.changelog, encoding="utf-8") as file:
        changelog = file.read()
    if args.dry_run:
        for project in projects.values():
            print(json.dumps(metadata(project, args.version, changelog, ["<ids of the game versions>"],
                                      args.release_type), indent=2))
        return
    token = os.environ.get("CURSEFORGE_TOKEN")
    if not token:
        sys.exit("The environment variable CURSEFORGE_TOKEN is empty.")
    try:
        result = publish(CurseForge(token), projects, args.version, changelog, folder=args.jars,
                         kind=args.release_type)
    except (RuntimeError, ValueError, OSError) as error:
        sys.exit(str(error))
    if result.refused:
        sys.exit(f"CurseForge refused the modules: {', '.join(result.refused)}. "
                 f"Uploaded modules: {', '.join(result.files) or 'none'}.")

if __name__ == "__main__":
    main()
