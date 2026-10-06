from typing import List

from fastapi import APIRouter, Depends, HTTPException

from auth.auth_service import get_current_uid
from dependencies.group_dependencies import notification_service
from models.notification_models import MarkAllReadResult, NotificationDeleted, NotificationOut, UnreadCount

router = APIRouter(prefix="/notifications", tags=["notifications"])


@router.get("", response_model=List[NotificationOut])
def get_notifications(uid: str = Depends(get_current_uid)):
    return notification_service.list_notifications(uid)


@router.get("/unread-count", response_model=UnreadCount)
def get_unread_count(uid: str = Depends(get_current_uid)):
    return {"count": notification_service.unread_count(uid)}


@router.patch("/read-all", response_model=MarkAllReadResult)
def mark_all_notifications_read(uid: str = Depends(get_current_uid)):
    return {"updated": notification_service.mark_all_read(uid)}


@router.patch("/{notification_id}/read", response_model=NotificationOut)
def mark_notification_read(notification_id: str, uid: str = Depends(get_current_uid)):
    notification = notification_service.mark_read(uid, notification_id)
    if notification is None:
        raise HTTPException(status_code=404, detail=f"Notification '{notification_id}' not found")
    return notification


@router.delete("/{notification_id}", response_model=NotificationDeleted)
def delete_notification(notification_id: str, uid: str = Depends(get_current_uid)):
    """Permanently delete one of the caller's own notifications.

    The recipient is always the authenticated user (never taken from the request), so a user cannot
    delete someone else's notification: it simply does not exist for them (404). The unread count is
    computed from the stored notifications, so deleting an unread one lowers it.
    """
    if not notification_service.delete_notification(uid, notification_id):
        raise HTTPException(status_code=404, detail=f"Notification '{notification_id}' not found")
    return {"success": True, "notificationId": notification_id}
