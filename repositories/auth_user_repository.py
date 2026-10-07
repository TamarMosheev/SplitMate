from typing import Optional

from firebase_admin import auth

from firebase.firebase_service import FirebaseService


class AuthUserRepository:
    """Firebase Authentication user lookups and updates (Firebase Admin)."""

    def __init__(self, firebase: FirebaseService):
        self._firebase = firebase

    def find_password_user_uid(self, email: str) -> Optional[str]:
        """uid of an enabled email/password user with this email, else None."""
        self._firebase.initialize()
        try:
            user = auth.get_user_by_email(email)
        except (auth.UserNotFoundError, ValueError):
            return None
        if user.disabled:
            return None
        if not any(p.provider_id == "password" for p in user.provider_data):
            return None
        return user.uid

    def update_password(self, uid: str, new_password: str) -> None:
        """Set a new password (ValueError if Firebase rejects it)."""
        self._firebase.initialize()
        auth.update_user(uid, password=new_password)

    def revoke_sessions(self, uid: str) -> None:
        self._firebase.initialize()
        auth.revoke_refresh_tokens(uid)
