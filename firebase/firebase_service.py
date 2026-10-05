import os
from pathlib import Path

import firebase_admin
from firebase_admin import credentials, firestore

DEFAULT_KEY_PATH = Path(__file__).resolve().parent.parent / "serviceAccountKey.json"


class FirebaseService:
    """Owns the Firebase Admin app and the shared Firestore client."""

    def __init__(self, default_key_path: Path = DEFAULT_KEY_PATH):
        self._default_key_path = default_key_path
        self._db = None

    def _key_path(self) -> Path:
        return Path(os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", self._default_key_path))

    def initialize(self) -> None:
        """Initialize the Firebase Admin SDK once."""
        key_path = self._key_path()
        if not key_path.is_file():
            raise FileNotFoundError(
                f"Firebase service account key not found at {key_path}. "
                "Download it from Firebase Console > Project settings > "
                "Service accounts and save it there, or set GOOGLE_APPLICATION_CREDENTIALS."
            )

        if not firebase_admin._apps:
            firebase_admin.initialize_app(credentials.Certificate(str(key_path)))

    def get_db(self):
        """Return the shared Firestore client, initializing Firebase on first use."""
        if self._db is None:
            self.initialize()
            self._db = firestore.client()
        return self._db


firebase_service = FirebaseService()
