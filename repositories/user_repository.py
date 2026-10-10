from typing import List, Optional

from google.api_core.exceptions import InvalidArgument

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

    @staticmethod
    def _public_profile(uid: str, data: dict) -> dict:
        """Only the fields that are safe to show to other users."""
        profile = {
            "uid": uid,
            "displayName": (data.get("name") or "").strip() or None,
            "email": data.get("email") or None,
        }
        photo = data.get("photoUrl")
        if photo:
            profile["photoUrl"] = photo
        return profile

    def get_public_profile(self, uid: str) -> Optional[dict]:
        """Safe public profile of a registered user, or None if there is no such user."""
        try:
            doc = self._firebase.get_db().collection(self.COLLECTION).document(uid).get()
        except (ValueError, InvalidArgument):  # not a valid Firestore document id, so no such user
            return None
        if not doc.exists:
            return None
        return self._public_profile(doc.id, doc.to_dict())

    def search(self, query: str, limit: int) -> List[dict]:
        """Registered users whose name or email contains `query` (case-insensitive), public fields only.

        Profiles are small and Firestore has no substring search, so this scans the profile
        documents (only the name/email/photoUrl fields are fetched) and filters here.
        """
        needle = query.casefold()
        found = []
        docs = self._firebase.get_db().collection(self.COLLECTION).select(["name", "email", "photoUrl"]).stream()
        for doc in docs:
            data = doc.to_dict()
            haystack = f"{data.get('name') or ''}\n{data.get('email') or ''}".casefold()
            if needle in haystack:
                found.append(self._public_profile(doc.id, data))
        found.sort(key=lambda user: (user["displayName"] or "").casefold())
        return found[:limit]
