"""Uploads the jar of each Folkhold mod to its CurseForge project.

The file docs/curseforge/projects.json gives, for each module, the id and the slug of its CurseForge project and the
slugs of the projects that it requires. The workflow Release calls this script after the GitHub Release, so the
CurseForge jars are the jars of the GitHub Release.

For each module, the script sends <module>/build/libs/folkhold_<module>-<version>.jar with its metadata: the display
name, the changelog, the release type, the game versions and the required projects. The game versions are the ids of
"1.21.1", "NeoForge", "Java 21", "Client" and "Server" in the list of CurseForge. The release type is "beta" while
the major version is 0, and "release" after that. The modules go in the order of the file, so a required Folkhold
project gets its file first.

Usage: python3 scripts/curseforge_publish.py <version> <changelog file (Markdown)> [--dry-run] [--only <module>]
The environment variable CURSEFORGE_TOKEN gives the API token. With --dry-run, the script reads no token, sends
nothing and prints the metadata.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

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


def release_type(version):
    """Returns the release type of a version: "beta" while the major version is 0."""
    return "beta" if version.split(".")[0] == "0" else "release"


def metadata(project, version, changelog, version_ids):
    """Returns the metadata of the file of a project."""
    data = {
        "changelog": changelog,
        "changelogType": "markdown",
        "displayName": f"{project['name']} {version}",
        "gameVersions": version_ids,
        "releaseType": release_type(version),
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


def jar_path(module, version):
    return os.path.join(ROOT, module, "build", "libs", f"folkhold_{module}-{version}.jar")


def publish(api, projects, version, changelog, read=None):
    """Uploads the jar of each project and returns the file id of each module. `read` returns the bytes of a jar."""
    if read is None:
        def read(path):
            with open(path, "rb") as file:
                return file.read()
    jars = {module: read(jar_path(module, version)) for module in projects}
    version_ids = game_version_ids(api.versions(), api.version_types())
    files = {}
    for module, project in projects.items():
        name = os.path.basename(jar_path(module, version))
        files[module] = api.upload(project["id"], metadata(project, version, changelog, version_ids), name,
                                   jars[module])
        print(f"Uploaded {name} to the project {project['slug']} ({project['id']}): file {files[module]}")
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("version")
    parser.add_argument("changelog")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--only", action="append", help="upload only this module (can repeat)")
    args = parser.parse_args()
    with open(PROJECTS, encoding="utf-8") as file:
        projects = json.load(file)
    if args.only:
        unknown = [module for module in args.only if module not in projects]
        if unknown:
            sys.exit(f"Unknown module: {', '.join(unknown)}")
        projects = {module: project for module, project in projects.items() if module in args.only}
    with open(args.changelog, encoding="utf-8") as file:
        changelog = file.read()
    if args.dry_run:
        for project in projects.values():
            print(json.dumps(metadata(project, args.version, changelog, ["<ids of the game versions>"]), indent=2))
        return
    token = os.environ.get("CURSEFORGE_TOKEN")
    if not token:
        sys.exit("The environment variable CURSEFORGE_TOKEN is empty.")
    try:
        publish(CurseForge(token), projects, args.version, changelog)
    except (RuntimeError, ValueError, OSError) as error:
        sys.exit(str(error))


if __name__ == "__main__":
    main()
