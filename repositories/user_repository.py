from typing import List, Optional

from google.api_core.exceptions import InvalidArgument

from firebase.firebase_service import FirebaseService
from models.user_models import ALLOWED_AVATAR_VALUES, DEFAULT_AVATAR


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
    def normalize_avatar(stored) -> dict:
        """A complete, valid avatar. Missing or invalid stored fields fall back to the default, so
        users without an avatar (or with a damaged one) never break a response."""
        stored = stored if isinstance(stored, dict) else {}
        avatar = dict(DEFAULT_AVATAR)
        for field, allowed in ALLOWED_AVATAR_VALUES.items():
            if stored.get(field) in allowed:
                avatar[field] = stored[field]
        if isinstance(stored.get("glassesEnabled"), bool):
            avatar["glassesEnabled"] = stored["glassesEnabled"]
        return avatar

    def get_own_profile(self, uid: str) -> Optional[dict]:
        """name/email/avatar of the user, or None if there is no users/{uid} document."""
        doc = self._firebase.get_db().collection(self.COLLECTION).document(uid).get()
        if not doc.exists:
            return None
        data = doc.to_dict()
        return {
            "uid": doc.id,
            "name": (data.get("name") or "").strip() or None,
            "email": data.get("email") or None,
            "avatar": self.normalize_avatar(data.get("avatar")),
        }

    def update_avatar(self, uid: str, changes: dict) -> Optional[dict]:
        """Merge `changes` into the stored avatar. Writes ONLY the `avatar` field (Firestore update(),
        never set()), so name, email and every other field stay as they are. Returns the saved avatar,
        or None if users/{uid} does not exist (nothing is created)."""
        ref = self._firebase.get_db().collection(self.COLLECTION).document(uid)
        doc = ref.get()
        if not doc.exists:
            return None
        avatar = {**self.normalize_avatar(doc.to_dict().get("avatar")), **changes}
        ref.update({"avatar": avatar})
        return avatar

    def get_public_profiles(self, uids: List[str]) -> List[dict]:
        """Public profiles in the order of `uids`. A uid with no profile document still gets an entry
        (uid + default avatar) so every member can be displayed."""
        if not uids:
            return []
        db = self._firebase.get_db()
        refs = [db.collection(self.COLLECTION).document(uid) for uid in uids]
        by_id = {doc.id: doc for doc in db.get_all(refs) if doc.exists}
        return [
            self._public_profile(uid, by_id[uid].to_dict())
            if uid in by_id
            else {"uid": uid, "displayName": None, "email": None, "avatar": dict(DEFAULT_AVATAR)}
            for uid in uids
        ]

    @classmethod
    def _public_profile(cls, uid: str, data: dict) -> dict:
        """Only the fields that are safe to show to other users."""
        profile = {
            "uid": uid,
            "displayName": (data.get("name") or "").strip() or None,
            "email": data.get("email") or None,
            "avatar": cls.normalize_avatar(data.get("avatar")),
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
        documents (only the name/email/photoUrl/avatar fields are fetched) and filters here.
        """
        needle = query.casefold()
        found = []
        docs = self._firebase.get_db().collection(self.COLLECTION).select(["name", "email", "photoUrl", "avatar"]).stream()
        for doc in docs:
            data = doc.to_dict()
            haystack = f"{data.get('name') or ''}\n{data.get('email') or ''}".casefold()
            if needle in haystack:
                found.append(self._public_profile(doc.id, data))
        found.sort(key=lambda user: (user["displayName"] or "").casefold())
        return found[:limit]
