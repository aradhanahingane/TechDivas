from app.services.groq_ai import Explanation, explain_finding


def test_missing_groq_key_keeps_ai_optional(monkeypatch):
    monkeypatch.setattr("app.services.groq_ai.settings.groq_api_key", None)
    result, status = explain_finding({"metric": "mood"})
    assert result is None
    assert status == "missing_key"


def test_explanation_schema_rejects_extra_or_overlong_content():
    valid = Explanation(headline="A changed pattern", explanation="The measure differs from its personal baseline.", uncertainty="One signal is available.")
    assert valid.headline == "A changed pattern"
    try:
        Explanation(headline="ok", explanation="short", uncertainty="unknown", invented_value=42)
    except ValueError:
        pass
    else:
        raise AssertionError("Strict explanation schema accepted invalid content")
