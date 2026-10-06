from typing import List, Optional

from google.api_core.exceptions import InvalidArgument
from google.cloud.firestore_v1.base_query import FieldFilter

from firebase.firebase_service import FirebaseService


class GroupRepository:
    """Firestore access for the 'groups' collection."""

    COLLECTION = "groups"

    def __init__(self, firebase: FirebaseService):
        self._firebase = firebase

    def get_by_id(self, group_id: str) -> Optional[dict]:
        try:
            doc = self._firebase.get_db().collection(self.COLLECTION).document(group_id).get()
        except (ValueError, InvalidArgument):  # not a valid Firestore document id, so no such group
            return None
        if not doc.exists:
            return None
        return {"id": doc.id, **doc.to_dict()}

    def delete_with_all_data(self, group_id: str) -> None:
        """Permanently delete the group document and EVERYTHING nested under it
        (groups/{id}/expenses, groups/{id}/settlements and any other subcollection)."""
        db = self._firebase.get_db()
        db.recursive_delete(db.collection(self.COLLECTION).document(group_id))

    def list_by_member(self, uid: str) -> List[dict]:
        query = self._firebase.get_db().collection(self.COLLECTION).where(
            filter=FieldFilter("memberIds", "array_contains", uid)
        )
        return [{"id": doc.id, **doc.to_dict()} for doc in query.stream()]
