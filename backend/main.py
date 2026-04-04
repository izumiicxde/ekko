import json
import logging
import os
import re
from typing import AsyncIterator

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ekko-rag")

app = FastAPI(title="Ekko RAG Backend")

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://localhost:11434/api/generate")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "llama3.2:1b")
OLLAMA_REQUEST_TIMEOUT = float(os.getenv("OLLAMA_REQUEST_TIMEOUT", "600"))
OLLAMA_RELEVANCE_TIMEOUT = float(os.getenv("OLLAMA_RELEVANCE_TIMEOUT", "15"))

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

NOT_FOUND = "I could not find this in the provided content."


class RAGRequest(BaseModel):
    question: str
    chunks: list[str]
    document_name: str = ""


class RAGResponse(BaseModel):
    answer: str
    chunks_used: int


class SummaryRequest(BaseModel):
    context: str
    document_name: str = ""


class SummaryResponse(BaseModel):
    summary: str


def extract_keywords(text: str) -> list[str]:
    words = re.findall(r"[a-zA-Z]{3,}", text.lower())
    return [w for w in words if w not in STOPWORDS]


def fuzzy_match(keyword: str, text: str) -> bool:
    if keyword in text:
        return True
    words = re.findall(r"[a-zA-Z]{3,}", text)
    for word in words:
        if abs(len(keyword) - len(word)) > 2:
            continue
        differences = sum(1 for a, b in zip(keyword, word) if a != b)
        differences += abs(len(keyword) - len(word))
        if differences <= 1:
            return True
    return False


def has_keyword_match(question: str, chunks: list[str]) -> bool:
    keywords = extract_keywords(question)
    if not keywords:
        return True
    combined = " ".join(chunks).lower()
    return any(fuzzy_match(keyword, combined) for keyword in keywords)


async def is_relevant(question: str, context: str, client: httpx.AsyncClient) -> bool:
    check_prompt = (
        f"Does the following text contain information relevant to answering "
        f'the question: "{question}"?\n\n'
        f"TEXT:\n{context[:1000]}\n\n"
        f"Reply with only YES or NO."
    )
    try:
        response = await client.post(
            OLLAMA_URL,
            json={
                "model": OLLAMA_MODEL,
                "prompt": check_prompt,
                "stream": False,
                "options": {"temperature": 0, "num_predict": 5},
            },
            timeout=OLLAMA_RELEVANCE_TIMEOUT,
        )
        response.raise_for_status()
        answer = response.json().get("response", "").strip().upper()
        logger.info("Relevance check for '%s': %s", question, answer)
        return "YES" in answer
    except Exception as exc:
        logger.warning("Relevance check failed: %s, allowing through", exc)
        return True


def normalize_chunks(chunks: list[str]) -> list[str]:
    return [chunk.strip() for chunk in chunks if chunk and chunk.strip()]


def build_prompt(question: str, chunks: list[str], document_name: str) -> str:
    context = "\n\n".join(chunks)
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


def build_summary_prompt(context: str, document_name: str) -> str:
    doc_ref = f' from "{document_name}"' if document_name else ""
    return (
        f"Below is extracted text{doc_ref}.\n\n"
        f"EXTRACTED TEXT:\n{context.strip()}\n\n"
        "Write a concise, accurate summary using only the provided text.\n"
        "Requirements:\n"
        "- Keep it brief but informative\n"
        "- Prefer 1 short paragraph followed by 3-5 bullet points when useful\n"
        "- Do not mention missing information unless the text is genuinely too short\n"
        "- Do not add outside facts or assumptions\n\n"
        "SUMMARY:"
    )


async def prepare_rag_request(
    request: RAGRequest,
    client: httpx.AsyncClient,
) -> tuple[bool, list[str], str]:
    question = request.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="Question cannot be empty.")

    chunks = normalize_chunks(request.chunks)
    if not chunks:
        raise HTTPException(status_code=400, detail="No chunks provided.")

    if not has_keyword_match(question, chunks):
        logger.info("Keyword rejected: '%s'", question)
        return False, chunks, ""

    context = "\n\n".join(chunks)
    relevant = await is_relevant(question, context, client)
    if not relevant:
        logger.info("Model rejected as irrelevant: '%s'", question)
        return False, chunks, ""

    prompt = build_prompt(question, chunks, request.document_name)
    return True, chunks, prompt


def not_found_response() -> RAGResponse:
    return RAGResponse(answer=NOT_FOUND, chunks_used=0)


async def not_found_stream() -> AsyncIterator[str]:
    yield json.dumps({"token": NOT_FOUND}) + "\n"
    yield json.dumps({"done": True}) + "\n"


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "model": OLLAMA_MODEL,
        "ollama_url": OLLAMA_URL,
    }


@app.post("/rag")
async def rag(request: RAGRequest):
    async with httpx.AsyncClient(timeout=OLLAMA_REQUEST_TIMEOUT) as client:
        allowed, chunks, prompt = await prepare_rag_request(request, client)
        if not allowed:
            return not_found_response()

        logger.info(
            "RAG request | question='%s' | chunks=%s",
            request.question,
            len(chunks),
        )
        try:
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
            answer = response.json().get("response", "").strip()
        except httpx.TimeoutException:
            raise HTTPException(
                status_code=504, detail="Model took too long to respond."
            )
        except Exception as exc:
            logger.error("Unexpected error: %s", exc)
            raise HTTPException(status_code=500, detail="Internal server error.")

    return RAGResponse(answer=answer, chunks_used=len(chunks))


@app.post("/summary")
async def summary(request: SummaryRequest):
    context = request.context.strip()
    if not context:
        raise HTTPException(status_code=400, detail="Context cannot be empty.")

    prompt = build_summary_prompt(context, request.document_name)
    logger.info(
        "Summary request | document='%s' | chars=%s",
        request.document_name,
        len(context),
    )

    try:
        async with httpx.AsyncClient(timeout=OLLAMA_REQUEST_TIMEOUT) as client:
            response = await client.post(
                OLLAMA_URL,
                json={
                    "model": OLLAMA_MODEL,
                    "prompt": prompt,
                    "stream": False,
                    "options": {
                        **OLLAMA_OPTIONS,
                        "temperature": 0.2,
                    },
                },
            )
            response.raise_for_status()
            summary_text = response.json().get("response", "").strip()
    except httpx.TimeoutException:
        raise HTTPException(
            status_code=504, detail="Model took too long to respond."
        )
    except Exception as exc:
        logger.error("Summary generation failed: %s", exc)
        raise HTTPException(status_code=500, detail="Internal server error.")

    if not summary_text:
        raise HTTPException(status_code=500, detail="Model returned an empty summary.")

    return SummaryResponse(summary=summary_text)


@app.post("/rag/stream")
async def rag_stream(request: RAGRequest):
    async with httpx.AsyncClient(timeout=OLLAMA_REQUEST_TIMEOUT) as client:
        allowed, chunks, prompt = await prepare_rag_request(request, client)
        if not allowed:
            return StreamingResponse(not_found_stream(), media_type="application/x-ndjson")

    logger.info(
        "RAG stream | question='%s' | chunks=%s",
        request.question,
        len(chunks),
    )

    async def token_generator() -> AsyncIterator[str]:
        try:
            async with httpx.AsyncClient(timeout=OLLAMA_REQUEST_TIMEOUT) as client:
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
                    response.raise_for_status()
                    async for line in response.aiter_lines():
                        if not line.strip():
                            continue
                        try:
                            data = json.loads(line)
                        except json.JSONDecodeError:
                            continue

                        token = data.get("response", "")
                        done = data.get("done", False)
                        if token:
                            yield json.dumps({"token": token}) + "\n"
                        if done:
                            yield json.dumps({"done": True}) + "\n"
                            break
        except Exception as exc:
            logger.error("Stream error: %s", exc)
            yield json.dumps({"error": str(exc)}) + "\n"

    return StreamingResponse(token_generator(), media_type="application/x-ndjson")
