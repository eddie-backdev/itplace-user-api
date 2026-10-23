import unittest

from smoke import envelope_data, parse_sse


class SmokeHelpersTest(unittest.TestCase):
    def test_parse_named_sse_events_and_multiline_data(self):
        raw = (
            b'event: status\ndata: {"stage":"planning"}\n\n'
            b'event: done\ndata: {"status":\ndata: "OUT_OF_SCOPE","partners":[]}\n\n'
        )
        self.assertEqual(list(parse_sse(raw)), [
            ("status", {"stage": "planning"}),
            ("done", {"status": "OUT_OF_SCOPE", "partners": []}),
        ])

    def test_parse_invalid_event_json_without_exposing_payload(self):
        self.assertEqual(list(parse_sse(b"event: error\ndata: private detail\n\n")), [("error", None)])

    def test_envelope_requires_data_container(self):
        self.assertEqual(envelope_data({"data": []}), [])
        with self.assertRaisesRegex(Exception, "expected data envelope"):
            envelope_data({"data": "private"})

    def test_benefit_page_contract_has_content_and_benefit_id(self):
        page = envelope_data({"code": "BENEFIT_LIST_SUCCESS", "data": {
            "content": [{"benefitId": 42, "benefitName": "영화 할인"}],
            "totalElements": 1, "totalPages": 1, "currentPage": 0, "hasNext": False,
        }})
        self.assertEqual(page["content"][0]["benefitId"], 42)

    def test_chat_done_contract_is_response_payload(self):
        raw = b'event: done\ndata: {"status":"ANSWER","partners":[{"partnerName":"CGV"}]}\n\n'
        event, result = next(parse_sse(raw))
        self.assertEqual(event, "done")
        self.assertEqual(result["status"], "ANSWER")
        self.assertEqual(result["partners"][0]["partnerName"], "CGV")


if __name__ == "__main__":
    unittest.main()
