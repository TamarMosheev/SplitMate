from datetime import timedelta
from typing import List, Optional

from repositories.notification_repository import NotificationRepository
from repositories.user_repository import UserRepository
from services.settlement_service import SettlementNotFoundError, SettlementService, stale_settlement_message

REMINDER_COOLDOWN = timedelta(hours=24)
FALLBACK_NAME = "מישהו"  # used when the sender has no name in their profile


class SettlementAlreadyPaidError(Exception):
    """The settlement has already been marked as paid."""


class ReminderNotAllowedError(Exception):
    """The caller is not the creditor of the settlement."""


class InvalidReminderStateError(Exception):
    """The settlement exists but a reminder cannot be sent for it."""


class ReminderCooldownError(Exception):
    """A reminder for this settlement was already sent within the cooldown."""


class NotificationService:
    """Business rules for debt reminders and the in-app notification inbox."""

    def __init__(
        self,
        notification_repository: NotificationRepository,
        user_repository: UserRepository,
        settlement_service: SettlementService,
    ):
        self._notifications = notification_repository
        self._users = user_repository
        self._settlements = settlement_service

    @staticmethod
    def _format_amount(amount: float) -> str:
        return f"{int(amount)}" if float(amount).is_integer() else f"{amount:.2f}"

    def send_debt_reminder(self, group: dict, settlement_id: str, caller_uid: str) -> dict:
        """Create a reminder for the debtor of an open settlement. Only the creditor may send it.

        Everything except the caller's uid is resolved server-side from the stored settlement.
        """
        settlement = self._settlements.get_settlement_by_id(group["id"], settlement_id)
        if settlement is None:
            raise SettlementNotFoundError(stale_settlement_message(group["id"], settlement_id))
        if settlement["status"] != "open":
            raise SettlementAlreadyPaidError("This settlement has already been paid")
        if settlement["to"] != caller_uid:
            raise ReminderNotAllowedError("Only the person owed money can send a reminder for this debt")

        recipient_uid = settlement["from"]
        if recipient_uid not in group.get("memberIds", []):
            raise InvalidReminderStateError("The debtor is no longer a member of this group")

        sender_name = self._users.get_display_name(caller_uid) or FALLBACK_NAME
        amount = settlement["amount"]
        data = {
            "type": "debt_reminder",
            "recipientUid": recipient_uid,
            "senderUid": caller_uid,
            "groupId": group["id"],
            "settlementId": settlement_id,
            "amount": amount,
            "message": f"{sender_name} שלח לך תזכורת לגבי חוב של ₪{self._format_amount(amount)}",
            "isRead": False,
            "readAt": None,
        }
        created = self._notifications.create_unless_recent(
            recipient_uid,
            data,
            same_as={"type": "debt_reminder", "senderUid": caller_uid,
                     "groupId": group["id"], "settlementId": settlement_id},
            cooldown=REMINDER_COOLDOWN,
        )
        if created is None:
            raise ReminderCooldownError("A reminder for this settlement was already sent recently")
        return created

    def list_notifications(self, uid: str) -> List[dict]:
        return self._notifications.list_for_user(uid)

    def unread_count(self, uid: str) -> int:
        return self._notifications.count_unread(uid)

    def mark_read(self, uid: str, notification_id: str) -> Optional[dict]:
        return self._notifications.mark_read(uid, notification_id)

    def mark_all_read(self, uid: str) -> int:
        return self._notifications.mark_all_read(uid)

    def delete_notification(self, uid: str, notification_id: str) -> bool:
        """Delete the caller's own notification. False if they have no such notification."""
        return self._notifications.delete(uid, notification_id)
