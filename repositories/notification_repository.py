from datetime import datetime, timedelta, timezone
from typing import Dict, List, Optional

from firebase_admin import firestore
from google.api_core.exceptions import InvalidArgument, NotFound
from google.cloud.firestore import transactional
from google.cloud.firestore_v1.base_query import FieldFilter

from firebase.firebase_service import FirebaseService

BATCH_LIMIT = 400  # Firestore allows 500 writes per batch


class NotificationRepository:
    """Firestore access for users/{uid}/notifications."""

    def __init__(self, firebase: FirebaseService):
        self._firebase = firebase

    def _collection(self, uid: str):
        return self._firebase.get_db().collection("users").document(uid).collection("notifications")

    @staticmethod
    def _to_dict(doc) -> dict:
        return {"id": doc.id, **doc.to_dict()}

    def create_unless_recent(
        self, recipient_uid: str, data: dict, same_as: Dict[str, str], cooldown: timedelta
    ) -> Optional[dict]:
        """Create the notification unless one matching `same_as` was created within `cooldown`.

        The check and the write happen in one Firestore transaction, so two simultaneous
        requests cannot both create a notification. Returns the saved notification, or
        None if a recent one already exists.
        """
        collection = self._collection(recipient_uid)
        new_ref = collection.document()
        cutoff = datetime.now(timezone.utc) - cooldown

        @transactional
        def attempt(transaction) -> bool:
            query = collection
            for field, value in same_as.items():
                query = query.where(filter=FieldFilter(field, "==", value))
            for existing in query.stream(transaction=transaction):
                created_at = existing.to_dict().get("createdAt")
                if created_at is None or created_at >= cutoff:  # None: timestamp still pending
                    return False
            transaction.create(new_ref, {**data, "createdAt": firestore.SERVER_TIMESTAMP})
            return True

        if not attempt(self._firebase.get_db().transaction()):
            return None
        return self._to_dict(new_ref.get())

    def list_for_user(self, uid: str) -> List[dict]:
        docs = self._collection(uid).order_by("createdAt", direction=firestore.Query.DESCENDING).stream()
        return [self._to_dict(doc) for doc in docs]

    def count_unread(self, uid: str) -> int:
        query = self._collection(uid).where(filter=FieldFilter("isRead", "==", False))
        return query.count().get()[0][0].value

    def mark_read(self, uid: str, notification_id: str) -> Optional[dict]:
        """Mark one notification read. Idempotent: readAt is only set the first time."""
        ref = self._collection(uid).document(notification_id)
        doc = ref.get()
        if not doc.exists:
            return None
        if not doc.to_dict().get("isRead"):
            ref.update({"isRead": True, "readAt": firestore.SERVER_TIMESTAMP})
            doc = ref.get()
        return self._to_dict(doc)

    def delete_for_group(self, uid: str, group_id: str) -> int:
        """Delete every notification of this user that is about the given group (all types).

        Used when a group is deleted, so no notification is left pointing at a group that no longer
        exists. Only this user's notifications whose groupId matches are touched. Returns how many.
        """
        docs = list(self._collection(uid).where(filter=FieldFilter("groupId", "==", group_id)).stream())
        db = self._firebase.get_db()
        for start in range(0, len(docs), BATCH_LIMIT):
            batch = db.batch()
            for doc in docs[start:start + BATCH_LIMIT]:
                batch.delete(doc.reference)
            batch.commit()
        return len(docs)

    def delete(self, uid: str, notification_id: str) -> bool:
        """Permanently delete one of the user's own notifications.

        The path is always users/{uid}/notifications/{id} with the uid of the authenticated user, so
        nobody can delete someone else's notification. The delete only succeeds if the document exists
        (checked atomically by Firestore), so False means nothing was deleted.
        """
        try:
            ref = self._collection(uid).document(notification_id)
            ref.delete(option=self._firebase.get_db().write_option(exists=True))
        except (NotFound, InvalidArgument, ValueError):  # missing, or not a valid Firestore document id
            return False
        return True

    def mark_all_read(self, uid: str) -> int:
        unread = list(self._collection(uid).where(filter=FieldFilter("isRead", "==", False)).stream())
        db = self._firebase.get_db()
        for start in range(0, len(unread), BATCH_LIMIT):
            batch = db.batch()
            for doc in unread[start:start + BATCH_LIMIT]:
                batch.update(doc.reference, {"isRead": True, "readAt": firestore.SERVER_TIMESTAMP})
            batch.commit()
        return len(unread)
