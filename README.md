# Ekko Semantic

Ekko Semantic is an Android-first document intelligence project. The app indexes local files on device, runs semantic search and document analysis locally, and can optionally call a lightweight FastAPI backend for grounded question answering and backend-generated summaries.

## What It Does

- Indexes PDF, DOCX, PPTX, and TXT files from user-selected folders
- Stores extracted text, chunks, embeddings, summaries, and metadata locally
- Supports semantic search, grouped browsing, and folder browsing
- Provides file chat and library-wide Q&A through a local RAG backend
- Includes graph views for both category clusters and folder/file relationships
- Generates summaries and document-level metadata for detail views

## Repository Layout

```text
.
├── backend/      FastAPI service used for RAG and summary generation
├── ekko-app/     Android application
├── ml.md         ML and ranking notes
└── DOCUMENTATION.md
```

## Main Components

### Android app

Key areas inside `ekko-app/app/src/main/java/com/semantic/ekko/`:

- `data/`: Room models, DAOs, repositories
- `processing/`: document scanning, extraction, chunking, indexing
- `ml/`: embeddings, classification, entity extraction helpers, summarization
- `ui/`: home, search, detail, graph, settings, Q&A, statistics
- `work/`: background indexing and storage import workers
- `network/`: backend request/response models and Retrofit wiring

### Backend

The backend in `backend/main.py` exposes:

- `GET /health`
- `POST /rag`
- `POST /rag/stream`
- `POST /summary`

It accepts selected chunks from the app, applies relevance and grounding rules, and forwards the prompt to Ollama.

## Setup

### Android app

Requirements:

- Android Studio / Gradle Android toolchain
- Android device or emulator with API 26+

Build commands:

```bash
cd ekko-app
./gradlew app:assembleDebug
./gradlew app:assembleRelease
```

### Backend

Requirements:

- Python 3.10+
- Ollama running locally

Typical local run:

```bash
cd backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload
```

Useful backend env vars:

- `OLLAMA_URL`
- `OLLAMA_MODEL`
- `OLLAMA_REQUEST_TIMEOUT`
- `OLLAMA_RELEVANCE_TIMEOUT`

## Current Behavior Notes

- Search and chunk retrieval are primarily on-device
- Normal document Q&A is grounded to indexed content
- File-scoped chat can use a more general answer mode
- Brief-answer requests are passed through to the backend and capped more tightly
- The graph screen now supports both cluster and folder modes

## Documentation

- Project architecture and workflows: [DOCUMENTATION.md](/home/izumii/coding/java-applications/ekko-semantic/DOCUMENTATION.md)
- ML and ranking details: [ml.md](/home/izumii/coding/java-applications/ekko-semantic/ml.md)
- Android module notes: [ekko-app/README.md](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/README.md)
