from datetime import date, datetime, timezone
from uuid import uuid4

from sqlalchemy import Boolean, Date, DateTime, Float, ForeignKey, Integer, JSON, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.db.session import Base


def now_utc():
    return datetime.now(timezone.utc)


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    email: Mapped[str | None] = mapped_column(String(320), unique=True, index=True, nullable=True)
    password_hash: Mapped[str | None] = mapped_column(String(255), nullable=True)
    firebase_uid: Mapped[str | None] = mapped_column(String(128), unique=True, index=True, nullable=True)
    cloud_sync_enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    ai_consent: Mapped[bool] = mapped_column(Boolean, default=False)
    role: Mapped[str] = mapped_column(String(30), default="other")
    goals: Mapped[list] = mapped_column(JSON, default=list)
    schedule_preferences: Mapped[dict] = mapped_column(JSON, default=dict)
    usual_bedtime: Mapped[str | None] = mapped_column(String(5), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc, onupdate=now_utc)


class Activity(Base):
    __tablename__ = "activity"
    __table_args__ = (UniqueConstraint("user_id", "local_date", "source", name="uq_activity_owner_date_source"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    local_date: Mapped[date] = mapped_column(Date, index=True)
    screen_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    late_night_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    active_windows: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    break_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    tasks_planned: Mapped[int | None] = mapped_column(Integer, nullable=True)
    tasks_completed: Mapped[int | None] = mapped_column(Integer, nullable=True)
    steps: Mapped[int | None] = mapped_column(Integer, nullable=True)
    sleep_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    bedtime: Mapped[str | None] = mapped_column(String(5), nullable=True)
    source: Mapped[str] = mapped_column(String(20), default="manual")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc, onupdate=now_utc)


class AppUsage(Base):
    __tablename__ = "app_usage"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    local_date: Mapped[date] = mapped_column(Date, index=True)
    app_label: Mapped[str] = mapped_column(String(120))
    duration_minutes: Mapped[int] = mapped_column(Integer)
    late_night_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    purpose: Mapped[str] = mapped_column(String(20), default="unknown")
    purpose_confirmed: Mapped[bool] = mapped_column(Boolean, default=False)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    source: Mapped[str] = mapped_column(String(20), default="manual")


class CheckIn(Base):
    __tablename__ = "check_ins"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    local_date: Mapped[date] = mapped_column(Date, index=True)
    recorded_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=now_utc)
    mood: Mapped[int] = mapped_column(Integer)
    stress: Mapped[int] = mapped_column(Integer)
    energy: Mapped[int] = mapped_column(Integer)
    fatigue: Mapped[int] = mapped_column(Integer)
    context_tags: Mapped[list] = mapped_column(JSON, default=list)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)


class FocusSession(Base):
    __tablename__ = "focus_sessions"
    __table_args__ = (UniqueConstraint("user_id", "client_id", name="uq_focus_owner_client_id"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    client_id: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    session_type: Mapped[str] = mapped_column(String(10))
    planned_seconds: Mapped[int] = mapped_column(Integer)
    actual_seconds: Mapped[int] = mapped_column(Integer, default=0)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    status: Mapped[str] = mapped_column(String(15), default="running")
    completed: Mapped[bool] = mapped_column(Boolean, default=False)
    cycle_number: Mapped[int] = mapped_column(Integer, default=1)
    local_date: Mapped[date] = mapped_column(Date, index=True)
    timezone_name: Mapped[str] = mapped_column(String(64), default="UTC")


class Insight(Base):
    __tablename__ = "insights"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    insight_type: Mapped[str] = mapped_column(String(50))
    category: Mapped[str] = mapped_column(String(30))
    start_date: Mapped[date] = mapped_column(Date)
    end_date: Mapped[date] = mapped_column(Date)
    evidence: Mapped[list] = mapped_column(JSON, default=list)
    observed_value: Mapped[float | None] = mapped_column(Float, nullable=True)
    baseline_value: Mapped[float | None] = mapped_column(Float, nullable=True)
    deviation: Mapped[float | None] = mapped_column(Float, nullable=True)
    source: Mapped[str] = mapped_column(String(30), default="analysis")
    uncertainty: Mapped[str] = mapped_column(Text)
    explanation: Mapped[str] = mapped_column(Text)
    recommendation: Mapped[str | None] = mapped_column(Text, nullable=True)
    recommendation_status: Mapped[str] = mapped_column(String(15), default="proposed")
    feedback: Mapped[str | None] = mapped_column(String(20), nullable=True)
    headline: Mapped[str | None] = mapped_column(String(100), nullable=True)
    generation_status: Mapped[str] = mapped_column(String(30), default="deterministic_only")
    model_name: Mapped[str | None] = mapped_column(String(100), nullable=True)


class ContextEvent(Base):
    __tablename__ = "context_events"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid4()))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    tag: Mapped[str] = mapped_column(String(30))
    start_date: Mapped[date] = mapped_column(Date)
    end_date: Mapped[date] = mapped_column(Date)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
