"""Optional evidence-grounded explanation service. Never calculate metrics here."""
import json

import httpx
from pydantic import BaseModel, ConfigDict, Field

from app.core.config import settings


class Explanation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    headline: str = Field(min_length=5, max_length=100)
    explanation: str = Field(min_length=15, max_length=600)
    action: str | None = Field(default=None, max_length=220)
    uncertainty: str = Field(min_length=5, max_length=300)


def explain_finding(finding: dict) -> tuple[Explanation | None, str]:
    if not settings.groq_api_key:
        return None, "missing_key"
    minimized = {key: finding.get(key) for key in ("metric", "category", "observed", "baseline", "deviation", "count", "confidence", "uncertainty")}
    payload = {"model": settings.groq_model, "temperature": 0,
               "messages": [{"role": "system", "content": "Explain only the supplied descriptive evidence. Do not infer diagnosis, stress probability, cause, identity, or invent values. Separate observation from possible interpretation. Keep neutral."},
                            {"role": "user", "content": json.dumps(minimized)}],
               "response_format": {"type": "json_schema", "json_schema": {"name": "rhythm_explanation", "strict": True,
                   "schema": Explanation.model_json_schema()}}}
    try:
        with httpx.Client(timeout=settings.groq_timeout_seconds) as client:
            response = client.post("https://api.groq.com/openai/v1/chat/completions",
                                   headers={"Authorization": f"Bearer {settings.groq_api_key}"}, json=payload)
            response.raise_for_status()
        content = response.json()["choices"][0]["message"]["content"]
        parsed = Explanation.model_validate_json(content)
        return parsed, "available"
    except (httpx.HTTPError, ValueError, KeyError, IndexError):
        return None, "unavailable"
