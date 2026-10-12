"""Synthetic leaf-first stacks keep profiler attribution independent of JDK recordings."""

import contextlib
import io
import unittest

from pytool.jfr_hotspots import neighbours, tally


class JfrHotspotsTest(unittest.TestCase):
    def test_jdk_leaf_retains_project_inclusive_cost(self):
        own = "mmm.Work.run()"
        caller = "mmm.Loop.step()"
        self_hits, inclusive = tally([
            ["java.math.BigInteger.multiply()", own, caller],
            [own, caller],
            ["java.lang.Thread.sleep()"],
            [],
        ], "mmm")
        self.assertEqual(dict(self_hits), {own: 1})
        self.assertEqual(dict(inclusive), {own: 2, caller: 2})

    def test_caller_and_callee_follow_leaf_first_order(self):
        stack = [["Leaf.run()", "Target.run()", "Parent.run()"]]
        for direction, expected, excluded in [
            ("caller", "Parent.run()", "Leaf.run()"),
            ("callee", "Leaf.run()", "Parent.run()"),
        ]:
            with self.subTest(direction=direction):
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    neighbours(stack, "Target", direction, 10)
                self.assertIn(expected, output.getvalue())
                self.assertNotIn(excluded, output.getvalue())


if __name__ == "__main__":
    unittest.main()
