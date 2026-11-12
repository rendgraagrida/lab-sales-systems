import copy
from pathlib import Path
import sys
import unittest

if sys.platform == 'linux':
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
    try:
        import deploy_linux
    finally:
        sys.path.pop(0)


@unittest.skipUnless(sys.platform == 'linux', 'Native Linux deployment adapter')
class RollbackHistoryTests(unittest.TestCase):
    def release(self, revision, jar, manifest, run):
        return {'revision': revision * 40, 'jar_sha256': jar * 64,
                'manifest_sha256': manifest * 64, 'run_id': str(run), 'main_pid': 100 + run}

    def test_repeated_current_release_does_not_hide_previous_release(self):
        first = self.release('a', '1', '2', 1)
        current = self.release('b', '3', '4', 2)
        state = {'accepted_release': current, 'history': [first, current, dict(current)]}
        original = copy.deepcopy(state)
        self.assertEqual(first, deploy_linux.previous_distinct_release(state))
        self.assertEqual(original, state)

    def test_new_run_manifest_for_same_application_is_not_a_rollback(self):
        first = self.release('a', '1', '2', 1)
        prior_run = self.release('b', '3', '4', 2)
        current = self.release('b', '3', '5', 3)
        state = {'accepted_release': current, 'history': [first, prior_run, current]}
        self.assertEqual(first, deploy_linux.previous_distinct_release(state))

    def test_most_recent_distinct_application_wins_after_prior_rollback(self):
        first = self.release('a', '1', '2', 1)
        other = self.release('b', '3', '4', 2)
        current = dict(first)
        state = {'accepted_release': current, 'history': [first, other, current, dict(current)]}
        self.assertEqual(other, deploy_linux.previous_distinct_release(state))

    def test_changed_jar_at_same_revision_remains_a_distinct_rollback(self):
        previous = self.release('a', '1', '2', 1)
        current = self.release('a', '3', '4', 2)
        state = {'accepted_release': current, 'history': [previous, current]}
        self.assertEqual(previous, deploy_linux.previous_distinct_release(state))

    def test_missing_distinct_history_fails_instead_of_redeploying_current(self):
        current = self.release('a', '1', '2', 1)
        for state in ({}, {'accepted_release': current, 'history': []},
                      {'accepted_release': current, 'history': [current, dict(current)]}):
            with self.subTest(state=state), self.assertRaises(deploy_linux.DeployError):
                deploy_linux.previous_distinct_release(state)


if __name__ == '__main__':
    unittest.main()
