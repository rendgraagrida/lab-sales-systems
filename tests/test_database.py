import sys
from pathlib import Path
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
try:
    import database
finally:
    sys.path.pop(0)


class MigrationBoundaryTests(unittest.TestCase):
    def invoke(self, answers, failure=None):
        with mock.patch.object(database, 'identity'), \
                mock.patch.object(database, 'sql', side_effect=answers), \
                mock.patch.object(database, 'secure_function_defaults') as defaults, \
                mock.patch.object(database, 'maven', side_effect=failure) as maven, \
                mock.patch.object(database, 'grant_runtime') as grant:
            if failure:
                with self.assertRaises(database.OperationError):
                    database.migrate({}, 'lab')
            else:
                database.migrate({}, 'lab')
            return maven.call_args_list, defaults.call_count, grant.call_count


    def test_unknown_partial_failed_or_unowned_history_refused(self):
        for answers in (['1'], ['0', 'f', '1'], ['0', 't', '1,2,3', '0'],
                        ['0', 't', '1,2,3,4,5,6,7,8', '0'], ['0', 't', '1,2,3,4', '1']):
            with self.subTest(answers=answers), mock.patch.object(database, 'identity'), \
                    mock.patch.object(database, 'sql', side_effect=answers), \
                    mock.patch.object(database, 'maven') as maven, \
                    mock.patch.object(database, 'grant_runtime') as grant:
                with self.assertRaises(database.OperationError):
                    database.migrate({}, 'lab')
                maven.assert_not_called()
                grant.assert_not_called()

    def test_changed_v4_validation_prevents_migration_and_grants(self):
        calls, _, granted = self.invoke(['0', 't', '1,2,3,4', '0'], database.OperationError('invalid'))
        self.assertEqual([mock.call({}, ['-Dflyway.target=4', 'flyway:validate'], 'lab')], calls)
        self.assertEqual(0, granted)

    def test_changed_v5_validation_prevents_migration_and_grants(self):
        calls, _, granted = self.invoke(['0', 't', '1,2,3,4,5', '0'], database.OperationError('invalid'))
        self.assertEqual([mock.call({}, ['-Dflyway.target=5', 'flyway:validate'], 'lab')], calls)
        self.assertEqual(0, granted)


if __name__ == '__main__':
    unittest.main()
