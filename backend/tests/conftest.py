import os
from pathlib import Path

os.environ["DATABASE_URL"] = "sqlite:///:memory:"
os.environ["ENVIRONMENT"] = "test"
os.environ["GROQ_API_KEY"] = ""

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.db.session import Base, get_db
from app.main import app

engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
TestingSession = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


@pytest.fixture
def client(monkeypatch):
    def fake_verify(token):
        if token == "invalid":
            raise ValueError("bad token")
        return {"uid": token, "email": f"{token}@example.com", "email_verified": True}
    monkeypatch.setattr("app.main.verify_firebase_id_token", fake_verify)
    Base.metadata.create_all(engine)
    def override_db():
        session = TestingSession()
        try:
            yield session
        finally:
            session.close()
    app.dependency_overrides[get_db] = override_db
    with TestClient(app) as test_client:
        yield test_client
    app.dependency_overrides.clear()
    Base.metadata.drop_all(engine)


@pytest.fixture
def headers(client):
    result = {"Authorization": "Bearer test-person"}
    assert client.get("/api/v1/auth/me", headers=result).status_code == 200
    assert client.patch("/api/v1/profile", json={"cloud_sync_enabled": True}, headers=result).status_code == 200
    return result
