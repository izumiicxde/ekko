import logging

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ekko-rag")

app = FastAPI(title="Ekko RAG Backend")

OLLAMA_URL = "http://localhost:11434/api/generate"
OLLAMA_MODEL = "gemma2:2b"


# =========================
# REQUEST / RESPONSE MODELS
# =========================


class RAGRequest(BaseModel):
    question: str
    chunks: list[str]  # top-k document chunks from Android
    document_name: str = ""  # optional, used in prompt for context


class RAGResponse(BaseModel):
    answer: str
    chunks_used: int


# =========================
# PROMPT BUILDER
# =========================


def build_prompt(question: str, chunks: list[str], document_name: str) -> str:
    context = "\n\n".join(
        f"[Chunk {i + 1}]\n{chunk.strip()}"
        for i, chunk in enumerate(chunks)
        if chunk.strip()
    )

    doc_ref = f' from "{document_name}"' if document_name else ""

    return (
        f"You are a document assistant. Answer the user's question using only "
        f"the context provided below{doc_ref}. "
        f"If the context does not contain enough information to answer, say so clearly. "
        f"Do not make up information.\n\n"
        f"Context:\n{context}\n\n"
        f"Question: {question}\n\n"
        f"Answer:"
    )


# =========================
# ROUTES
# =========================


@app.get("/health")
async def health():
    return {"status": "ok", "model": OLLAMA_MODEL}


@app.post("/rag", response_model=RAGResponse)
async def rag(request: RAGRequest):
    if not request.question.strip():
        raise HTTPException(status_code=400, detail="Question cannot be empty.")

    if not request.chunks:
        raise HTTPException(status_code=400, detail="No chunks provided.")

    prompt = build_prompt(request.question, request.chunks, request.document_name)
    logger.info(
        f"RAG request | question='{request.question}' | chunks={len(request.chunks)}"
    )

    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            response = await client.post(
                OLLAMA_URL,
                json={
                    "model": OLLAMA_MODEL,
                    "prompt": prompt,
                    "stream": False,
                },
            )
            response.raise_for_status()
            data = response.json()
            answer = data.get("response", "").strip()

    except httpx.TimeoutException:
        logger.error("Ollama request timed out.")
        raise HTTPException(status_code=504, detail="Model took too long to respond.")
    except httpx.HTTPStatusError as e:
        logger.error(f"Ollama HTTP error: {e}")
        raise HTTPException(status_code=502, detail="Failed to reach language model.")
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
        raise HTTPException(status_code=500, detail="Internal server error.")

    return RAGResponse(answer=answer, chunks_used=len(request.chunks))
