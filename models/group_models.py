from pydantic import BaseModel


class GroupDeleted(BaseModel):
    success: bool
    groupId: str
