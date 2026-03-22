import json
import logging

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ekko-rag")

app = FastAPI(title="Ekko RAG Backend")

OLLAMA_URL = "http://localhost:11434/api/generate"
OLLAMA_URL_STREAM = "http://localhost:11434/api/generate"
OLLAMA_MODEL = "gemma2:2b"


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


@app.post("/rag")
async def rag(request: RAGRequest):
    """Non-streaming endpoint kept for compatibility."""
    if not request.question.strip():
        raise HTTPException(status_code=400, detail="Question cannot be empty.")
    if not request.chunks:
        raise HTTPException(status_code=400, detail="No chunks provided.")

    prompt = build_prompt(request.question, request.chunks, request.document_name)
    logger.info(
        f"RAG request | question='{request.question}' | chunks={len(request.chunks)}"
    )

    try:
        async with httpx.AsyncClient(timeout=120.0) as client:
            response = await client.post(
                OLLAMA_URL,
                json={"model": OLLAMA_MODEL, "prompt": prompt, "stream": False},
            )
            response.raise_for_status()
            data = response.json()
            answer = data.get("response", "").strip()
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="Model took too long to respond.")
    except httpx.HTTPStatusError as e:
        raise HTTPException(status_code=502, detail="Failed to reach language model.")
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
        raise HTTPException(status_code=500, detail="Internal server error.")

    return RAGResponse(answer=answer, chunks_used=len(request.chunks))


@app.post("/rag/stream")
async def rag_stream(request: RAGRequest):
    """
    Streaming endpoint. Returns a stream of Server-Sent Events.
    Each event is a JSON object with a single field:
      {"token": "..."} for each token as it is generated
      {"done": true}   when generation is complete
    """
    if not request.question.strip():
        raise HTTPException(status_code=400, detail="Question cannot be empty.")
    if not request.chunks:
        raise HTTPException(status_code=400, detail="No chunks provided.")

    prompt = build_prompt(request.question, request.chunks, request.document_name)
    logger.info(
        f"RAG stream | question='{request.question}' | chunks={len(request.chunks)}"
    )

    async def token_generator():
        try:
            async with httpx.AsyncClient(timeout=120.0) as client:
                async with client.stream(
                    "POST",
                    OLLAMA_URL_STREAM,
                    json={"model": OLLAMA_MODEL, "prompt": prompt, "stream": True},
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
