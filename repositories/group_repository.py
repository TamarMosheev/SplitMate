from typing import List, Optional

from firebase_admin import firestore
from google.api_core.exceptions import InvalidArgument
from google.cloud.firestore import transactional
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

    def add_member(self, group_id: str, requester_uid: str, new_uid: str, validate_target) -> Optional[dict]:
        """Atomically add `new_uid` to memberIds, in one transaction that re-reads the group.

        Checks run against the freshly read group, so a concurrent change cannot slip through:
        `validate_target()` is called once the requester is confirmed a member. Only memberIds is
        written (ArrayUnion, never overwritten); createdBy and the expenses are untouched.
        Returns the updated group. Raises LookupError (no group), PermissionError (requester not a
        member) or FileExistsError (already a member); `validate_target` may raise its own errors.
        """
        db = self._firebase.get_db()
        ref = db.collection(self.COLLECTION).document(group_id)

        @transactional
        def attempt(transaction):
            doc = ref.get(transaction=transaction)
            if not doc.exists:
                raise LookupError(group_id)
            members = doc.to_dict().get("memberIds", [])
            if requester_uid not in members:
                raise PermissionError(requester_uid)
            validate_target()
            if new_uid in members:
                raise FileExistsError(new_uid)
            transaction.update(ref, {"memberIds": firestore.ArrayUnion([new_uid])})

        attempt(db.transaction())
        return self.get_by_id(group_id)
