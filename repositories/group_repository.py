from typing import List, Optional

from google.cloud.firestore_v1.base_query import FieldFilter

from firebase.firebase_service import FirebaseService


class GroupRepository:
    """Firestore access for the 'groups' collection."""

    COLLECTION = "groups"

    def __init__(self, firebase: FirebaseService):
        self._firebase = firebase

    def get_by_id(self, group_id: str) -> Optional[dict]:
        doc = self._firebase.get_db().collection(self.COLLECTION).document(group_id).get()
        if not doc.exists:
            return None
        return {"id": doc.id, **doc.to_dict()}

    def list_by_member(self, uid: str) -> List[dict]:
        query = self._firebase.get_db().collection(self.COLLECTION).where(
            filter=FieldFilter("memberIds", "array_contains", uid)
        )
        return [{"id": doc.id, **doc.to_dict()} for doc in query.stream()]
