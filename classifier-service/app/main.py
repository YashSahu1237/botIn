"""PLAN STEP 82 — the classifier service. Two endpoints, one job.

WHAT THIS IS FOR. Closed-set classification of a short Hinglish string, in two shapes:
which concern is this (`/classify-intent`), and is this really the concern the partner is
already in (`/classify`). Nothing here generates prose, and nothing here decides
anything — the tier, the confidence floor and the routing all live on the Java side,
where they are configuration rather than code.

Naming the task that precisely is the point. It is a narrow, cheap purchase, and keeping
it narrow is what stops the conversation drifting toward a general-purpose assistant.

NO MODEL IS WIRED IN YET. The LLM track is blocked on procurement, so `classify_text` is
a deterministic placeholder and says so at startup. Everything around it — the contract,
the validation, the failure shape — is real, and it is what the Java client is built
against. When a model arrives, one function changes.

RUN IT:
    pip install -r requirements.txt
    uvicorn app.main:app --port 8099
Then point the service at it:
    export CLASSIFIER_URL=http://localhost:8099
"""

import logging

from fastapi import FastAPI

from .models import Classification, ClassifyRequest

log = logging.getLogger(__name__)

app = FastAPI(title="BOTIn classifier", version="0.1.0")


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "model": "none — deterministic placeholder"}


@app.post("/classify-intent", response_model=Classification)
def classify_intent(request: ClassifyRequest) -> Classification:
    """The partner typed something before picking anything. Which concern is it?"""
    return classify_text(request.text)


@app.post("/classify", response_model=Classification)
def classify(request: ClassifyRequest) -> Classification:
    """The partner is inside a concern and typed a reason. Is it really this concern?

    Returning the concern they are ALREADY in is treated as no match. Sending someone
    back into the flow they are standing in reads as the bot ignoring them, and it is a
    loop as far as the caller is concerned.
    """
    result = classify_text(request.text)
    if result.category and request.concern and result.category == request.concern:
        return Classification(
            category=None,
            confidence=result.confidence,
            sentiment=result.sentiment,
            abuse_flag=result.abuse_flag,
            risk_flag=result.risk_flag,
        )
    return result


def classify_text(text: str) -> Classification:
    """PLACEHOLDER. Replace this one function with a real model call.

    It must keep the two properties the flow depends on, and nothing else matters:
      * it never raises — every failure returns Classification.no_match()
      * it never returns a category outside the live taxonomy

    The Java side independently rejects an unknown category, so a model that starts
    hallucinating cannot route a partner anywhere. That is deliberate redundancy: this
    check protects the service's own callers, that one protects the partner.
    """
    lowered = text.lower()

    risk = any(word in lowered for word in ("accident", "hospital", "police"))

    keywords = {
        "mpin": "FORGET_MPIN",
        "recharge": "RECHARGE_DEBIT_NO_CREDIT",
        "transport": "TRANSPORT_NOT_RECEIVED",
        "delivery": "PROD_DELIVERY_DELAY",
    }

    for keyword, category in keywords.items():
        if keyword in lowered:
            return Classification(
                category=category,
                confidence=0.95,
                extracted_reason=f"matched '{keyword}'",
                sentiment="NEGATIVE" if risk else "NEUTRAL",
                risk_flag=risk,
            )

    if risk:
        return Classification(category=None, confidence=0.0, sentiment="NEGATIVE", risk_flag=True)

    return Classification.no_match()
