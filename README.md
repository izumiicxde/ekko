# Ekko

Ekko is an on-device document intelligence assistant for Android. It indexes local documents from selected folders and applies a suite of machine learning algorithms to enable semantic search, automatic classification, extractive summarization, and entity extraction, all without any internet dependency beyond an initial ML Kit model download.

---

## Features

- Indexes PDF, DOCX, PPTX, and TXT files from user-selected folders
- Semantic search using sentence embeddings and cosine similarity
- Automatic document classification into Technical, Research, Legal, Medical, and General categories
- Extractive summarization using Maximal Marginal Relevance
- Entity extraction for dates, addresses, emails, phone numbers, URLs, and monetary amounts
- Keyword extraction per document
- Material You dynamic theming with light and dark mode support
- Fully offline after initial ML Kit model download

---

## ML Algorithms

| Algorithm               | Implementation            | Purpose                                   |
| ----------------------- | ------------------------- | ----------------------------------------- |
| Sentence Embedding      | TFLite MiniLM-L6-v2       | Converts text to 384-dim semantic vectors |
| Cosine Similarity       | `EmbeddingEngine.java`    | Ranks documents against a search query    |
| Centroid Classification | `DocumentClassifier.java` | Classifies documents by category          |
| MMR Summarization       | `TextSummarizer.java`     | Selects diverse, relevant sentences       |
| Entity Extraction       | ML Kit Entity Extraction  | Extracts structured entities from text    |

---

## Tech Stack

- **Language:** Java
- **Platform:** Android (minSdk 26, targetSdk 35)
- **ML:** TensorFlow Lite, Google ML Kit Entity Extraction
- **Database:** Room (SQLite)
- **PDF extraction:** PdfBox Android
- **DOCX/PPTX extraction:** Apache POI
- **Charts:** MPAndroidChart
- **UI:** Material 3, Bricolage Grotesque font

---

## Project Structure

```
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
│   └── settings/    SettingsActivity, FolderAdapter
└── util/
    ├── PrefsManager.java
    └── FileUtils.java
```

---

## Setup

### Prerequisites

- Android Studio Hedgehog or later
- Android device or emulator running API 26+
- Internet connection on first launch for ML Kit entity model download (~5MB)

### Installation

1. Clone the repository.
2. Open the project in Android Studio.
3. Download the MiniLM TFLite model and place it in `app/src/main/assets/all_minilm_l6_v2.tflite`:

```bash
python3 -c "
from huggingface_hub import hf_hub_download
path = hf_hub_download(
    repo_id='Madhur-Prakash-Mangal/all-MiniLM-L6-v2-tflite',
    filename='all-MiniLM-L6-v2.tflite'
)
print(path)
"
cp <printed_path> app/src/main/assets/all_minilm_l6_v2.tflite
```

4. Sync Gradle and build the project.

---

## How It Works

### Indexing Pipeline

When a folder is added, Ekko runs each document through an 8-step pipeline:

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

---

## Known Limitations

- Indexing large documents is slow on low-end devices due to per-sentence TFLite inference. Sentence count is capped at 40 per document for performance.
- The tokenizer in `EmbeddingEngine` uses a hash-based approximation rather than the full WordPiece vocabulary, which reduces embedding quality slightly compared to the reference implementation.
- Slide-based PDFs produce fragmented text which may result in lower quality summaries.
- ML Kit entity extraction requires an internet connection on first use to download the language model.

---

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
