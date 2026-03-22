import json
import logging
import re

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ekko-rag")

app = FastAPI(title="Ekko RAG Backend")

OLLAMA_URL = "http://localhost:11434/api/generate"
OLLAMA_MODEL = "qwen2.5:0.5b"

OLLAMA_OPTIONS = {
    "temperature": 0,
    "num_predict": -1,
}

SYSTEM_PROMPT = (
    "You are a strict document assistant. "
    "You ONLY answer based on the text provided to you. "
    "You have NO access to external knowledge or training data. "
    "If the answer is not explicitly present in the provided text, "
    'you must respond with exactly: "I could not find this in the provided content." '
    "Never guess, infer beyond the text, or use information you were trained on. "
    "Never mention author names, professor names, college names, department names, "
    "batch years, or course codes in your answer."
)

# Common words that should not count as meaningful query terms
STOPWORDS = {
    "what",
    "is",
    "are",
    "the",
    "a",
    "an",
    "of",
    "in",
    "to",
    "and",
    "or",
    "how",
    "why",
    "when",
    "where",
    "who",
    "which",
    "do",
    "does",
    "can",
    "could",
    "would",
    "should",
    "explain",
    "describe",
    "tell",
    "me",
    "about",
    "give",
    "list",
    "define",
    "definition",
    "meaning",
    "example",
    "examples",
    "it",
    "its",
    "be",
    "was",
    "were",
    "has",
    "have",
    "had",
    "will",
    "that",
    "this",
    "these",
    "those",
    "with",
    "for",
    "from",
    "by",
    "at",
    "on",
}


# =========================
# REQUEST / RESPONSE MODELS
# =========================


class RAGRequest(BaseModel):
    question: str
    chunks: list[str]
    document_name: str = ""


class RAGResponse(BaseModel):
    answer: str
    chunks_used: int


# =========================
# KEYWORD PRESENCE CHECK
# =========================


def extract_keywords(text: str) -> list[str]:
    """Extract meaningful words from a query, excluding stopwords."""
    words = re.findall(r"[a-zA-Z]{3,}", text.lower())
    return [w for w in words if w not in STOPWORDS]


def has_keyword_match(question: str, chunks: list[str]) -> bool:
    keywords = extract_keywords(question)
    if not keywords:
        return True
    combined = " ".join(chunks).lower()
    for keyword in keywords:
        # Check if keyword or its stem (first 5 chars) appears in chunks
        if keyword in combined or (len(keyword) > 5 and keyword[:5] in combined):
            return True
    return False


# =========================
# PROMPT BUILDER
# =========================


def build_prompt(question: str, chunks: list[str], document_name: str) -> str:
    context = "\n\n".join(chunk.strip() for chunk in chunks if chunk.strip())
    doc_ref = f' from "{document_name}"' if document_name else ""

    return (
        f"Below is extracted text{doc_ref}.\n\n"
        f"EXTRACTED TEXT:\n{context}\n\n"
        f"QUESTION: {question}\n\n"
        f"Format your answer using markdown:\n"
        f"- Use **bold** for key terms\n"
        f"- Use bullet points or numbered lists for multiple items\n"
        f"- Use ## or ### headings to separate major sections\n"
        f"- Use `code blocks` only for actual code or commands\n"
        f"- Do NOT wrap your entire answer in a code block\n\n"
        f"ANSWER:"
    )


# =========================
# ROUTES
# =========================


@app.get("/health")
async def health():
    return {"status": "ok", "model": OLLAMA_MODEL}


@app.post("/rag")
async def rag(request: RAGRequest):
    if not request.question.strip():
        raise HTTPException(status_code=400, detail="Question cannot be empty.")
    if not request.chunks:
        raise HTTPException(status_code=400, detail="No chunks provided.")

    if not has_keyword_match(request.question, request.chunks):
        logger.info(f"Rejected - no keyword match for: '{request.question}'")
        return RAGResponse(
            answer="I could not find this in the provided content.", chunks_used=0
        )

    prompt = build_prompt(request.question, request.chunks, request.document_name)
    logger.info(
        f"RAG request | question='{request.question}' | chunks={len(request.chunks)}"
    )

    try:
        async with httpx.AsyncClient(timeout=120.0) as client:
            response = await client.post(
                OLLAMA_URL,
                json={
                    "model": OLLAMA_MODEL,
                    "prompt": prompt,
                    "system": SYSTEM_PROMPT,
                    "stream": False,
                    "options": OLLAMA_OPTIONS,
                },
            )
            response.raise_for_status()
            data = response.json()
            answer = data.get("response", "").strip()
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="Model took too long to respond.")
    except httpx.HTTPStatusError:
        raise HTTPException(status_code=502, detail="Failed to reach language model.")
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
        raise HTTPException(status_code=500, detail="Internal server error.")

    return RAGResponse(answer=answer, chunks_used=len(request.chunks))


@app.post("/rag/stream")
async def rag_stream(request: RAGRequest):
    if not request.question.strip():
        raise HTTPException(status_code=400, detail="Question cannot be empty.")
    if not request.chunks:
        raise HTTPException(status_code=400, detail="No chunks provided.")

    # Check keyword presence before streaming
    if not has_keyword_match(request.question, request.chunks):
        logger.info(f"Rejected stream - no keyword match for: '{request.question}'")

        async def rejected():
            yield (
                json.dumps({"token": "I could not find this in the provided content."})
                + "\n"
            )
            yield json.dumps({"done": True}) + "\n"

        return StreamingResponse(rejected(), media_type="application/x-ndjson")

    prompt = build_prompt(request.question, request.chunks, request.document_name)
    logger.info(
        f"RAG stream | question='{request.question}' | chunks={len(request.chunks)}"
    )

    async def token_generator():
        try:
            async with httpx.AsyncClient(timeout=120.0) as client:
                async with client.stream(
                    "POST",
                    OLLAMA_URL,
                    json={
                        "model": OLLAMA_MODEL,
                        "prompt": prompt,
                        "system": SYSTEM_PROMPT,
                        "stream": True,
                        "options": OLLAMA_OPTIONS,
                    },
                ) as response:
                    async for line in response.aiter_lines():
                        if not line.strip():
                            continue
                        try:
                            data = json.loads(line)
                            token = data.get("response", "")
                            done = data.get("done", False)
                            if token:
                                yield json.dumps({"token": token}) + "\n"
                            if done:
                                yield json.dumps({"done": True}) + "\n"
                                break
                        except json.JSONDecodeError:
                            continue
        except Exception as e:
            logger.error(f"Stream error: {e}")
            yield json.dumps({"error": str(e)}) + "\n"

    return StreamingResponse(token_generator(), media_type="application/x-ndjson")
