from datetime import datetime, timezone
from uuid import uuid4

from fastapi import Depends, FastAPI, HTTPException, Query
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core.config import settings
from app.core.firebase_auth import verify_firebase_id_token
from app.db.session import Base, engine, get_db
from app.models.entities import Activity, AppUsage, CheckIn, ContextEvent, FocusSession, Insight, User
from app.services.groq_ai import explain_finding
from app.schemas.api import (ActivityIn, CheckInIn, FeedbackIn, ProfileUpdate, SessionIn,
                             SessionPatch, UsageIn)
from app.services.analysis import compare

app = FastAPI(title="RHYTHM API", version="1.0.0", description="Private behavioral insights and journaling API. Demo data is never uploaded automatically.")
bearer = HTTPBearer(auto_error=False)


def current_user(credentials: HTTPAuthorizationCredentials | None = Depends(bearer), db: Session = Depends(get_db)) -> User:
    if credentials is None:
        raise HTTPException(status_code=401, detail="Authentication required")
    try:
        claims = verify_firebase_id_token(credentials.credentials)
    except Exception:
        raise HTTPException(status_code=401, detail="Invalid or expired Firebase token") from None
    firebase_uid = claims.get("uid") or claims.get("sub") or claims.get("user_id")
    if not isinstance(firebase_uid, str) or not firebase_uid:
        raise HTTPException(status_code=401, detail="Invalid Firebase identity")
    user = db.scalar(select(User).where(User.firebase_uid == firebase_uid))
    if user is None:
        email = claims.get("email") if claims.get("email_verified") else None
        # Link a legacy account only after Firebase proves control of its email.
        user = db.scalar(select(User).where(User.email == email)) if email else None
        if user:
            user.firebase_uid = firebase_uid
        else:
            user = User(firebase_uid=firebase_uid, email=email)
            db.add(user)
        try:
            db.commit()
            db.refresh(user)
        except IntegrityError:
            db.rollback()
            user = db.scalar(select(User).where(User.firebase_uid == firebase_uid))
            if user is None:
                raise HTTPException(status_code=409, detail="Account identity conflict") from None
    return user


def owned(db: Session, model, record_id: str, user: User):
    record = db.scalar(select(model).where(model.id == record_id, model.user_id == user.id))
    if not record:
        raise HTTPException(status_code=404, detail="Record not found")
    return record


def create_record(db: Session, model, user: User, data: dict):
    row = model(user_id=user.id, **data)
    db.add(row)
    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(status_code=409, detail="A matching record already exists") from None
    db.refresh(row)
    return row


def list_records(db: Session, model, user: User, limit: int, offset: int):
    return list(db.scalars(select(model).where(model.user_id == user.id).order_by(model.local_date.desc()).offset(offset).limit(limit)))


def crud_routerless(model, schema, collection: str):
    @app.get(f"/api/v1/{collection}", tags=[collection])
    def listing(limit: int = Query(50, ge=1, le=200), offset: int = Query(0, ge=0), user: User = Depends(current_user), db: Session = Depends(get_db)):
        return list_records(db, model, user, limit, offset)

    @app.post(f"/api/v1/{collection}", status_code=201, tags=[collection])
    def create(payload: schema, user: User = Depends(current_user), db: Session = Depends(get_db)):
        if not user.cloud_sync_enabled:
            raise HTTPException(status_code=403, detail="Cloud sync consent is required")
        return create_record(db, model, user, payload.model_dump())

    @app.put(f"/api/v1/{collection}/{{record_id}}", tags=[collection])
    def update(record_id: str, payload: schema, user: User = Depends(current_user), db: Session = Depends(get_db)):
        if not user.cloud_sync_enabled:
            raise HTTPException(status_code=403, detail="Cloud sync consent is required")
        row = owned(db, model, record_id, user)
        for key, value in payload.model_dump().items():
            setattr(row, key, value)
        db.commit()
        db.refresh(row)
        return row

    @app.delete(f"/api/v1/{collection}/{{record_id}}", status_code=204, tags=[collection])
    def delete(record_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
        db.delete(owned(db, model, record_id, user))
        db.commit()


@app.get("/health", tags=["health"])
def health():
    return {"status": "ok"}


@app.get("/api/v1/auth/me", tags=["auth"])
def me(user: User = Depends(current_user)):
    return {"id": user.id, "email": user.email, "role": user.role, "goals": user.goals,
            "schedule_preferences": user.schedule_preferences, "usual_bedtime": user.usual_bedtime,
            "cloud_sync_enabled": user.cloud_sync_enabled, "ai_consent": user.ai_consent}


@app.delete("/api/v1/account", status_code=204, tags=["auth"])
def delete_account(user: User = Depends(current_user), db: Session = Depends(get_db)):
    for model in (Activity, AppUsage, CheckIn, FocusSession, Insight, ContextEvent):
        db.query(model).filter(model.user_id == user.id).delete(synchronize_session=False)
    db.delete(user); db.commit()


@app.get("/api/v1/profile", tags=["profile"])
def get_profile(user: User = Depends(current_user)):
    return {"id": user.id, "email": user.email, "role": user.role, "goals": user.goals,
            "schedule_preferences": user.schedule_preferences, "usual_bedtime": user.usual_bedtime,
            "cloud_sync_enabled": user.cloud_sync_enabled, "ai_consent": user.ai_consent}


@app.patch("/api/v1/profile", tags=["profile"])
def patch_profile(payload: ProfileUpdate, user: User = Depends(current_user), db: Session = Depends(get_db)):
    requested = payload.model_dump(exclude_unset=True)
    if requested.get("ai_consent") is True and requested.get("cloud_sync_enabled", user.cloud_sync_enabled) is not True:
        raise HTTPException(status_code=422, detail="AI processing consent requires cloud sync consent")
    if requested.get("cloud_sync_enabled") is False and "ai_consent" not in requested:
        user.ai_consent = False
    for key, value in requested.items():
        setattr(user, key, value)
    db.commit(); db.refresh(user)
    return get_profile(user)


crud_routerless(Activity, ActivityIn, "activity")
crud_routerless(AppUsage, UsageIn, "app-usage")
crud_routerless(CheckIn, CheckInIn, "check-ins")


@app.get("/api/v1/focus-sessions", tags=["focus-sessions"])
def get_sessions(limit: int = Query(50, ge=1, le=200), offset: int = Query(0, ge=0), user: User = Depends(current_user), db: Session = Depends(get_db)):
    return list(db.scalars(select(FocusSession).where(FocusSession.user_id == user.id).order_by(FocusSession.started_at.desc()).offset(offset).limit(limit)))


@app.post("/api/v1/focus-sessions", status_code=201, tags=["focus-sessions"])
def post_session(payload: SessionIn, user: User = Depends(current_user), db: Session = Depends(get_db)):
    if not user.cloud_sync_enabled:
        raise HTTPException(status_code=403, detail="Cloud sync consent is required")
    if payload.client_id:
        existing = db.scalar(select(FocusSession).where(FocusSession.user_id == user.id, FocusSession.client_id == payload.client_id))
        if existing:
            return existing
    return create_record(db, FocusSession, user, payload.model_dump())


@app.patch("/api/v1/focus-sessions/{record_id}", tags=["focus-sessions"])
def patch_session(record_id: str, payload: SessionPatch, user: User = Depends(current_user), db: Session = Depends(get_db)):
    if not user.cloud_sync_enabled:
        raise HTTPException(status_code=403, detail="Cloud sync consent is required")
    row = owned(db, FocusSession, record_id, user)
    for key, value in payload.model_dump(exclude_unset=True).items():
        setattr(row, key, value)
    db.commit(); db.refresh(row)
    return row


@app.get("/api/v1/insights", tags=["analysis"])
def get_insights(user: User = Depends(current_user), db: Session = Depends(get_db)):
    return list(db.scalars(select(Insight).where(Insight.user_id == user.id).order_by(Insight.end_date.desc())))


@app.post("/api/v1/analysis/recompute", tags=["analysis"])
def recompute(user: User = Depends(current_user), db: Session = Depends(get_db)):
    metrics = ((Activity, "screen_minutes", "screen_time"), (Activity, "late_night_minutes", "late_night"),
               (Activity, "break_count", "breaks"), (Activity, "tasks_completed", "tasks_completed"),
               (CheckIn, "mood", "mood"), (CheckIn, "stress", "stress"), (CheckIn, "energy", "energy"),
               (CheckIn, "fatigue", "fatigue"))
    generated = []
    for model, field, name in metrics:
        rows = db.scalars(select(model).where(model.user_id == user.id).order_by(model.local_date)).all()
        grouped = {}
        for row in rows:
            grouped.setdefault(row.local_date.isoformat(), []).append(getattr(row, field))
        if not grouped:
            continue
        target_date = max(grouped)
        historical = [sum(v for v in vals if v is not None) / sum(v is not None for v in vals) for day, vals in grouped.items() if day < target_date and any(v is not None for v in vals)]
        observed_values = grouped[target_date]
        observed = sum(v for v in observed_values if v is not None) / max(1, sum(v is not None for v in observed_values)) if any(v is not None for v in observed_values) else None
        result = compare(observed, historical, name)
        deterministic_explanation = (f"Observed {result['observed']}; personal historical median {result['baseline']}. This is descriptive and does not establish a cause." if result["baseline"] is not None else "More valid historical days are needed for a personal baseline.")
        generated_ai, ai_status = (explain_finding(result) if user.cloud_sync_enabled and user.ai_consent and result["baseline"] is not None else (None, "not_consented" if not (user.cloud_sync_enabled and user.ai_consent) else "insufficient_data"))
        ai_uncertainty = f" {generated_ai.uncertainty}" if generated_ai else ""
        insight = Insight(user_id=user.id, insight_type=name, category=result["category"], start_date=datetime.fromisoformat(target_date).date(), end_date=datetime.fromisoformat(target_date).date(), evidence=[{"date": day, "value": value} for day, vals in grouped.items() for value in vals if value is not None], observed_value=result["observed"], baseline_value=result["baseline"], deviation=result["deviation"], source="deterministic", uncertainty=result["uncertainty"] + ai_uncertainty + (" AI explanation unavailable." if user.ai_consent and ai_status != "available" else ""), explanation=generated_ai.explanation if generated_ai else deterministic_explanation, recommendation=generated_ai.action if generated_ai else None, generation_status=ai_status, model_name=settings.groq_model if generated_ai else None, headline=generated_ai.headline if generated_ai else None)
        previous = db.scalar(select(Insight).where(
            Insight.user_id == user.id, Insight.insight_type == name,
            Insight.end_date == datetime.fromisoformat(target_date).date()
        ))
        if previous:
            for key, value in {
                "category": insight.category, "evidence": insight.evidence,
                "observed_value": insight.observed_value, "baseline_value": insight.baseline_value,
                "deviation": insight.deviation, "source": insight.source,
                "uncertainty": insight.uncertainty, "explanation": insight.explanation,
                "recommendation": insight.recommendation, "generation_status": insight.generation_status,
                "model_name": insight.model_name, "headline": insight.headline,
            }.items():
                setattr(previous, key, value)
            db.flush()
            generated.append(previous)
        else:
            db.add(insight)
            generated.append(insight)
    db.commit()
    for item in generated: db.refresh(item)
    return generated


@app.get("/api/v1/recommendations", tags=["analysis"])
def recommendations(user: User = Depends(current_user), db: Session = Depends(get_db)):
    return list(db.scalars(select(Insight).where(Insight.user_id == user.id, Insight.recommendation.is_not(None)).order_by(Insight.end_date.desc())))


@app.post("/api/v1/recommendations/{recommendation_id}/feedback", tags=["analysis"])
def recommendation_feedback(recommendation_id: str, payload: FeedbackIn, user: User = Depends(current_user), db: Session = Depends(get_db)):
    row = owned(db, Insight, recommendation_id, user)
    row.feedback = payload.feedback
    row.recommendation_status = "completed" if payload.feedback == "helpful" else "declined"
    db.commit(); db.refresh(row)
    return row


@app.get("/api/v1/export", tags=["data"])
def export_data(user: User = Depends(current_user), db: Session = Depends(get_db)):
    tables = {"activity": Activity, "app_usage": AppUsage, "check_ins": CheckIn,
              "focus_sessions": FocusSession, "insights": Insight, "context_events": ContextEvent}
    exported = {"profile": get_profile(user)}
    for name, model in tables.items():
        exported[name] = [{c.name: (getattr(r, c.name).isoformat() if hasattr(getattr(r, c.name), "isoformat") else getattr(r, c.name)) for c in model.__table__.columns if c.name != "user_id"} for r in db.scalars(select(model).where(model.user_id == user.id))]
    return exported


@app.post("/api/v1/import", tags=["data"])
def import_activity(payload: list[ActivityIn], user: User = Depends(current_user), db: Session = Depends(get_db)):
    if not user.cloud_sync_enabled:
        raise HTTPException(status_code=403, detail="Cloud sync consent is required")
    created = 0; duplicates = 0
    for item in payload:
        exists = db.scalar(select(Activity).where(Activity.user_id == user.id, Activity.local_date == item.local_date, Activity.source == item.source))
        if exists:
            duplicates += 1
        else:
            db.add(Activity(user_id=user.id, **item.model_dump())); created += 1
    db.commit()
    return {"created": created, "duplicates": duplicates}
