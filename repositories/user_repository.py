from typing import Optional

from firebase.firebase_service import FirebaseService


class UserRepository:
    """Firestore access for the 'users' collection (profiles keyed by Firebase uid)."""

    COLLECTION = "users"

    def __init__(self, firebase: FirebaseService):
        self._firebase = firebase

    def get_display_name(self, uid: str) -> Optional[str]:
        doc = self._firebase.get_db().collection(self.COLLECTION).document(uid).get()
        if not doc.exists:
            return None
        name = (doc.to_dict().get("name") or "").strip()
        return name or None
