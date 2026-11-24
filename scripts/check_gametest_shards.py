"""Checks the shards of the GameTests of a CI run: each test ran in one shard and only one.

Each shard of the CI uploads the folder run-gametest/shard-report of its GameTest server (see GameTestShards in the
GameTests of core): all-tests.txt has the names of all the registered tests, and shard-tests.txt has the names of the
tests of the shard. A name is "<batch> <test>".

The check fails when a shard has no report, when two shards do not have the same list of all the tests, or when the
tests of the shards together are not the list of all the tests (a test in no shard, or in two shards).

Each shard also writes failed-tests.txt in its report folder (see "failures" below): one line for each required test
that failed, "<test> failed at <position>! <message>". The check shows each failed test of each shard as an error
annotation and in the job summary. A failed test does not change the result of the check: the result of the shards
gives the result of the GameTests.

Usage:
  python3 scripts/check_gametest_shards.py <folder> <number of shards>
    The folder has one sub-folder for each shard: folkhold-gametest-shard-<i>.
  python3 scripts/check_gametest_shards.py failures <log> <output file> <shard>
    Writes the failed required tests of the log of a GameTest server to the output file. Prints an error annotation
    and a line of the job summary for each of them.
"""
import collections
import os
import re
import sys

# A failed required test in the log of the GameTest server. An optional test has "(optional)" before its name.
FAILED = re.compile(r"LogTestReporter\]: (?!\(optional\))(\S+ failed at .*)$")


def read(path):
    with open(path, encoding="utf-8") as file:
        return [line.rstrip("\n") for line in file if line.strip()]


def check(folder, count):
    """Returns the list of the errors, and the numbers of the tests: all, and of each shard."""
    errors = []
    all_tests = None
    shards = {}
    for index in range(1, count + 1):
        shard = os.path.join(folder, f"folkhold-gametest-shard-{index}")
        all_path = os.path.join(shard, "all-tests.txt")
        mine_path = os.path.join(shard, "shard-tests.txt")
        if not (os.path.isfile(all_path) and os.path.isfile(mine_path)):
            errors.append(f"The shard {index}/{count} has no test lists")
            continue
        listed = read(all_path)
        if all_tests is None:
            all_tests = listed
        elif collections.Counter(listed) != collections.Counter(all_tests):
            errors.append(f"The shard {index}/{count} does not have the same list of all the tests")
        shards[index] = read(mine_path)
    if all_tests is None:
        return errors, 0, shards
    together = collections.Counter()
    for tests in shards.values():
        together.update(tests)
    expected = collections.Counter(all_tests)
    for name in sorted(set(expected) | set(together)):
        if together[name] < expected[name]:
            errors.append(f"No shard ran the test {name}")
        elif together[name] > expected[name]:
            errors.append(f"More than one shard ran the test {name}")
    if not shards or sum(len(tests) for tests in shards.values()) == 0:
        errors.append("The shards ran no test")
    return errors, len(all_tests), shards


def failures(log):
    """Returns the lines of the failed required tests of a log, in their order, with no duplicate."""
    found = []
    with open(log, encoding="utf-8", errors="replace") as file:
        for line in file:
            match = FAILED.search(line.rstrip("\n"))
            if match and match.group(1) not in found:
                found.append(match.group(1))
    return found


def failed_tests(folder, count):
    """Returns the failed tests of each shard, from the files failed-tests.txt of the shards."""
    result = {}
    for index in range(1, count + 1):
        path = os.path.join(folder, f"folkhold-gametest-shard-{index}", "failed-tests.txt")
        if os.path.isfile(path):
            result[index] = read(path)
    return result


def annotation(text):
    """Encodes the characters that the annotation syntax uses."""
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def write_failures(argv):
    log, output, shard = argv[2], argv[3], argv[4]
    found = failures(log) if os.path.isfile(log) else []
    os.makedirs(os.path.dirname(output) or ".", exist_ok=True)
    with open(output, "w", encoding="utf-8") as file:
        file.write("".join(line + "\n" for line in found))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    lines = [f"Shard {shard}: {len(found)} failed required tests."] + [f"- `{line}`" for line in found]
    for line in found:
        print(f"::error title=GameTest failed (shard {shard})::{annotation(line)}")
    if summary:
        with open(summary, "a", encoding="utf-8") as file:
            file.write("\n".join(lines) + "\n")
    return 0


def main(argv):
    if len(argv) == 5 and argv[1] == "failures":
        return write_failures(argv)
    if len(argv) != 3:
        print(__doc__)
        return 2
    folder, count = argv[1], int(argv[2])
    errors, total, shards = check(folder, count)
    counts = ", ".join(f"{index}/{count}: {len(tests)}" for index, tests in sorted(shards.items()))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    lines = [f"GameTests: {total} tests, {sum(len(t) for t in shards.values())} in the shards ({counts})."]
    for error in errors[:50]:
        print(f"::error title=GameTest shards::{error}")
        lines.append(f"- {error}")
    if not errors:
        print(f"::notice title=GameTest shards::{total} tests, each in one shard ({counts})")
    failed = failed_tests(folder, count)
    for index, tests in sorted(failed.items()):
        for test in tests[:50]:
            print(f"::error title=GameTest failed (shard {index}/{count})::{annotation(test)}")
            lines.append(f"- Shard {index}/{count}: `{test}`")
    if summary:
        with open(summary, "a", encoding="utf-8") as file:
            file.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
