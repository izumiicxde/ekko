# ML and Ranking in Ekko

This file describes the actual ML-adjacent logic in the project. Ekko is not one large model. It is a pipeline that mixes local embeddings, heuristics, retrieval logic, and optional backend generation.

## Core Principle

Most intelligence happens on device first:

1. extract text
2. normalize it
3. embed it
4. rank documents or chunks
5. only then call the backend if needed

The backend is mainly for answer generation and summary generation, not for indexing or primary retrieval.

## Embeddings

Main implementation:

- [EmbeddingEngine.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ml/EmbeddingEngine.java)

Ekko uses a local MiniLM-based embedding model in TFLite form. Text is converted into 384-dimensional vectors.

Those vectors are used for:

- document search
- chunk retrieval for Q&A
- category classification
- graph relationships
- summarization support

## Search Ranking

Main implementation:

- [DocumentRepository.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/data/repository/DocumentRepository.java)
- [SearchTextMatcher.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/data/repository/SearchTextMatcher.java)

Search is hybrid, not purely semantic.

The app combines:

- full-document embedding similarity
- metadata coverage
- summary coverage
- raw-text coverage
- filename/path coverage
- phrase matches
- chunk-level signals for shortlisted candidates

Important recent behavior:

- the repository does not need to score every chunk for every document up front
- candidate documents are narrowed first
- chunk signals are then computed where they are actually useful

That makes search and retrieval noticeably cheaper as the library grows.

## Q&A Retrieval

Main implementation:

- [RagRepository.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/data/repository/RagRepository.java)

For global Q&A:

1. embed the question
2. shortlist likely documents
3. score chunks inside that shortlist
4. send selected chunks to the backend

For file-scoped chat:

- the app can send the selected document context directly

This keeps the answer path grounded in indexed content instead of shipping the whole library to the backend.

## Category Classification

Main implementation:

- [DocumentClassifier.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ml/DocumentClassifier.java)

Ekko uses centroid-based classification.

High level flow:

1. define seed text per category
2. embed those seeds
3. average them into centroids
4. compare a document excerpt against each centroid
5. fall back to heuristic rules if confidence is weak

Categories currently include:

- Technical
- Research
- Legal
- Medical
- General

## Summaries

Relevant implementations:

- local summarizer: [TextSummarizer.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ml/TextSummarizer.java)
- backend summary endpoint: [main.py](/home/izumii/coding/java-applications/ekko-semantic/backend/main.py)

The project contains a local extractive summarizer, but current UI flows also use the backend `/summary` route for concise document summaries from indexed text.

Conceptually:

- the local summarizer uses sentence embeddings plus MMR-style selection
- the backend summary path uses a grounded prompt over extracted text

## Keywords and Entities

Relevant implementations:

- keywords: [TextPreprocessor.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/processing/TextPreprocessor.java)
- entities: [EntityExtractorHelper.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ml/EntityExtractorHelper.java)

Keyword extraction is heuristic and frequency-based.

Entity extraction uses ML Kit and focuses on structured items like:

- dates
- phone numbers
- email addresses
- URLs
- money amounts

## Backend Prompting and Grounding

Relevant implementation:

- [backend/main.py](/home/izumii/coding/java-applications/ekko-semantic/backend/main.py)

The backend currently has two important modes:

- strict grounded mode for normal document Q&A
- file-context general mode for file-scoped chat

There is also answer-style handling:

- short/brief answer requests are detected
- generation limits are reduced for those requests
- prompts no longer force long sectioned markdown for every answer

## Current Limitations

- on-device indexing is still expensive for large libraries
- embedding quality depends on the local tokenizer/model approximation
- semantic search is better than pure keyword match, but still limited by extraction quality
- summary and answer quality depend on chunk selection and local Ollama model behavior
