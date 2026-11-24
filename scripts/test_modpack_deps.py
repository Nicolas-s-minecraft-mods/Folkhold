"""Tests of scripts/modpack_deps.py. Run: python3 -m unittest discover -s scripts -p "test_*.py" """
import hashlib
import io
import json
import os
import tempfile
import unittest
import zipfile

import modpack_deps


def mods_toml(mod_id, version, dependencies=(), provides=()):
    """The text of a neoforge.mods.toml file. `dependencies` has (mod id, type, version range) items."""
    lines = ['modLoader = "javafml"', 'loaderVersion = "[4,)"', "[[mods]]", f'modId = "{mod_id}"',
             f'version = "{version}"']
    if provides:
        lines.append("provides = [" + ", ".join(f'"{name}"' for name in provides) + "]")
    for name, kind, version_range in dependencies:
        lines += [f"[[dependencies.{mod_id}]]", f'modId = "{name}"', f'type = "{kind}"',
                  f'versionRange = "{version_range}"']
    return "\n".join(lines) + "\n"


def jar(mod_id=None, version="1.0.0", dependencies=(), embedded=(), manifest=None, provides=()):
    """The bytes of a mod jar. `embedded` has (file name, bytes) items for META-INF/jarjar."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        if mod_id:
            archive.writestr("META-INF/neoforge.mods.toml", mods_toml(mod_id, version, dependencies, provides))
        if manifest:
            archive.writestr("META-INF/MANIFEST.MF", manifest)
        if embedded:
            archive.writestr("META-INF/jarjar/metadata.json", json.dumps({"jars": [
                {"identifier": {"group": "g", "artifact": name}, "path": f"META-INF/jarjar/{name}"}
                for name, _ in embedded]}))
            for name, data in embedded:
                archive.writestr(f"META-INF/jarjar/{name}", data)
    return buffer.getvalue()


class VersionTest(unittest.TestCase):
    def test_compare(self):
        self.assertLess(modpack_deps.compare_versions("1.9", "1.10"), 0)
        self.assertEqual(modpack_deps.compare_versions("1.0", "1"), 0)
        self.assertLess(modpack_deps.compare_versions("1.0-beta", "1.0"), 0)
        self.assertLess(modpack_deps.compare_versions("1.0-alpha.2", "1.0-beta.1"), 0)
        self.assertGreater(modpack_deps.compare_versions("1.0.1", "1.0"), 0)
        self.assertGreater(modpack_deps.compare_versions("21.1.259", "21.1.90"), 0)
        self.assertGreater(modpack_deps.compare_versions("2.1.0+neoforge", "2.1.0"), 0)
        self.assertGreater(modpack_deps.compare_versions("1.21.1-3.7.2", "1.21-2.29.33"), 0)
        self.assertEqual(modpack_deps.compare_versions("1.0-final", "1"), 0)
        self.assertEqual(modpack_deps.compare_versions("1.0a1", "1.0-alpha-1"), 0)
        self.assertLess(modpack_deps.compare_versions("1.0-rc1", "1.0-snapshot"), 0)
        self.assertLess(modpack_deps.compare_versions("1.0", "1.0-sp"), 0)

    def test_range(self):
        self.assertTrue(modpack_deps.in_range("1.5", "[1.0,2.0)"))
        self.assertFalse(modpack_deps.in_range("2.0", "[1.0,2.0)"))
        self.assertTrue(modpack_deps.in_range("2.0", "[1.0,2.0]"))
        self.assertFalse(modpack_deps.in_range("1.0", "(1.0,)"))
        self.assertTrue(modpack_deps.in_range("9.0", "[1.0,)"))
        self.assertFalse(modpack_deps.in_range("0.9", "[1.0,)"))
        self.assertTrue(modpack_deps.in_range("1.0", "[1.0]"))
        self.assertFalse(modpack_deps.in_range("1.1", "[1.0]"))
        self.assertTrue(modpack_deps.in_range("1.3", "(,1.0],[1.2,)"))
        self.assertFalse(modpack_deps.in_range("1.1", "(,1.0],[1.2,)"))

    def test_range_with_no_bracket_accepts_each_version(self):
        for version_range in ("", "*", "1.0"):
            self.assertTrue(modpack_deps.in_range("0.1", version_range))

    def test_range_that_is_not_valid(self):
        with self.assertRaises(ValueError):
            modpack_deps.in_range("1.0", "[1.0")


class ReadJarTest(unittest.TestCase):
    def test_required_dependencies_only(self):
        mods = modpack_deps.read_jar(jar("a", "1.2.3", [("b", "required", "[1,)"), ("c", "optional", "[1,)"),
                                                      ("d", "incompatible", "*")]), "a.jar")
        self.assertEqual([(mod.mod_id, mod.version, mod.dependencies) for mod in mods],
                         [("a", "1.2.3", (("b", "[1,)"),))])

    def test_mandatory_flag(self):
        toml = ('[[mods]]\nmodId = "a"\nversion = "1"\n[[dependencies.a]]\nmodId = "b"\nmandatory = true\n'
                '[[dependencies.a]]\nmodId = "c"\nmandatory = false\n')
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("META-INF/mods.toml", toml)
        mods = modpack_deps.read_jar(buffer.getvalue(), "a.jar")
        self.assertEqual(mods[0].dependencies, (("b", ""),))

    def test_version_from_the_manifest(self):
        manifest = "Manifest-Version: 1.0\r\nImplementation-Version: 4.5.6\r\n"
        mods = modpack_deps.read_jar(jar("a", "${file.jarVersion}", manifest=manifest), "a.jar")
        self.assertEqual(mods[0].version, "4.5.6")

    def test_embedded_jars(self):
        inner = jar("lib", "2.0.0", embedded=[("deep.jar", jar("deep", "3.0.0"))])
        mods = modpack_deps.read_jar(jar("a", embedded=[("lib.jar", inner)]), "a.jar")
        self.assertEqual([mod.mod_id for mod in mods], ["a", "lib", "deep"])
        self.assertEqual(mods[2].source, "a.jar (lib.jar) (deep.jar)")

    def test_jar_with_no_mod(self):
        self.assertEqual(modpack_deps.read_jar(jar(), "library.jar"), [])


class CheckTest(unittest.TestCase):
    def test_embedded_library_passes(self):
        """A mod that embeds its required library passes, as Tectonic with ApolLib."""
        mods = modpack_deps.read_jar(jar("tectonic", "3.0.0", [("apollib", "required", "[1.0,)"),
                                                              ("minecraft", "required", "[1.21.1]")],
                                         embedded=[("apollib.jar", jar("apollib", "1.2.0"))]), "tectonic.jar")
        self.assertEqual(modpack_deps.check(mods), [])

    def test_missing_library_fails(self):
        mods = modpack_deps.read_jar(jar("tectonic", "3.0.0", [("apollib", "required", "[1.0,)")]), "tectonic.jar")
        problems = modpack_deps.check(mods)
        self.assertEqual(len(problems), 1)
        self.assertIn("tectonic (tectonic.jar) requires apollib [1.0,)", problems[0])
        self.assertIn("does not have apollib", problems[0])

    def test_version_out_of_range_fails(self):
        mods = (modpack_deps.read_jar(jar("a", "1.0.0", [("b", "required", "[2.0,)")]), "a.jar")
                + modpack_deps.read_jar(jar("b", "1.9.0"), "b.jar"))
        problems = modpack_deps.check(mods)
        self.assertEqual(len(problems), 1)
        self.assertIn("The modpack has b 1.9.0", problems[0])

    def test_one_version_in_range_is_enough(self):
        mods = (modpack_deps.read_jar(jar("a", "1.0.0", [("b", "required", "[2.0,)")]), "a.jar")
                + modpack_deps.read_jar(jar("b", "1.9.0"), "old.jar")
                + modpack_deps.read_jar(jar("c", embedded=[("b.jar", jar("b", "2.1.0"))]), "c.jar"))
        self.assertEqual(modpack_deps.check(mods), [])

    def test_provided_id(self):
        mods = (modpack_deps.read_jar(jar("a", "1.0.0", [("b", "required", "*")]), "a.jar")
                + modpack_deps.read_jar(jar("b_fork", "1.0.0", provides=["b"]), "fork.jar"))
        self.assertEqual(modpack_deps.check(mods), [])

    def test_platform_dependencies_have_no_check(self):
        mods = modpack_deps.read_jar(jar("a", "1.0.0", [("minecraft", "required", "[1.21.1]"),
                                                       ("neoforge", "required", "[21.1,)"),
                                                       ("java", "required", "[21,)")]), "a.jar")
        self.assertEqual(modpack_deps.check(mods), [])


class PackTest(unittest.TestCase):
    def write_pack(self, folder, files):
        """Writes one metafile for each (name, jar bytes) item. Returns the jars by URL."""
        os.makedirs(os.path.join(folder, "mods"))
        served = {}
        for name, data in files:
            url = f"https://example.invalid/{name}.jar"
            served[url] = data
            with open(os.path.join(folder, "mods", f"{name}.pw.toml"), "w", encoding="utf-8") as file:
                file.write(f'name = "{name}"\nfilename = "{name}.jar"\n[download]\nurl = "{url}"\n'
                           f'hash-format = "sha512"\nhash = "{hashlib.sha512(data).hexdigest()}"\n')
        return served

    def test_pack_and_folder(self):
        with tempfile.TemporaryDirectory() as folder:
            served = self.write_pack(folder, [("b", jar("b", "1.0.0"))])
            jars = os.path.join(folder, "libs")
            os.makedirs(jars)
            with open(os.path.join(jars, "a-1.0.0.jar"), "wb") as file:
                file.write(jar("a", "1.0.0", [("b", "required", "[1.0,)"), ("c", "required", "*")]))
            with open(os.path.join(jars, "a-1.0.0-sources.jar"), "wb") as file:
                file.write(jar("ignored", "1.0.0", [("missing", "required", "*")]))
            mods = modpack_deps.pack_mods(folder, fetch=lambda url, kind, expected, cache: served[url])
            mods += modpack_deps.folder_mods(jars)
            problems = modpack_deps.check(mods)
            self.assertEqual(len(problems), 1)
            self.assertIn("requires c", problems[0])

    def test_download_checks_the_hash_and_uses_the_cache(self):
        with tempfile.TemporaryDirectory() as cache:
            data = jar("a")
            expected = hashlib.sha512(data).hexdigest()
            with open(os.path.join(cache, f"{expected}.jar"), "wb") as file:
                file.write(data)
            self.assertEqual(modpack_deps.download("https://example.invalid/a.jar", "sha512", expected, cache), data)
            with open(os.path.join(cache, "bad.jar"), "wb") as file:
                file.write(b"other")
            with self.assertRaises(SystemExit):
                modpack_deps.download("https://example.invalid/a.jar", "sha512", "bad", cache)


if __name__ == "__main__":
    unittest.main()
