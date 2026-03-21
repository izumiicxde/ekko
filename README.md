# Ekko Semantic

Ekko Semantic is a multi-part document intelligence project built around the Ekko Android app and a lightweight retrieval-augmented backend. The repository combines on-device document indexing and semantic search with an optional server-side question-answering service.

The Android application is the primary product in this repository. The backend is a separate FastAPI service that accepts retrieved document chunks and forwards them to a local Ollama model for answer generation.

## Repository Layout

```text
.
├── backend/    FastAPI RAG service for chunk-based question answering
└── ekko-app/   Android app for offline document intelligence
```

## Components

### `ekko-app`

The Android app indexes local documents from user-selected folders and performs:

- Semantic search
- Document classification
- Extractive summarization
- Entity extraction
- Local storage with Room

Supported file types include PDF, DOCX, PPTX, and TXT.

See the app-specific guide here:
[`ekko-app/README.md`](ekko-app/README.md)

### `backend`

The backend is a small FastAPI service with:

- `GET /health` for service status
- `POST /rag` for chunk-based answer generation

It expects the client to send:

- A user question
- Top relevant document chunks
- An optional document name

The service builds a grounded prompt and forwards it to a local Ollama instance using the `gemma2:2b` model by default.

## How The Project Fits Together

1. The Android app scans and indexes local files on device.
2. Search and relevance scoring happen locally in the app.
3. The backend can be used as an optional second layer for answer generation from selected chunks.
4. Ollama runs locally and serves the language model used by the backend.

At the moment, the Android app README is the source of truth for the mobile application’s ML pipeline, structure, and setup details.

## Getting Started

### Android App

Open `ekko-app` in Android Studio and follow the setup instructions in:
[`ekko-app/README.md`](ekko-app/README.md)

Important app prerequisites:

- Android Studio
- Android API 26+
- The MiniLM TFLite model in `ekko-app/app/src/main/assets/all_minilm_l6_v2.tflite`
- Internet access on first launch for ML Kit entity model download

### Backend

Prerequisites:

- Python 3.10+
- Ollama running locally
- The Ollama model configured in `backend/main.py`

Install dependencies:

```bash
cd backend
python3 -m venv env
source env/bin/activate
pip install -r requirements.txt
```

Start the API:

```bash
cd backend
source env/bin/activate
uvicorn main:app --reload
```

The backend defaults to:

- API base: `http://127.0.0.1:8000`
- Ollama endpoint: `http://localhost:11434/api/generate`
- Ollama model: `gemma2:2b`

## API Summary

### `GET /health`

Returns service status and the configured model name.

### `POST /rag`

Request body:

```json
{
  "question": "What does this document say about deployment?",
  "chunks": [
    "Chunk 1 text",
    "Chunk 2 text"
  ],
  "document_name": "architecture-notes.pdf"
}
```

Response body:

```json
{
  "answer": "Generated answer",
  "chunks_used": 2
}
```

## Development Notes

- The mobile app is fully useful on its own for offline document indexing and search.
- The backend is optional and currently isolated as a standalone service under `backend/`.
- Model names and Ollama connection details are hardcoded in `backend/main.py` and should be moved to environment variables if the service is expanded.

## Current Source Of Truth

For Android architecture, features, ML pipeline details, and app screens, refer to:
[`ekko-app/README.md`](ekko-app/README.md)
