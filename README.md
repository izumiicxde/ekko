# Ekko Semantic

Ekko Semantic is a document intelligence project built around the Ekko Android app and a lightweight retrieval-augmented backend. It combines on-device document indexing, semantic search, document analysis, and an optional server-side question-answering service.

The Android application is the main product in this repository. The backend is a separate FastAPI service that accepts retrieved document chunks and forwards them to a local Ollama model for grounded answer generation.

## Repository Layout

```text
.
├── backend/    FastAPI RAG service for chunk-based question answering
└── ekko-app/   Android app for offline document intelligence
```

## Features

### Android App

- Indexes PDF, DOCX, PPTX, and TXT files from user-selected folders
- Semantic search using sentence embeddings and cosine similarity
- Automatic document classification into Technical, Research, Legal, Medical, and General categories
- Extractive summarization using Maximal Marginal Relevance
- Entity extraction for dates, addresses, emails, phone numbers, URLs, and monetary amounts
- Keyword extraction per document
- Material You dynamic theming with light and dark mode support
- Fully offline after initial ML Kit model download

### Backend

- `GET /health` endpoint for service status
- `POST /rag` endpoint for chunk-based answer generation
- Grounded prompt construction from retrieved document chunks
- Local Ollama integration for answer synthesis

## How The Project Fits Together

1. The Android app scans and indexes local files on device.
2. Search and relevance scoring happen locally in the app.
3. The app can optionally send top document chunks to the backend for answer generation.
4. The backend forwards the grounded prompt to Ollama running locally.

## ML Algorithms

| Algorithm               | Implementation            | Purpose                                   |
| ----------------------- | ------------------------- | ----------------------------------------- |
| Sentence Embedding      | TFLite MiniLM-L6-v2       | Converts text to 384-dim semantic vectors |
| Cosine Similarity       | `EmbeddingEngine.java`    | Ranks documents against a search query    |
| Centroid Classification | `DocumentClassifier.java` | Classifies documents by category          |
| MMR Summarization       | `TextSummarizer.java`     | Selects diverse, relevant sentences       |
| Entity Extraction       | ML Kit Entity Extraction  | Extracts structured entities from text    |

## Tech Stack

### Android App

- **Language:** Java
- **Platform:** Android (`minSdk 26`, `targetSdk 35`)
- **ML:** TensorFlow Lite, Google ML Kit Entity Extraction
- **Database:** Room (SQLite)
- **PDF extraction:** PdfBox Android
- **DOCX/PPTX extraction:** Apache POI
- **Charts:** MPAndroidChart
- **UI:** Material 3, Bricolage Grotesque font

### Backend

- **Language:** Python
- **Framework:** FastAPI
- **HTTP client:** httpx
- **Validation:** Pydantic
- **LLM runtime:** Ollama

## Project Structure

### Repository

```text
.
├── backend/
│   ├── main.py
│   └── requirements.txt
└── ekko-app/
    ├── app/
    ├── gradle/
    ├── build.gradle
    ├── settings.gradle
    └── README.md
```

### Android App Packages

```text
com.semantic.ekko/
├── data/
│   ├── db/          Room database, DAOs
│   ├── model/       Entity classes, SearchResult
│   └── repository/  DocumentRepository, FolderRepository
├── ml/
│   ├── EmbeddingEngine.java
│   ├── DocumentClassifier.java
│   ├── TextSummarizer.java
│   └── EntityExtractorHelper.java
├── processing/
│   ├── DocumentScanner.java
│   ├── DocumentIndexer.java
│   ├── TextPreprocessor.java
│   └── extractor/   PdfTextExtractor, DocxTextExtractor, PptxTextExtractor, TxtTextExtractor
├── ui/
│   ├── home/        HomeActivity, HomeViewModel, DocumentAdapter
│   ├── detail/      DetailActivity, DetailViewModel
│   ├── search/      SearchActivity, SearchViewModel, SearchResultAdapter
│   ├── statistics/  StatisticsActivity, StatisticsViewModel
│   ├── settings/    SettingsActivity, FolderAdapter
│   └── onboarding/  OnboardingActivity
└── util/
    ├── PrefsManager.java
    └── FileUtils.java
```

## Setup

### Prerequisites

#### Android App

- Android Studio Hedgehog or later
- Android device or emulator running API 26+
- Internet connection on first launch for ML Kit entity model download

#### Backend

- Python 3.10+
- Ollama running locally
- The Ollama model configured in `backend/main.py`

### Installation

#### Android App

1. Clone the repository.
2. Open `ekko-app` in Android Studio.
3. Download the MiniLM TFLite model and place it in `ekko-app/app/src/main/assets/all_minilm_l6_v2.tflite`:

```bash
python3 -c "
from huggingface_hub import hf_hub_download
path = hf_hub_download(
    repo_id='Madhur-Prakash-Mangal/all-MiniLM-L6-v2-tflite',
    filename='all-MiniLM-L6-v2.tflite'
)
print(path)
"
cp <printed_path> ekko-app/app/src/main/assets/all_minilm_l6_v2.tflite
```

4. Sync Gradle and build the project.

#### Backend

1. Create and activate a virtual environment.
2. Install dependencies from `backend/requirements.txt`.
3. Start the FastAPI application with Uvicorn.

```bash
cd backend
python3 -m venv env
source env/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload
```

The backend defaults to:

- API base: `http://127.0.0.1:8000`
- Ollama endpoint: `http://localhost:11434/api/generate`
- Ollama model: `llama3.2:1b`

You can override the backend runtime with environment variables:

- `OLLAMA_URL`
- `OLLAMA_MODEL`
- `OLLAMA_REQUEST_TIMEOUT`
- `OLLAMA_RELEVANCE_TIMEOUT`

## How It Works

### Indexing Pipeline

When a folder is added, Ekko runs each document through this pipeline:

1. Scan folder for supported file types
2. Extract raw text per file type
3. Clean and normalize text
4. Classify document category using centroid-based embedding similarity
5. Generate a 384-dim embedding vector for the full document
6. Generate an extractive summary using MMR sentence selection
7. Extract top keywords using term frequency
8. Extract named entities using ML Kit
9. Save all results to Room database

### Semantic Search

Search queries are embedded using MiniLM and compared against all stored document embeddings via cosine similarity. Results are ranked by relevance score.

### Summarization

Sentences are embedded individually. A centroid vector is computed from all sentence embeddings representing the document's overall topic. MMR iteratively selects sentences that are maximally relevant to the centroid while being minimally redundant with already selected sentences.

### Backend RAG Flow

1. The client sends a question and top-k document chunks to `POST /rag`.
2. The backend builds a prompt constrained to the provided context.
3. The prompt is sent to Ollama at `http://localhost:11434/api/generate`.
4. The backend returns the generated answer and number of chunks used.

## API Summary

### `GET /health`

Returns service status and the configured model name.

Example response:

```json
{
  "status": "ok",
  "model": "gemma2:2b"
}
```

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

## Known Limitations

- Indexing large documents is slow on low-end devices due to per-sentence TFLite inference. Sentence count is capped at 40 per document for performance.
- The tokenizer in `EmbeddingEngine` uses a hash-based approximation rather than the full WordPiece vocabulary, which reduces embedding quality slightly compared to the reference implementation.
- Slide-based PDFs produce fragmented text which may result in lower quality summaries.
- ML Kit entity extraction requires an internet connection on first use to download the language model.
- Backend model names and Ollama connection details are hardcoded in `backend/main.py`.
- The backend assumes Ollama is reachable locally and does not yet support environment-based configuration.

## Screens

| Screen               | Status      |
| -------------------- | ----------- |
| Home                 | Complete    |
| Search Results       | In progress |
| Document Detail      | In progress |
| Statistics           | In progress |
| Settings             | In progress |
| Onboarding           | Planned     |
| PIN / Biometric lock | Planned     |

## Notes

- The Android app is fully useful on its own for offline document indexing and search.
- The backend is optional and currently isolated as a standalone service under `backend/`.
- The app-specific README still exists at `ekko-app/README.md`, but the root README now contains the same core application overview plus repository-level backend details.
