# RHYTHM API

FastAPI service with Firebase ID-token verification, user-owned records, deterministic analysis, and optional Groq explanations. Local records remain on Android unless the user enables cloud sync. AI processing requires separate consent.

## Local setup (PowerShell)

```powershell
cd backend
py -3.12 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
Copy-Item .env.example .env
$env:DATABASE_URL = "sqlite:///./rhythm.db"
# Set Firebase Admin credentials using ADC, or FIREBASE_SERVICE_ACCOUNT_JSON in the protected environment.
alembic upgrade head
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Firebase Email/Password account creation and login happen in the Android Firebase SDK. The app sends Firebase ID tokens as Bearer tokens. The backend verifies tokens with Firebase Admin; use Application Default Credentials or a protected `FIREBASE_SERVICE_ACCOUNT_JSON` value. Never put service-account keys in the Android project.

## Configuration

- `DATABASE_URL`: SQLite for local development; PostgreSQL via `postgresql+psycopg://...` for deployment. `postgres://` URLs are normalized.
- `FIREBASE_SERVICE_ACCOUNT_JSON`: optional JSON string for backend service credentials; otherwise ADC is used.
- `GROQ_API_KEY`: optional server-side key. Leave unset to use deterministic insight text only.
- `GROQ_MODEL`: defaults to `openai/gpt-oss-120b`.
- `GROQ_TIMEOUT_SECONDS`: request timeout; defaults to 15 seconds.
- `ENVIRONMENT`: informational deployment environment.

## Endpoints

- `GET /health`
- `GET /api/v1/auth/me` (verified Firebase bearer token; provisions the backend profile)
- `GET/PATCH /api/v1/profile` (role, goals, schedule, cloud-sync and AI consent)
- Activity, app-usage and check-in collection CRUD under `/api/v1/activity`, `/api/v1/app-usage`, `/api/v1/check-ins`
- Focus sessions under `/api/v1/focus-sessions`
- `GET /api/v1/insights`, `POST /api/v1/analysis/recompute`, `GET /api/v1/recommendations`, recommendation feedback
- `GET /api/v1/export`, `DELETE /api/v1/account`

Record creation and updates require explicit cloud-sync consent. Groq is only called when `ai_consent` is true and deterministic history is sufficient. The deterministic median/MAD comparison remains authoritative. The AI service receives aggregated metric evidence without account identifiers, app package names, or notes. AI failures preserve deterministic results.

## Migrations and tests

```powershell
alembic upgrade head
python -m pytest -q
```

The migration path includes a compatibility revision for Firebase identity and consent fields. Tests use mocked Firebase verification and do not need Firebase or Groq credentials.

## Render

The Blueprint is at `backend/render.yaml`; select that explicit path when creating the Render service. Configure Firebase credentials and optional Groq credentials as protected environment variables. `DATABASE_URL` comes from the Render PostgreSQL resource. The web service runs migrations before startup and exposes `/health`. No deployment or live credential verification is established by this repository.
