from datetime import datetime
from typing import Callable, Optional, Tuple

from firebase_admin import firestore

from firebase.firebase_service import FirebaseService


class PasswordResetRepository:
    """Firestore access for passwordResetRequests/{requestId}.

    The request id is derived from the email, so each email has at most one active request.
    """

    COLLECTION = "passwordResetRequests"

    def __init__(self, firebase: FirebaseService):
        self._firebase = firebase

    def _collection(self):
        return self._firebase.get_db().collection(self.COLLECTION)

    def get(self, request_id: str) -> Optional[dict]:
        doc = self._collection().document(request_id).get()
        return doc.to_dict() if doc.exists else None

    def transact(self, request_id: str, mutate: Callable[[Optional[dict]], Tuple[Optional[dict], object]]):
        """Read the request, let `mutate(data)` decide, and write its dict (merged) atomically.

        `mutate` returns (fields_to_write | None, result). It must not raise: an exception would
        roll back the write (e.g. a failed-attempt counter). Returns `result`.
        """
        db = self._firebase.get_db()
        ref = self._collection().document(request_id)

        @firestore.transactional
        def run(transaction):
            snapshot = ref.get(transaction=transaction)
            writes, result = mutate(snapshot.to_dict() if snapshot.exists else None)
            if writes:
                transaction.set(ref, writes, merge=True)
            return result

        return run(db.transaction())

    def update(self, request_id: str, fields: dict) -> None:
        self._collection().document(request_id).set(fields, merge=True)

    def delete_stale(self, now: datetime, limit: int = 25) -> int:
        """Delete up to `limit` requests whose purgeAfter has passed. Returns how many."""
        query = self._collection().where("purgeAfter", "<", now).limit(limit)
        deleted = 0
        for doc in query.stream():
            doc.reference.delete()
            deleted += 1
        return deleted
