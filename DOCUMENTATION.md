# Ekko Semantic Documentation

## Overview

Ekko Semantic is split into two layers:

1. An Android app that owns document ingestion, indexing, storage, search, and most UI flows.
2. A small backend that performs answer generation and summary generation against already-selected content.

The app is the source of truth for documents and retrieval. The backend is not a crawler or vector store.

## End-to-End Flow

### 1. Folder import and indexing

The app lets the user select folders. From there:

1. Files are scanned from selected sources.
2. Supported types are extracted into raw text.
3. Text is cleaned and normalized.
4. Documents are chunked for retrieval.
5. Full-document and per-chunk embeddings are generated.
6. Category, keywords, summary, and entities are derived.
7. Everything is stored in Room.

Relevant code:

- [DocumentScanner.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/processing/DocumentScanner.java)
- [DocumentIndexer.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/processing/DocumentIndexer.java)
- [ChunkUtils.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/processing/ChunkUtils.java)
- [BackgroundIndexWorker.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/work/BackgroundIndexWorker.java)

### 2. Search

Search is local-first.

The current search path combines:

- document embedding similarity
- metadata text matching
- summary text matching
- raw-text matching
- filename/path matching
- chunk-level signals for shortlisted documents

Relevant code:

- [DocumentRepository.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/data/repository/DocumentRepository.java)
- [SearchTextMatcher.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/data/repository/SearchTextMatcher.java)

### 3. Q&A and file chat

The app embeds the question, selects relevant documents/chunks, and only then calls the backend.

Current behavior:

- global Q&A shortlists documents first, then scores chunks inside that shortlist
- file-scoped chat can send the full selected document context
- the backend applies keyword/relevance gating for grounded mode
- brief-answer requests are detected and constrained more tightly

Relevant code:

- [RagRepository.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/data/repository/RagRepository.java)
- [QAViewModel.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ui/qa/QAViewModel.java)
- [backend/main.py](/home/izumii/coding/java-applications/ekko-semantic/backend/main.py)

## Android Architecture

The Android side is organized in a fairly direct way:

- Room DAOs for persistence
- repositories for retrieval and scoring logic
- view models for UI-facing state
- workers for long-running indexing/import tasks

Important packages:

- `data/db`
- `data/model`
- `data/repository`
- `processing`
- `ml`
- `ui`
- `work`

## UI Areas

### Home

Home lists documents, supports grouped/folder navigation, and surfaces live indexing progress.

Relevant files:

- [HomeFragment.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ui/home/HomeFragment.java)
- [fragment_home.xml](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/res/layout/fragment_home.xml)

### Graph

The graph screen now supports:

- cluster mode: category-centric overview
- folder mode: folder-to-files navigation

Relevant files:

- [GraphActivity.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ui/graph/GraphActivity.java)
- [GraphViewModel.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ui/graph/GraphViewModel.java)
- [ClusterGraphView.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ui/graph/ClusterGraphView.java)
- [activity_graph.xml](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/res/layout/activity_graph.xml)

### Detail, search, and Q&A

- Detail shows metadata, summaries, and file-level actions
- Search shows ranked results
- Q&A drives global and file-scoped assistant behavior

## Backend Architecture

The backend is intentionally narrow:

- it does not index files
- it does not own embeddings
- it does not select documents globally

It accepts preselected chunks and focuses on:

- grounding logic
- prompt assembly
- streaming/non-streaming answer generation
- summary generation

Endpoints:

- `GET /health`
- `POST /rag`
- `POST /rag/stream`
- `POST /summary`

## Release / Build Notes

Useful commands:

```bash
cd ekko-app
./gradlew app:assembleDebug
./gradlew app:assembleRelease
```

Backend sanity check:

```bash
python3 -m py_compile backend/main.py
```

## Known Constraints

- indexing large libraries is still compute-heavy on-device
- semantic quality depends on the local embedding approximation and chunk quality
- backend behavior depends on Ollama availability and the configured local model
- some unit tests may fail independently of current feature work if older assertions drift from current ranking behavior
