from typing import List

from fastapi import APIRouter, Depends, HTTPException, Query

from auth.auth_service import get_current_uid
from dependencies.group_dependencies import group_service, user_service
from models.group_models import UserPublic
from models.user_models import AvatarUpdate, UserMe
from services.group_service import InvalidMemberRequestError
from services.user_service import UserProfileNotFoundError

router = APIRouter(prefix="/users", tags=["users"])


@router.get("/search", response_model=List[UserPublic], response_model_exclude_none=True)
def search_users(query: str = Query(default=""), uid: str = Depends(get_current_uid)):
    """Search registered SplitMate users by name or email (min 2 characters). Public fields only."""
    try:
        return group_service.search_users(query)
    except InvalidMemberRequestError as error:
        raise HTTPException(status_code=400, detail=str(error))


@router.get("/me", response_model=UserMe, response_model_exclude_none=True)
def get_me(uid: str = Depends(get_current_uid)):
    """The signed-in user's profile: uid, name, email and avatar (a default avatar if none is saved)."""
    try:
        return user_service.get_me(uid)
    except UserProfileNotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error))


@router.patch("/me/avatar", response_model=UserMe, response_model_exclude_none=True)
def update_my_avatar(body: AvatarUpdate, uid: str = Depends(get_current_uid)):
    """Update the signed-in user's own avatar. Send any subset of the fields; the rest keep their value.

    There is no user id in the path: only the authenticated user's avatar can be changed. Only the
    `avatar` field of users/{uid} is written. 404: no profile document. 422: invalid value.
    """
    try:
        return user_service.update_avatar(uid, body.model_dump(exclude_none=True))
    except UserProfileNotFoundError as error:
        raise HTTPException(status_code=404, detail=str(error))
