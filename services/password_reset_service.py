import hashlib
import hmac
import logging
import os
import re
import secrets
from datetime import datetime, timedelta, timezone
from typing import Callable, Optional

from repositories.auth_user_repository import AuthUserRepository
from repositories.password_reset_repository import PasswordResetRepository
from services.email_provider import EmailDeliveryError, EmailNotConfiguredError, EmailProvider

logger = logging.getLogger(__name__)

OTP_TTL = timedelta(minutes=10)
RESET_TOKEN_TTL = timedelta(minutes=10)
RESEND_COOLDOWN = timedelta(seconds=60)
PURGE_GRACE = timedelta(hours=1)  # how long after expiry a request document is kept before cleanup
MAX_FAILED_ATTEMPTS = 5
MIN_PASSWORD_LENGTH = 8
MAX_PASSWORD_LENGTH = 128
GENERIC_REQUEST_MESSAGE = "If the account exists, a verification code was sent."

_EMAIL_PATTERN = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


class InvalidEmailError(Exception):
    """400: the email address is malformed."""


class WeakPasswordError(Exception):
    """400: the new password breaks the password policy."""


class InvalidCodeError(Exception):
    """400: unknown, expired, used or wrong verification code (deliberately not distinguished)."""


class TooManyAttemptsError(Exception):
    """429: the request has used up its failed attempts."""


class ResendCooldownError(Exception):
    """429: a code was sent less than RESEND_COOLDOWN ago."""

    def __init__(self, retry_after_seconds: int):
        super().__init__(f"Please wait {retry_after_seconds} seconds before requesting another code")
        self.retry_after_seconds = retry_after_seconds


class InvalidResetTokenError(Exception):
    """401: the reset token is unknown, malformed, unverified or expired."""


class ResetTokenUsedError(Exception):
    """409: the reset token was already used."""


class PasswordResetUnavailableError(Exception):
    """500: the server cannot currently send reset emails (details are logged, not exposed)."""


def normalize_email(email: str) -> str:
    return (email or "").strip().lower()


def validate_email(email: str) -> str:
    email = normalize_email(email)
    if len(email) > 254 or not _EMAIL_PATTERN.match(email):
        raise InvalidEmailError("Invalid email address")
    return email


def validate_password(password: str) -> None:
    if len(password) < MIN_PASSWORD_LENGTH:
        raise WeakPasswordError(f"Password must be at least {MIN_PASSWORD_LENGTH} characters long")
    if len(password) > MAX_PASSWORD_LENGTH:
        raise WeakPasswordError(f"Password must be at most {MAX_PASSWORD_LENGTH} characters long")
    if not any(c.isalpha() for c in password):
        raise WeakPasswordError("Password must contain at least one letter")
    if not any(c.isdigit() for c in password):
        raise WeakPasswordError("Password must contain at least one number")


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _aware(value: datetime) -> datetime:
    return value if value.tzinfo else value.replace(tzinfo=timezone.utc)


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class PasswordResetService:
    """OTP-based password reset: request code -> verify code -> complete with a one-time token."""

    def __init__(
        self,
        repository: PasswordResetRepository,
        users: AuthUserRepository,
        email_provider: EmailProvider,
        clock: Callable[[], datetime] = _now,
    ):
        self._repo = repository
        self._users = users
        self._email = email_provider
        self._clock = clock

    # -- hashing ---------------------------------------------------------------------------

    @staticmethod
    def request_id_for(email: str) -> str:
        return _sha256(email)[:40]

    @staticmethod
    def _hash_code(code: str, salt: str, request_id: str) -> str:
        """HMAC-SHA256 of the code, bound to its request and salt.

        PASSWORD_RESET_PEPPER (optional, server-side secret) makes an offline guess of the 6 digits
        impossible even if the database leaks.
        """
        key = (os.environ.get("PASSWORD_RESET_PEPPER", "") + salt).encode("utf-8")
        return hmac.new(key, f"{request_id}:{code}".encode("utf-8"), hashlib.sha256).hexdigest()

    # -- 1. request / resend ---------------------------------------------------------------

    def request_code(self, email: str, schedule: Callable) -> None:
        """Create and email a code. Always looks identical to the caller, account or not.

        Raises InvalidEmailError, ResendCooldownError, PasswordResetUnavailableError.
        The email is sent via `schedule(fn, *args)` (a background task) so response time does not
        reveal whether an account exists.
        """
        email = validate_email(email)
        try:
            self._email.ensure_configured()
        except EmailNotConfiguredError as exc:
            logger.error("Password reset unavailable: %s", exc)
            raise PasswordResetUnavailableError() from None

        uid = self._users.find_password_user_uid(email)
        request_id = self.request_id_for(email)
        now = self._clock()
        code = f"{secrets.randbelow(1_000_000):06d}"
        salt = secrets.token_hex(16)

        def mutate(data: Optional[dict]):
            if data and data.get("lastSentAt"):
                wait = _aware(data["lastSentAt"]) + RESEND_COOLDOWN - now
                if wait.total_seconds() > 0:
                    return None, int(wait.total_seconds()) + 1
            fields = {
                "uid": uid,
                "email": email,
                # Unknown email: only a cooldown marker is stored (no code, already "used"), so the
                # 429 behaviour is identical and nothing can be verified against it.
                "otpHash": self._hash_code(code, salt, request_id) if uid else None,
                "otpSalt": salt,
                "createdAt": now,
                "lastSentAt": now,
                "expiresAt": now + OTP_TTL,
                "purgeAfter": now + OTP_TTL + PURGE_GRACE,
                "attempts": 0,
                "verified": False,
                "used": uid is None,
                "resetTokenHash": None,
                "resetTokenExpiresAt": None,
            }
            return fields, 0

        retry_after = self._repo.transact(request_id, mutate)
        if retry_after:
            raise ResendCooldownError(retry_after)

        if uid:
            schedule(self._deliver, email, code)
        schedule(self._cleanup)

    def _deliver(self, email: str, code: str) -> None:
        subject = "קוד אימות לאיפוס סיסמה – SplitMate"
        minutes = int(OTP_TTL.total_seconds() // 60)
        html = (
            '<div dir="rtl" style="font-family:Arial,sans-serif;text-align:right">'
            "<h2>איפוס סיסמה ב-SplitMate</h2>"
            "<p>קוד האימות שלך הוא:</p>"
            f'<p style="font-size:32px;letter-spacing:6px;font-weight:bold">{code}</p>'
            f"<p>הקוד תקף ל-{minutes} דקות. אם לא ביקשת לאפס סיסמה, אפשר להתעלם מהמייל הזה.</p>"
            "</div>"
        )
        text = (
            f"קוד האימות שלך ל-SplitMate: {code}\n"
            f"הקוד תקף ל-{minutes} דקות. אם לא ביקשת לאפס סיסמה, התעלם/י מהמייל.\n\n"
            f"Your SplitMate verification code is {code}. It expires in {minutes} minutes."
        )
        try:
            self._email.send(email, subject, html, text)
        except (EmailNotConfiguredError, EmailDeliveryError) as exc:
            logger.error("Password reset email was not delivered: %s", exc)

    def _cleanup(self) -> None:
        try:
            self._repo.delete_stale(self._clock())
        except Exception:  # best-effort; expired requests are ignored anyway
            logger.warning("Password reset cleanup failed", exc_info=True)

    # -- 2. verify -------------------------------------------------------------------------

    def verify_code(self, email: str, code: str) -> str:
        """Check the code and return a single-use reset token. Raises InvalidCodeError / TooManyAttemptsError."""
        try:
            email = validate_email(email)
        except InvalidEmailError:
            raise InvalidCodeError("Invalid or expired verification code") from None
        code = (code or "").strip()
        request_id = self.request_id_for(email)
        now = self._clock()
        secret = secrets.token_urlsafe(32)
        token_expires = now + RESET_TOKEN_TTL

        def mutate(data: Optional[dict]):
            if (
                not data
                or not data.get("uid")
                or data.get("used")
                or data.get("verified")
                or not data.get("otpHash")
                or _aware(data["expiresAt"]) <= now
            ):
                return None, "invalid"
            if data.get("attempts", 0) >= MAX_FAILED_ATTEMPTS:
                return None, "locked"
            expected = self._hash_code(code, data["otpSalt"], request_id)
            if re.fullmatch(r"\d{6}", code) and hmac.compare_digest(expected, data["otpHash"]):
                return {
                    "verified": True,
                    "verifiedAt": now,
                    "otpHash": None,  # the code cannot be replayed
                    "resetTokenHash": _sha256(secret),
                    "resetTokenExpiresAt": token_expires,
                    "purgeAfter": token_expires + PURGE_GRACE,
                }, "ok"
            return {"attempts": data.get("attempts", 0) + 1}, "wrong"

        outcome = self._repo.transact(request_id, mutate)
        if outcome == "ok":
            return f"{request_id}.{secret}"
        if outcome == "locked":
            raise TooManyAttemptsError("Too many failed attempts. Request a new code.")
        raise InvalidCodeError("Invalid or expired verification code")

    # -- 3. complete -----------------------------------------------------------------------

    def complete(self, reset_token: str, new_password: str) -> None:
        """Consume the reset token and set the new password."""
        validate_password(new_password)
        request_id, _, secret = (reset_token or "").partition(".")
        if not request_id or not secret:
            raise InvalidResetTokenError("Invalid or expired reset token")
        token_hash = _sha256(secret)
        now = self._clock()

        def mutate(data: Optional[dict]):
            if (
                not data
                or not data.get("uid")
                or not data.get("resetTokenHash")
                or not hmac.compare_digest(data["resetTokenHash"], token_hash)
            ):
                return None, ("invalid", None)
            if data.get("used"):
                return None, ("used", None)
            if not data.get("verified") or _aware(data["resetTokenExpiresAt"]) <= now:
                return None, ("invalid", None)
            return {"used": True, "usedAt": now}, ("ok", data["uid"])

        outcome, uid = self._repo.transact(request_id, mutate)
        if outcome == "used":
            raise ResetTokenUsedError("This reset token was already used")
        if outcome != "ok":
            raise InvalidResetTokenError("Invalid or expired reset token")

        try:
            self._users.update_password(uid, new_password)
        except ValueError as exc:  # Firebase rejected the password: let the user retry with the same token
            self._repo.update(request_id, {"used": False})
            raise WeakPasswordError("Password was rejected. Choose a different password.") from exc
        except Exception:
            self._repo.update(request_id, {"used": False})
            raise

        self._repo.update(request_id, {"resetTokenHash": None, "otpHash": None})
        try:
            self._users.revoke_sessions(uid)
        except Exception:
            logger.warning("Could not revoke sessions after password reset for uid %s", uid, exc_info=True)
