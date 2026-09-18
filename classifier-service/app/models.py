"""The contract. Pydantic is the boundary, not a formality.

Spike 5 fed this service's shape four things a model actually does wrong — a category
that does not exist, a confidence of 1.7, prose where a field should be, and a missing
field — and all four have to be stopped BEFORE anything is returned to the caller.

Validating here does not make the Java side's checks redundant. The two are independent
on purpose: this process can be redeployed, misconfigured or replaced by a different
vendor's service, and the guarantee the flow depends on must not be one that leaves with
it. See ClassifierGateway for the other half.
"""

from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field, field_validator


class Sentiment(str, Enum):
    """Closed set. An unrecognised value is a bug, not a new category."""

    NEUTRAL = "NEUTRAL"
    NEGATIVE = "NEGATIVE"
    ABUSIVE = "ABUSIVE"


class ClassifyRequest(BaseModel):
    """What the partner typed, and where they are.

    NO IDENTIFIERS. No spId, no order id, no ticket id, and above all no database
    credentials — this service is given a sentence and a concern code and nothing else.
    That is what keeps it a pure function: it can be logged, replayed, load-tested and
    swapped for another vendor without a privacy review each time.
    """

    text: str = Field(min_length=1, max_length=2000)
    concern: Optional[str] = Field(default=None, max_length=64)


class Classification(BaseModel):
    """What the model said, after it has been checked.

    `confidence` is BOUNDED HERE BUT NEVER THRESHOLDED HERE. The 0.7 floor lives in the
    decision tables so a business owner can tune it without a deployment; a service that
    decided what counts as confident would quietly take that control back.
    """

    category: Optional[str] = None
    confidence: float = Field(ge=0.0, le=1.0)
    extracted_reason: Optional[str] = Field(default=None, max_length=400)
    sentiment: Sentiment = Sentiment.NEUTRAL
    abuse_flag: bool = False
    risk_flag: bool = False

    @field_validator("category")
    @classmethod
    def blank_is_none(cls, value: Optional[str]) -> Optional[str]:
        """An empty string is not a category. It is a model that found nothing and said
        so badly, and letting it through would produce a reroute to "" downstream."""
        if value is None or not value.strip():
            return None
        return value.strip()

    @classmethod
    def no_match(cls) -> "Classification":
        """The only value this service ever falls back to: an explicit no, never a null.

        Every failure path returns THIS — a timeout against the model, a response that
        will not parse, an unknown category. One value means the caller has one
        behaviour to reason about, and it is the safe one.
        """
        return cls(category=None, confidence=0.0)
