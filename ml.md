# ML In Ekko

This file explains the machine learning and ranking logic used in Ekko in simple terms, with examples taken from how this project actually works.

## Big Picture

Ekko is not one single ML model. It is a pipeline:

1. Read document text from PDF, DOCX, PPTX, or TXT.
2. Clean the text so noisy formatting does not confuse later steps.
3. Turn text into vectors using a sentence embedding model.
4. Use those vectors for classification, search, retrieval, and chunk ranking.
5. Extract useful structure like keywords and entities.
6. Optionally send selected chunks to the backend for grounded answer generation.

The important thing is that most intelligence happens on-device first. The backend is mainly used for final answer generation and summary generation.

## 1. Sentence Embeddings

Implementation:
- [EmbeddingEngine.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ml/EmbeddingEngine.java)

What it does:
- Converts text into a 384-dimensional numeric vector.
- Texts with similar meaning should end up with similar vectors.

Model used:
- `all-MiniLM-L6-v2` in TensorFlow Lite form.

Why it matters:
- Ekko uses the same embedding idea in several places:
- document search
- chunk retrieval for Q and A
- category classification
- extractive summarization

Simple example:
- Query: `deployment checklist`
- Document A: `Steps for deploying a backend service safely`
- Document B: `Symptoms and treatment of viral fever`

Even if Document A does not contain the exact phrase `deployment checklist`, its embedding should still be much closer to the query than Document B.

How this project uses it:
- Search query embeddings are compared to document embeddings.
- Chunk embeddings are compared to question embeddings.
- Category seed texts are embedded and averaged into centroids.
- Sentences are embedded for summarization.

Important implementation detail:
- The tokenizer is simplified compared to the original reference model. That makes the app easier to run on-device, but it may reduce semantic quality slightly in edge cases.

## 2. Cosine Similarity

Implementation:
- [EmbeddingEngine.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ml/EmbeddingEngine.java)
- [DocumentRepository.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/data/repository/DocumentRepository.java)
- [RagRepository.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/data/repository/RagRepository.java)

What it does:
- Measures how close two vectors point in the same direction.
- Score is usually between `-1` and `1`.
- Higher means more semantically similar.

Simple example:
- Query embedding compared with:
- `cloud architecture notes` -> high score
- `hospital discharge summary` -> low score

Where Ekko uses it:

### Document search

Ekko computes a final search score like this:

- `0.55 * embeddingScore`
- `0.30 * keywordScore`
- `0.10 * summaryScore`
- `0.05 * filenameScore`

This means semantic similarity is the main signal, but plain text overlap still helps.

Project example:
- Query: `database normalization`
- A file named `db_unit4_notes.pdf` may not literally say `normalization` in the filename.
- But if its text strongly discusses schema design, the embedding score can still rank it high.

### Chunk retrieval for Q and A

For Q and A, Ekko does not send a whole document to the backend. It first:

1. Splits documents into chunks.
2. Embeds each chunk.
3. Compares the question embedding against chunk embeddings.
4. Picks the top chunks.

This is what keeps answers grounded in the indexed content.

## 3. Category Classification With Centroids

Implementation:
- [DocumentClassifier.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ml/DocumentClassifier.java)

Categories:
- Technical
- Research
- Legal
- Medical
- General

What a centroid means here:
- For each category, the app stores a few short seed sentences.
- It embeds those seed sentences.
- It averages them into one representative vector for the category.

Then classification works like this:

1. Take a representative excerpt from the document.
2. Embed it.
3. Compare that embedding to each category centroid.
4. Pick the category with the highest similarity.
5. If the top score is not clearly better than the second score, fall back to keyword rules.

Why the fallback exists:
- Small or messy documents can produce unstable embeddings.
- Some files are too short or too noisy.
- Keyword rules give a safer answer in low-confidence situations.

Project example:
- A document containing words like `jurisdiction`, `liability`, and `arbitration` will probably be labeled `Legal`.
- If the embedding result is ambiguous, the keyword fallback can still push it toward `Legal`.

Why the excerpt is used:
- The classifier does not always embed the full text.
- It takes the beginning and a middle section.
- This reduces compute cost and still captures the document topic reasonably well.

## 4. Keyword Extraction

Implementation:
- [TextPreprocessor.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/processing/TextPreprocessor.java)

What it does:
- This is not a neural model.
- It is a frequency-based heuristic.

How it works:

1. Lowercase the text.
2. Remove punctuation noise.
3. Split into tokens.
4. Remove stopwords and low-value academic noise.
5. Ignore very short tokens and many numeric patterns.
6. Count frequency.
7. Keep the most frequent meaningful terms.

Project example:
- If a document repeatedly uses `embedding`, `classifier`, `retrieval`, and `cosine`, those may become stored keywords.
- Words like `the`, `section`, `example`, `question`, and `page` are filtered out.

Why this matters:
- Keywords improve the detail screen.
- They also help the search ranking formula as a secondary signal.

## 5. Entity Extraction

Implementation:
- [EntityExtractorHelper.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ml/EntityExtractorHelper.java)

Model used:
- Google ML Kit Entity Extraction.

What it extracts:
- dates
- addresses
- phone numbers
- email addresses
- URLs
- money amounts
- flight numbers

How it works in this project:

1. The app downloads the ML Kit model if needed.
2. It runs extraction on a shortened excerpt, capped at 5000 characters.
3. It keeps only the entity types that matter for document intelligence.
4. It formats them into readable labels like `Date: 12 May 2025`.

Project example:
- A legal document might produce:
- `Date: 12 March 2026`
- `Amount: $25,000`
- `Email: legal@example.com`

Why the excerpt is capped:
- Entity extraction on very large text is slower and more memory-heavy.
- A representative prefix usually captures many useful entities already.

## 6. Extractive Summarization With MMR

Implementation:
- [TextSummarizer.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/ml/TextSummarizer.java)

Important note:
- The class is implemented in the app.
- The current detail screen summary flow mainly uses the backend summary endpoint from indexed text.
- But the summarizer class shows the local extractive summarization algorithm the project uses conceptually.

What MMR means:
- MMR stands for Maximal Marginal Relevance.
- It tries to pick sentences that are both:
- relevant to the main topic
- not repetitive with each other

Why this is useful:
- A naive summary often picks three very similar sentences.
- MMR tries to keep the summary informative and less redundant.

How Ekko does it:

1. Split the document into candidate sentences.
2. Reject noisy lines such as:
- page numbers
- short headings
- bullets
- very short fragments
3. Embed each remaining sentence.
4. Compute a centroid vector for all candidate sentence embeddings.
5. Repeatedly select the sentence with the best balance of:
- relevance to the centroid
- low redundancy against already selected sentences
6. Return about 3 sentences.

Project example:
- Imagine a study note has 30 valid sentences about operating systems.
- The centroid represents the overall topic.
- A sentence about process scheduling may score high for relevance.
- Another sentence about memory management may also score high and add diversity.
- A third sentence repeating process scheduling details may be penalized for redundancy.

That is the core MMR idea.

## 7. Chunking For Retrieval-Augmented Generation

Implementation:
- [ChunkUtils.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/processing/ChunkUtils.java)
- [DocumentIndexer.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/processing/DocumentIndexer.java)
- [RagRepository.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/data/repository/RagRepository.java)

What it does:
- Splits long document text into overlapping windows of words.

Current settings:
- chunk size: `100` words
- overlap: `20` words

Why overlap is useful:
- Important meaning can cross a chunk boundary.
- Overlap reduces the chance of losing context.

Project example:
- If a sentence starts at the end of chunk 1 and finishes at the start of chunk 2, the overlap helps both chunks keep enough context to remain useful in retrieval.

How retrieval works:

1. User asks a question.
2. Ekko embeds the question.
3. Ekko compares that vector against chunk embeddings.
4. It sorts chunks by cosine similarity.
5. It chooses the strongest chunks from the strongest documents.
6. Only those chunks are sent to the backend.

This is why the backend answer can stay focused on the indexed material.

## 8. Relevance Gating Before Calling The Backend

Implementation:
- [RagRepository.java](/home/izumii/coding/java-applications/ekko-semantic/ekko-app/app/src/main/java/com/semantic/ekko/data/repository/RagRepository.java)

What it does:
- For global Q and A, Ekko does not always call the backend.
- It first checks whether the best retrieved chunks are relevant enough.

Why this matters:
- Without this step, the backend could hallucinate answers for questions unrelated to the indexed files.

How it works:
- Ekko scores top chunks and computes a best document score.
- If the score is below `MIN_RELEVANCE = 0.55`, it refuses to answer and asks the user to rephrase or ask about something covered in the documents.

Project example:
- Indexed files: machine learning notes, legal contracts
- User asks: `What is the best pizza place in Bangalore?`
- Retrieval scores will be weak.
- Ekko blocks the backend call instead of inventing an answer.

## 9. End-To-End Example From This Project

Suppose the user adds a file called `ml_exam_notes.pdf`.

During indexing:

1. Text is extracted from the PDF.
2. The text is cleaned for display and for ML.
3. `DocumentClassifier` labels it, maybe as `Technical`.
4. `EmbeddingEngine` creates a document embedding.
5. `TextPreprocessor` extracts keywords like `embedding`, `classification`, `retrieval`.
6. `EntityExtractorHelper` might extract dates or URLs if present.
7. `ChunkUtils` splits the text into overlapping chunks.
8. Each chunk gets its own embedding and is stored.

Later, the user asks:
- `@ml_exam_notes.pdf: explain cosine similarity`

Then:

1. The query is embedded.
2. The most relevant chunks from that document are found using cosine similarity.
3. Those chunks are sent to the backend.
4. The backend generates a grounded answer from only those chunks.

That is the main ML story of Ekko.

## 10. What Is ML Here And What Is Not

ML or model-driven parts:
- sentence embeddings
- centroid classification
- entity extraction
- MMR summarization because it depends on embeddings
- retrieval scoring for Q and A

Heuristic or rule-based parts:
- keyword extraction
- fallback category rules
- chunk windowing
- search score blending
- noise filtering in sentence selection

This mix is intentional. The project uses ML where semantic understanding matters, and rules where simple logic is faster, cheaper, and easier to control on-device.

## 11. Practical Tradeoffs In This Codebase

Strengths:
- Most intelligence works offline.
- Search and retrieval are fast enough for phone use.
- Semantic matching is stronger than plain keyword search.
- Retrieval reduces hallucination risk before backend generation.

Tradeoffs:
- The simplified tokenizer may reduce embedding quality slightly.
- Very large files are intentionally trimmed or sampled in some steps to save time and memory.
- Classification uses small seed sets, so it is lightweight but not as robust as a trained supervised classifier.
- Entity extraction depends on ML Kit model availability.

## 12. If You Want To Extend The ML Later

Good next upgrades would be:
- better tokenizer compatibility for the embedding model
- stronger category seeds or a small trained classifier
- local abstractive summarization flow wired into the current UI
- chunk reranking before backend calls
- language-aware processing beyond English

For now, the current system is a practical on-device ML stack built around embeddings, similarity scoring, lightweight heuristics, and grounded retrieval.
