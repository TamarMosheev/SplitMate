from typing import Dict, Optional, Tuple

from pydantic import BaseModel, ConfigDict, StrictBool, field_validator, model_validator

# Stable, backend-owned ids. Android maps them to drawables/colors; never store resource ids.
ALLOWED_AVATAR_VALUES: Dict[str, Tuple[str, ...]] = {
    "hairStyle": tuple(f"hair_{n}" for n in range(1, 9)),
    "faceStyle": tuple(f"face_{n}" for n in range(1, 7)),
    "skinTone": tuple(f"skin_{n}" for n in range(1, 7)),
    "hairColor": ("black", "dark_brown", "brown_1", "brown_2", "brown_3", "blonde", "red", "gray"),
    "backgroundColor": (
        "light_blue", "light_green", "light_pink", "light_yellow", "light_purple", "light_orange", "light_gray",
    ),
}

DEFAULT_AVATAR = {
    "hairStyle": "hair_1",
    "faceStyle": "face_1",
    "glassesEnabled": False,
    "skinTone": "skin_3",
    "hairColor": "brown_2",
    "backgroundColor": "light_blue",
}


class Avatar(BaseModel):
    """A user's saved avatar (stored in users/{uid}.avatar)."""

    hairStyle: str
    faceStyle: str
    glassesEnabled: bool
    skinTone: str
    hairColor: str
    backgroundColor: str


class AvatarUpdate(BaseModel):
    """PATCH body: any subset of the avatar fields; omitted fields keep their current value."""

    model_config = ConfigDict(extra="forbid")

    hairStyle: Optional[str] = None
    faceStyle: Optional[str] = None
    glassesEnabled: Optional[StrictBool] = None
    skinTone: Optional[str] = None
    hairColor: Optional[str] = None
    backgroundColor: Optional[str] = None

    @field_validator("hairStyle", "faceStyle", "skinTone", "hairColor", "backgroundColor")
    @classmethod
    def _must_be_allowed(cls, value, info):
        if value is not None and value not in ALLOWED_AVATAR_VALUES[info.field_name]:
            allowed = ", ".join(ALLOWED_AVATAR_VALUES[info.field_name])
            raise ValueError(f"must be one of: {allowed}")
        return value

    @model_validator(mode="after")
    def _at_least_one_field(self):
        if not self.model_dump(exclude_none=True):
            raise ValueError("at least one avatar field must be provided")
        return self


class UserMe(BaseModel):
    """The signed-in user's own profile. No Firebase Auth data."""

    uid: str
    name: Optional[str] = None
    email: Optional[str] = None
    avatar: Avatar
