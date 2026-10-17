from app.services.analysis import compare


def test_health_and_openapi(client):
    assert client.get("/health").json() == {"status": "ok"}
    assert client.get("/openapi.json").status_code == 200


def test_firebase_identity_required_and_invalid(client):
    assert client.get("/api/v1/auth/me").status_code == 401
    assert client.get("/api/v1/auth/me", headers={"Authorization": "Bearer invalid"}).status_code == 401
    assert client.get("/api/v1/auth/me", headers={"Authorization": "Bearer valid-uid"}).json()["id"]


def test_consent_gate_and_user_isolation(client, headers):
    body = {"local_date": "2026-10-17", "mood": 4, "stress": 2, "energy": 3, "fatigue": 1, "context_tags": ["Exam"]}
    assert client.post("/api/v1/check-ins", json={**body, "mood": 6}, headers=headers).status_code == 422
    assert client.post("/api/v1/check-ins", json=body, headers=headers).status_code == 201
    other = {"Authorization": "Bearer other-person"}
    client.get("/api/v1/auth/me", headers=other)
    assert client.get("/api/v1/check-ins", headers=other).json() == []
    row = client.get("/api/v1/check-ins", headers=headers).json()[0]
    assert client.delete(f"/api/v1/check-ins/{row['id']}", headers=other).status_code == 404


def test_sync_requires_consent(client):
    headers = {"Authorization": "Bearer no-consent"}
    client.get("/api/v1/auth/me", headers=headers)
    response = client.post("/api/v1/activity", json={"local_date": "2026-10-17"}, headers=headers)
    assert response.status_code == 403


def test_activity_crud_nullable_values(client, headers):
    body = {"local_date": "2026-10-17", "source": "native", "screen_minutes": None, "break_count": 3}
    created = client.post("/api/v1/activity", json=body, headers=headers)
    assert created.status_code == 201 and created.json()["screen_minutes"] is None
    update = client.put(f"/api/v1/activity/{created.json()['id']}", json={**body, "break_count": 5}, headers=headers)
    assert update.json()["break_count"] == 5


def test_focus_session_idempotency_and_patch(client, headers):
    body = {"client_id": "timer-1", "session_type": "focus", "planned_seconds": 2700, "actual_seconds": 900,
            "started_at": "2026-10-17T10:00:00+05:30", "status": "running", "local_date": "2026-10-17", "timezone_name": "Asia/Kolkata"}
    first = client.post("/api/v1/focus-sessions", json=body, headers=headers)
    second = client.post("/api/v1/focus-sessions", json=body, headers=headers)
    assert first.status_code == 201 and second.json()["id"] == first.json()["id"]
    updated = client.patch(f"/api/v1/focus-sessions/{first.json()['id']}", json={"status": "interrupted", "actual_seconds": 1200}, headers=headers)
    assert updated.json()["status"] == "interrupted"


def test_recompute_and_export(client, headers):
    for i in range(9):
        client.post("/api/v1/activity", json={"local_date": f"2026-10-{i+1:02d}", "screen_minutes": 100+i, "source": "native"}, headers=headers)
    result = client.post("/api/v1/analysis/recompute", headers=headers).json()
    assert result and result[0]["category"] in {"typical", "changed", "worth_reviewing"}
    assert result[0]["generation_status"] == "not_consented"
    assert client.post("/api/v1/analysis/recompute", headers=headers).json()[0]["id"] == result[0]["id"]
    assert client.get("/api/v1/export", headers=headers).status_code == 200
    assert compare(180, [100, 101, 102, 99, 103, 100, 101], "screen_time")["baseline"] == 101
