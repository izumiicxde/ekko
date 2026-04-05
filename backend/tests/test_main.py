import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

import main


class BackendTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(main.app)

    @patch("main.is_relevant", new_callable=AsyncMock)
    def test_rag_rejects_when_keyword_gate_fails(self, mock_is_relevant):
        response = self.client.post(
            "/rag",
            json={"question": "volcano eruption", "chunks": ["database indexing notes"]},
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["answer"], main.NOT_FOUND)
        self.assertEqual(response.json()["chunks_used"], 0)
        mock_is_relevant.assert_not_awaited()

    @patch("main.is_relevant", new_callable=AsyncMock, return_value=False)
    def test_rag_rejects_when_relevance_gate_fails(self, mock_is_relevant):
        response = self.client.post(
            "/rag",
            json={"question": "database indexing", "chunks": ["database indexing notes"]},
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["answer"], main.NOT_FOUND)
        self.assertEqual(response.json()["chunks_used"], 0)
        mock_is_relevant.assert_awaited_once()

    @patch("main.is_relevant", new_callable=AsyncMock, return_value=True)
    @patch("httpx.AsyncClient.post", new_callable=AsyncMock)
    def test_rag_calls_model_after_gates_pass(self, mock_post, mock_is_relevant):
        mock_response = unittest.mock.Mock()
        mock_response.raise_for_status.return_value = None
        mock_response.json.return_value = {"response": "Grounded answer"}
        mock_post.return_value = mock_response

        response = self.client.post(
            "/rag",
            json={
                "question": "database indexing",
                "chunks": ["database indexing notes"],
                "document_name": "notes.txt",
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["answer"], "Grounded answer")
        self.assertEqual(response.json()["chunks_used"], 1)
        mock_is_relevant.assert_awaited_once()
        self.assertTrue(mock_post.await_count >= 1)
        first_payload = mock_post.await_args_list[0].kwargs["json"]
        self.assertEqual(first_payload["options"]["num_predict"], 384)

    @patch("main.is_relevant", new_callable=AsyncMock, return_value=True)
    @patch("httpx.AsyncClient.post", new_callable=AsyncMock)
    def test_rag_uses_brief_generation_options_for_short_answer_requests(
        self, mock_post, mock_is_relevant
    ):
        mock_response = unittest.mock.Mock()
        mock_response.raise_for_status.return_value = None
        mock_response.json.return_value = {"response": "Short answer"}
        mock_post.return_value = mock_response

        response = self.client.post(
            "/rag",
            json={
                "question": "Give a short answer: what is database indexing?",
                "chunks": ["Database indexing speeds up lookups."],
            },
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["answer"], "Short answer")
        first_payload = mock_post.await_args_list[0].kwargs["json"]
        self.assertEqual(first_payload["options"]["num_predict"], 120)
        self.assertIn("1-3 short sentences", first_payload["prompt"])

    @patch("main.is_relevant", new_callable=AsyncMock, return_value=False)
    def test_stream_returns_not_found_payload_when_relevance_fails(self, mock_is_relevant):
        response = self.client.post(
            "/rag/stream",
            json={"question": "database indexing", "chunks": ["database indexing notes"]},
        )

        self.assertEqual(response.status_code, 200)
        self.assertIn(main.NOT_FOUND, response.text)
        self.assertIn('"done": true', response.text.lower())
        mock_is_relevant.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
