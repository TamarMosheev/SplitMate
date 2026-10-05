from typing import Optional

from fastapi import Header, HTTPException
from firebase_admin import auth

from firebase.firebase_service import FirebaseService, firebase_service


class AuthService:
    """Verifies Firebase ID tokens sent as 'Authorization: Bearer <token>'."""

    def __init__(self, firebase: FirebaseService):
        self._firebase = firebase

    def get_uid_from_header(self, authorization: Optional[str]) -> str:
        unauthorized = {"WWW-Authenticate": "Bearer"}

        scheme, _, token = (authorization or "").partition(" ")
        token = token.strip()
        if scheme.lower() != "bearer" or not token:
            raise HTTPException(
                status_code=401,
                detail="Missing or malformed Authorization header. Expected: Bearer <firebase_id_token>",
                headers=unauthorized,
            )

        self._firebase.initialize()  # makes sure the Firebase Admin app is initialized
        try:
            return auth.verify_id_token(token)["uid"]
        except (ValueError, auth.InvalidIdTokenError):  # also covers expired tokens
            raise HTTPException(
                status_code=401,
                detail="Invalid or expired Firebase ID token",
                headers=unauthorized,
            )


auth_service = AuthService(firebase_service)


def get_current_uid(authorization: Optional[str] = Header(default=None)) -> str:
    """FastAPI dependency: returns the authenticated user's uid or raises 401."""
    return auth_service.get_uid_from_header(authorization)
