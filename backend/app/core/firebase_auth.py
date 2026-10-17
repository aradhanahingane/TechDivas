"""Firebase ID-token verification. Admin credentials are backend-only."""
import json

from app.core.config import settings


def verify_firebase_id_token(token: str) -> dict:
    import firebase_admin
    from firebase_admin import auth, credentials

    if not firebase_admin._apps:
        if settings.firebase_service_account_json:
            credential = credentials.Certificate(json.loads(settings.firebase_service_account_json))
            firebase_admin.initialize_app(credential)
        else:
            firebase_admin.initialize_app()
    return auth.verify_id_token(token, check_revoked=True)
