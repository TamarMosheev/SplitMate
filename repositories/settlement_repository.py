from typing import List, Optional

from firebase_admin import firestore
from google.api_core.exceptions import AlreadyExists
from google.cloud.firestore import transactional

from firebase.firebase_service import FirebaseService


class SettlementRepository:
    """Firestore access for groups/{groupId}/settlements.

    Only 'open' settlements may be changed or removed by reconciliation; every write that
    could touch an existing document re-checks its status inside a transaction, so a paid
    settlement can never be altered, deleted or reopened by a concurrent request.
    """

    def __init__(self, firebase: FirebaseService):
        self._firebase = firebase

    def _collection(self, group_id: str):
        return self._firebase.get_db().collection("groups").document(group_id).collection("settlements")

    @staticmethod
    def _to_dict(doc) -> dict:
        return {**doc.to_dict(), "id": doc.id}

    def list_all(self, group_id: str) -> List[dict]:
        return [self._to_dict(doc) for doc in self._collection(group_id).stream()]

    def get(self, group_id: str, settlement_id: str) -> Optional[dict]:
        doc = self._collection(group_id).document(settlement_id).get()
        return self._to_dict(doc) if doc.exists else None

    def create_open(self, group_id: str, settlement_id: str, debtor: str, creditor: str, amount: float) -> None:
        """Create an open settlement with a fixed id. A no-op if that id already exists."""
        try:
            self._collection(group_id).document(settlement_id).create({
                "id": settlement_id,
                "from": debtor,
                "to": creditor,
                "amount": amount,
                "status": "open",
                "createdAt": firestore.SERVER_TIMESTAMP,
                "paidAt": None,
                "markedBy": None,
                "paymentClaimStatus": "none",
                "paymentClaimedAt": None,
                "paymentClaimedBy": None,
            })
        except AlreadyExists:
            pass

    def update_open_amount(self, group_id: str, settlement_id: str, amount: float) -> None:
        ref = self._collection(group_id).document(settlement_id)

        @transactional
        def attempt(transaction):
            snap = ref.get(transaction=transaction)
            if snap.exists and snap.to_dict().get("status") == "open" and snap.to_dict().get("amount") != amount:
                transaction.update(ref, {"amount": amount})

        attempt(self._firebase.get_db().transaction())

    def delete_open(self, group_id: str, settlement_id: str) -> None:
        ref = self._collection(group_id).document(settlement_id)

        @transactional
        def attempt(transaction):
            snap = ref.get(transaction=transaction)
            if snap.exists and snap.to_dict().get("status") == "open":
                transaction.delete(ref)

        attempt(self._firebase.get_db().transaction())

    # Payment state changes (claim / confirm / reject / mark paid) live in PaymentRepository, because each
    # one must also create a notification in the same transaction.
