import unittest

from preservation_check import digest_sql, projected_row_sql


class PreservationCheckTest(unittest.TestCase):
    def test_non_user_projection_uses_source_column_names(self):
        expression = projected_row_sql("crawlrun", ["id", "status"])
        self.assertIn("row_data.\"id\"", expression)
        self.assertNotIn("issues", expression)

    def test_users_projection_excludes_membership_id_and_normalizes_grade(self):
        expression = projected_row_sql("users", [
            "userid", "membershipid", "membershipgradecode", "membershipGradeCode", "email",
        ])
        self.assertIn("'userid', row_data.\"userid\"", expression)
        self.assertIn("'email', row_data.\"email\"", expression)
        self.assertIn("'effective_grade', COALESCE(row_data.\"membershipgradecode\", row_data.\"membershipGradeCode\")", expression)
        self.assertNotIn("membershipid", expression)

    def test_digest_counts_nulls_deterministically_without_returning_rows(self):
        sql = digest_sql("users", ["userid", "membershipid"])
        self.assertIn("count(*)", sql)
        self.assertIn("md5(string_agg(row_hash, '' ORDER BY row_hash))", sql)
        self.assertIn("COALESCE", sql)
        self.assertNotIn("membershipid", sql)

    def test_source_projection_omits_target_only_columns(self):
        expression = projected_row_sql("users", ["userid"], ["userid", "membershipGradeCode"])
        self.assertIn("row_data.\"membershipGradeCode\"", expression)


if __name__ == "__main__":
    unittest.main()
