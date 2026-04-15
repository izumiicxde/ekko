# Core Models in Ekko

This file lists the main ML and ranking methods used in Ekko, what each one does in simple terms, and what it is closest to at its core.

## 1. Transformer embeddings from MiniLM

- What it is used for in general: Used to turn text into semantic vectors so machines can compare meaning instead of only matching exact words.
- What it does: Converts text like a query, document, or chunk into a dense numeric vector that captures meaning.
- At core: A `Transformer-based sentence embedding model`.
- Closest classical framing: Not really `K-NN` or `K-means` by itself. It is the feature-extraction step that produces vectors used by later algorithms.

## 2. Cosine similarity

- What it is used for in general: Used to measure how close or semantically similar two vectors are in tasks like search, recommendation, and clustering.
- What it does: Measures how similar two embedding vectors are by comparing their direction.
- At core: A `vector similarity metric`.
- Closest classical framing: Not `K-NN` or `K-means` itself, but it is the distance/similarity function used by nearest-neighbor style retrieval.

## 3. Nearest-neighbor style ranking

- What it is used for in general: Used in search and recommendation systems to find the most similar stored items for a new query or input.
- What it does: Ranks documents or chunks by finding which embeddings are closest to the query embedding.
- At core: `Nearest-neighbor retrieval` in embedding space.
- Closest classical framing: Very close to `K-NN`, but used for ranking and retrieval rather than a trained classifier.

## 4. Centroid-based classification

- What it is used for in general: Used to assign items to the closest category when each class can be represented by a typical average example.
- What it does: Creates one average embedding per category and assigns a document to the category whose centroid is most similar.
- At core: A `nearest-centroid classifier` or `prototype-based classifier`.
- Closest classical framing: Closer to `nearest centroid` than `K-means`. It uses class centroids directly instead of discovering clusters from unlabeled data.

## 5. MMR-based extractive summarization

- What it is used for in general: Used in summarization and retrieval to choose results that are both relevant and diverse, so the output does not become repetitive.
- What it does: Picks summary sentences that are important to the document while avoiding sentences that repeat the same idea.
- At core: `MMR` (`Maximal Marginal Relevance`) ranking over sentence embeddings.
- Closest classical framing: A greedy ranking and selection algorithm, not `K-NN` or `K-means`.
