from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, EmailStr, Field

Role = Literal["student", "creator", "professional", "self_employed", "mixed", "other"]
Source = Literal["manual", "csv", "native"]


class ProfileUpdate(BaseModel):
    role: Role | None = None
    goals: list[str] | None = None
    schedule_preferences: dict | None = None
    usual_bedtime: str | None = None
    cloud_sync_enabled: bool | None = None
    ai_consent: bool | None = None


class ProfileOut(BaseModel):
    id: str
    email: EmailStr
    role: str
    goals: list
    schedule_preferences: dict
    usual_bedtime: str | None
    model_config = ConfigDict(from_attributes=True)


class ActivityIn(BaseModel):
    local_date: date
    screen_minutes: int | None = Field(default=None, ge=0, le=1440)
    late_night_minutes: int | None = Field(default=None, ge=0, le=1440)
    active_windows: dict | None = None
    break_count: int | None = Field(default=None, ge=0, le=1000)
    tasks_planned: int | None = Field(default=None, ge=0, le=10000)
    tasks_completed: int | None = Field(default=None, ge=0, le=10000)
    steps: int | None = Field(default=None, ge=0, le=200000)
    sleep_minutes: int | None = Field(default=None, ge=0, le=1440)
    bedtime: str | None = None
    source: Source = "manual"


class UsageIn(BaseModel):
    local_date: date
    app_label: str = Field(min_length=1, max_length=120)
    duration_minutes: int = Field(ge=0, le=1440)
    late_night_minutes: int | None = Field(default=None, ge=0, le=1440)
    purpose: Literal["work", "study", "social", "leisure", "recovery", "unknown", "mixed"] = "unknown"
    purpose_confirmed: bool = False
    notes: str | None = Field(default=None, max_length=2000)
    source: Source = "manual"


class CheckInIn(BaseModel):
    local_date: date
    mood: int = Field(ge=1, le=5)
    stress: int = Field(ge=1, le=5)
    energy: int = Field(ge=1, le=5)
    fatigue: int = Field(ge=1, le=5)
    context_tags: list[str] = Field(default_factory=list, max_length=20)
    notes: str | None = Field(default=None, max_length=4000)


class SessionIn(BaseModel):
    client_id: str | None = Field(default=None, max_length=64)
    session_type: Literal["focus", "break"]
    planned_seconds: int = Field(ge=60, le=86400)
    actual_seconds: int = Field(default=0, ge=0, le=86400)
    started_at: datetime
    ended_at: datetime | None = None
    status: Literal["running", "completed", "interrupted", "skipped", "cancelled"] = "running"
    completed: bool = False
    cycle_number: int = Field(default=1, ge=1)
    local_date: date
    timezone_name: str = Field(default="UTC", max_length=64)


class SessionPatch(BaseModel):
    actual_seconds: int | None = Field(default=None, ge=0, le=86400)
    ended_at: datetime | None = None
    status: Literal["running", "completed", "interrupted", "skipped", "cancelled"] | None = None
    completed: bool | None = None


class FeedbackIn(BaseModel):
    feedback: Literal["helpful", "same", "not_helpful"]

