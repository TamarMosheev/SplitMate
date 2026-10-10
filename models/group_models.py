from typing import Optional

from pydantic import BaseModel


class GroupDeleted(BaseModel):
    success: bool
    groupId: str


class AddMemberRequest(BaseModel):
    userId: str


class UserPublic(BaseModel):
    """Safe public profile of a registered user."""

    uid: str
    displayName: Optional[str] = None
    email: Optional[str] = None
    photoUrl: Optional[str] = None
