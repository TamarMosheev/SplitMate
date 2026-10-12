from typing import Optional

from pydantic import BaseModel

from models.user_models import Avatar


class GroupDeleted(BaseModel):
    success: bool
    groupId: str


class GroupLeft(BaseModel):
    success: bool
    groupId: str
    leftGroup: bool


class AddMemberRequest(BaseModel):
    userId: str


class UserPublic(BaseModel):
    """Safe public profile of a registered user."""

    uid: str
    displayName: Optional[str] = None
    email: Optional[str] = None
    photoUrl: Optional[str] = None
    avatar: Avatar
