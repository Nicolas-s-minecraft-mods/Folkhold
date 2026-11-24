"""Tests of scripts/curseforge_publish.py. Run: python3 -m unittest discover -s scripts -p "test_*.py" """
import json
import os
import unittest

import curseforge_publish
import version

TYPES = [{"id": 1, "slug": "minecraft-1-21"}, {"id": 2, "slug": "modloader"}, {"id": 3, "slug": "java"},
         {"id": 4, "slug": "environment"}, {"id": 5, "slug": "bukkit"}]
VERSIONS = [{"id": 10, "gameVersionTypeID": 1, "name": "1.21.1"}, {"id": 11, "gameVersionTypeID": 5, "name": "1.21.1"},
            {"id": 20, "gameVersionTypeID": 2, "name": "NeoForge"}, {"id": 21, "gameVersionTypeID": 2, "name": "Forge"},
            {"id": 30, "gameVersionTypeID": 3, "name": "Java 21"}, {"id": 40, "gameVersionTypeID": 4, "name": "Client"},
            {"id": 41, "gameVersionTypeID": 4, "name": "Server"}]


class FakeApi:
    def __init__(self):
        self.uploads = []

    def versions(self):
        return VERSIONS

    def version_types(self):
        return TYPES

    def upload(self, project_id, data, file_name, content):
        self.uploads.append((project_id, data, file_name, content))
        return 1000 + len(self.uploads)


def projects():
    with open(curseforge_publish.PROJECTS, encoding="utf-8") as file:
        return json.load(file)


class GameVersionTest(unittest.TestCase):
    def test_ids(self):
        self.assertEqual(curseforge_publish.game_version_ids(VERSIONS, TYPES), [10, 20, 30, 40, 41])

    def test_missing_version(self):
        with self.assertRaises(ValueError):
            curseforge_publish.game_version_ids([v for v in VERSIONS if v["name"] != "NeoForge"], TYPES)


class MetadataTest(unittest.TestCase):
    def test_release_type(self):
        """The release type of each file is the value of release_type in gradle.properties."""
        expected = version.release_type()
        self.assertIn(expected, ("alpha", "beta", "release"))
        for module, project in projects().items():
            with self.subTest(module=module):
                data = curseforge_publish.metadata(project, "0.35.0", "notes", [10])
                self.assertEqual(data["releaseType"], expected)

    def test_relations(self):
        data = curseforge_publish.metadata(projects()["world"], "0.35.0", "notes", [10])
        self.assertEqual(data["displayName"], "Folkhold World 0.35.0")
        self.assertEqual([r["slug"] for r in data["relations"]["projects"]],
                         ["folkhold-core", "folkhold-blocks", "geckolib", "cartofold", "confee"])
        self.assertEqual({r["type"] for r in data["relations"]["projects"]}, {"requiredDependency"})

    def test_no_relations(self):
        self.assertNotIn("relations", curseforge_publish.metadata(projects()["core"], "0.35.0", "notes", [10]))

    def test_multipart(self):
        content_type, body = curseforge_publish.multipart({"a": 1}, "x.jar", b"JAR")
        boundary = content_type.split("boundary=")[1].encode()
        self.assertEqual(body.count(b"--" + boundary), 3)
        self.assertIn(b'name="metadata"\r\n\r\n{"a": 1}\r\n', body)
        self.assertIn(b'filename="x.jar"', body)
        self.assertIn(b"\r\n\r\nJAR\r\n", body)


class ProjectsTest(unittest.TestCase):
    def test_a_required_folkhold_project_comes_first(self):
        seen = set()
        for project in projects().values():
            for slug in project["requires"]:
                if slug.startswith("folkhold-"):
                    self.assertIn(slug, seen)
            seen.add(project["slug"])

    def test_the_requirements_are_the_dependencies_of_the_mod(self):
        """The required projects are the required mods of neoforge.mods.toml, without the loader and the game."""
        for module, project in projects().items():
            path = os.path.join(curseforge_publish.ROOT, module, "src", "main", "templates", "META-INF",
                                "neoforge.mods.toml")
            mods = set()
            with open(path, encoding="utf-8") as file:
                for line in file:
                    line = line.strip()
                    if line.startswith("modId") and "${" not in line:
                        mods.add(line.split('"')[1])
            mods -= {"neoforge", "minecraft"}
            self.assertEqual({slug.replace("-", "_") for slug in project["requires"]}, mods, module)


class PublishTest(unittest.TestCase):
    def test_all_the_jars_are_uploaded(self):
        api = FakeApi()
        files = curseforge_publish.publish(api, projects(), "0.35.0", "notes", read=lambda path: path.encode())
        self.assertEqual(list(files), ["core", "blocks", "world", "balance", "events"])
        self.assertEqual([u[0] for u in api.uploads], [1821089, 1821098, 1821107, 1821102, 1821115])
        self.assertEqual(api.uploads[0][2], "folkhold_core-0.35.0.jar")
        self.assertEqual(api.uploads[0][1]["gameVersions"], [10, 20, 30, 40, 41])

    def test_a_missing_jar_stops_before_the_first_upload(self):
        api = FakeApi()

        def read(path):
            if "events" in path:
                raise FileNotFoundError(path)
            return b""

        with self.assertRaises(FileNotFoundError):
            curseforge_publish.publish(api, projects(), "0.35.0", "notes", read=read)
        self.assertEqual(api.uploads, [])


if __name__ == "__main__":
    unittest.main()
