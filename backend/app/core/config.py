from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = "sqlite:///./rhythm.db"
    environment: str = "development"
    firebase_service_account_json: str | None = None
    groq_api_key: str | None = None
    groq_model: str = "openai/gpt-oss-120b"
    groq_timeout_seconds: float = Field(default=15.0, gt=0, le=60)
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()
