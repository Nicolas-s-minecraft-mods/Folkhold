"""Tests of check_gametest_shards.py."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import check_gametest_shards  # noqa: E402

ALL = ["a t1", "a t2", "b t3", "c t4"]


def write(folder, index, all_tests, mine):
    shard = os.path.join(folder, f"folkhold-gametest-shard-{index}")
    os.makedirs(shard, exist_ok=True)
    with open(os.path.join(shard, "all-tests.txt"), "w", encoding="utf-8") as file:
        file.write("\n".join(all_tests) + "\n")
    with open(os.path.join(shard, "shard-tests.txt"), "w", encoding="utf-8") as file:
        file.write("\n".join(mine) + "\n")


class CheckTest(unittest.TestCase):
    def test_each_test_in_one_shard_passes(self):
        with tempfile.TemporaryDirectory() as folder:
            write(folder, 1, ALL, ["a t1", "a t2"])
            write(folder, 2, ALL, ["b t3", "c t4"])
            errors, total, shards = check_gametest_shards.check(folder, 2)
            self.assertEqual([], errors)
            self.assertEqual(4, total)
            self.assertEqual(2, len(shards[1]))

    def test_missing_test_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            write(folder, 1, ALL, ["a t1", "a t2"])
            write(folder, 2, ALL, ["b t3"])
            errors, _, _ = check_gametest_shards.check(folder, 2)
            self.assertEqual(["No shard ran the test c t4"], errors)

    def test_test_in_two_shards_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            write(folder, 1, ALL, ["a t1", "a t2", "b t3"])
            write(folder, 2, ALL, ["b t3", "c t4"])
            errors, _, _ = check_gametest_shards.check(folder, 2)
            self.assertEqual(["More than one shard ran the test b t3"], errors)

    def test_missing_shard_and_other_list_fail(self):
        with tempfile.TemporaryDirectory() as folder:
            write(folder, 1, ALL, ALL)
            write(folder, 2, ALL[:3], [])
            errors, _, _ = check_gametest_shards.check(folder, 3)
            self.assertIn("The shard 2/3 does not have the same list of all the tests", errors)
            self.assertIn("The shard 3/3 has no test lists", errors)

    def test_a_name_of_two_tests_counts_two_times(self):
        with tempfile.TemporaryDirectory() as folder:
            names = ["a t1", "a t1", "b t2"]
            write(folder, 1, names, ["a t1"])
            write(folder, 2, names, ["a t1", "b t2"])
            errors, total, _ = check_gametest_shards.check(folder, 2)
            self.assertEqual([], errors)
            self.assertEqual(3, total)


if __name__ == "__main__":
    unittest.main()
