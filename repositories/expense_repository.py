from typing import List

from firebase_admin import firestore

from firebase.firebase_service import FirebaseService


class ExpenseRepository:
    """Firestore access for the 'expenses' subcollection of a group."""

    def __init__(self, firebase: FirebaseService):
        self._firebase = firebase

    def _collection(self, group_id: str):
        return (
            self._firebase.get_db()
            .collection("groups")
            .document(group_id)
            .collection("expenses")
        )

    def list_by_group(self, group_id: str) -> List[dict]:
        return [{"id": doc.id, **doc.to_dict()} for doc in self._collection(group_id).stream()]

    def create(self, group_id: str, data: dict) -> dict:
        """Store an expense with a server-side createdAt and return it as saved."""
        _, doc_ref = self._collection(group_id).add({**data, "createdAt": firestore.SERVER_TIMESTAMP})
        doc = doc_ref.get()
        return {"id": doc.id, **doc.to_dict()}
