from repositories.user_repository import UserRepository


class UserProfileNotFoundError(Exception):
    """There is no users/{uid} profile for the authenticated user."""


class UserService:
    """The signed-in user's own profile and avatar."""

    def __init__(self, user_repository: UserRepository):
        self._users = user_repository

    def get_me(self, uid: str) -> dict:
        profile = self._users.get_own_profile(uid)
        if profile is None:
            raise UserProfileNotFoundError("User profile not found")
        return profile

    def update_avatar(self, uid: str, changes: dict) -> dict:
        """Update the caller's own avatar (only fields present in `changes`); returns the full profile."""
        if self._users.update_avatar(uid, changes) is None:
            raise UserProfileNotFoundError("User profile not found")
        return self.get_me(uid)
