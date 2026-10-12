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

    def leave(self, group_id: str, uid: str) -> None:
        """Atomically remove `uid` from memberIds, in one transaction that re-reads the group.

        Only memberIds is written (ArrayRemove). Raises LookupError (no group), PermissionError (uid is
        not a member) or ValueError(other_member_count) when uid is the group's creator: the creator
        may not leave, whether or not other members remain (the group must be deleted instead).
        """
        db = self._firebase.get_db()
        ref = db.collection(self.COLLECTION).document(group_id)

        @transactional
        def attempt(transaction):
            doc = ref.get(transaction=transaction)
            if not doc.exists:
                raise LookupError(group_id)
            data = doc.to_dict()
            members = data.get("memberIds", [])
            if uid not in members:
                raise PermissionError(uid)
            if uid == data.get("createdBy"):
                raise ValueError(len([m for m in members if m != uid]))
            transaction.update(ref, {"memberIds": firestore.ArrayRemove([uid])})

        attempt(db.transaction())

    def remove_member(self, group_id: str, requester_uid: str, target_uid: str) -> Optional[dict]:
        """Atomically remove `target_uid` from memberIds, in one transaction that re-reads the group.

        ONLY memberIds is written (ArrayRemove). Expenses and settlements live in subcollections and are
        never read or written here, so history stays exactly as it was.
        Rule, the same for every group: any current member may remove any OTHER current member;
        createdBy plays no part. Raises LookupError (no group), PermissionError (requester is not a
        member), ValueError (requester == target: leaving is not done through this call) or
        FileNotFoundError (target is not a member). Returns the updated group.
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
            if target_uid == requester_uid:
                raise ValueError(target_uid)
            if target_uid not in members:
                raise FileNotFoundError(target_uid)
            transaction.update(ref, {"memberIds": firestore.ArrayRemove([target_uid])})

        attempt(db.transaction())
        return self.get_by_id(group_id)
