from typing import List

from fastapi import APIRouter, Depends, HTTPException, Query

from auth.auth_service import get_current_uid
from dependencies.group_dependencies import group_service
from models.group_models import UserPublic
from services.group_service import InvalidMemberRequestError

router = APIRouter(prefix="/users", tags=["users"])


@router.get("/search", response_model=List[UserPublic], response_model_exclude_none=True)
def search_users(query: str = Query(default=""), uid: str = Depends(get_current_uid)):
    """Search registered SplitMate users by name or email (min 2 characters). Public fields only."""
    try:
        return group_service.search_users(query)
    except InvalidMemberRequestError as error:
        raise HTTPException(status_code=400, detail=str(error))
