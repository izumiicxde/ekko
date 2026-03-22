import json
import logging
import traceback

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ekko-rag")

app = FastAPI(title="Ekko RAG Backend")

OLLAMA_URL = "http://localhost:11434/api/generate"
OLLAMA_URL_STREAM = "http://localhost:11434/api/generate"
# OLLAMA_MODEL = "gemma2:2b"
OLLAMA_MODEL = "qwen2.5:0.5b"

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
            timeout = httpx.Timeout(connect=30.0, read=None, write=30.0, pool=30.0)
            async with httpx.AsyncClient(timeout=timeout) as client:
                async with client.stream(
                    "POST",
                    OLLAMA_URL_STREAM,
                    json={"model": OLLAMA_MODEL, "prompt": prompt, "stream": True},
                ) as response:
                    response.raise_for_status()
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
                            logger.warning(f"Skipping non-JSON stream line: {line!r}")
                            continue
        except httpx.TimeoutException as e:
            logger.exception("Stream timeout while waiting for Ollama output.")
            yield (
                json.dumps(
                    {"error": "Model stream timed out before producing a response."}
                )
                + "\n"
            )
        except httpx.HTTPStatusError as e:
            status_code = e.response.status_code if e.response is not None else 502
            body = ""
            if e.response is not None:
                try:
                    body = e.response.text
                except Exception:
                    body = "<unavailable>"
            logger.error(
                "Ollama stream HTTP error status=%s body=%r",
                status_code,
                body,
            )
            yield (
                json.dumps(
                    {"error": f"Language model stream failed with HTTP {status_code}."}
                )
                + "\n"
            )
        except Exception as e:
            logger.error(
                "Stream error type=%s message=%r\n%s",
                type(e).__name__,
                str(e),
                traceback.format_exc(),
            )
            yield json.dumps({"error": "Internal streaming error."}) + "\n"

    return StreamingResponse(token_generator(), media_type="application/x-ndjson")
