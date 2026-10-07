import json
import logging
import os
import urllib.error
import urllib.request
from abc import ABC, abstractmethod

logger = logging.getLogger(__name__)


class EmailNotConfiguredError(Exception):
    """The email provider's required environment variables are missing."""


class EmailDeliveryError(Exception):
    """The provider rejected the message or could not be reached."""


class EmailProvider(ABC):
    """Sends transactional email. Swap the implementation to change provider."""

    @abstractmethod
    def ensure_configured(self) -> None:
        """Raise EmailNotConfiguredError if the provider cannot send."""

    @abstractmethod
    def send(self, to: str, subject: str, html: str, text: str) -> None:
        """Send one message, or raise EmailNotConfiguredError / EmailDeliveryError."""


class ResendEmailProvider(EmailProvider):
    """Resend (https://resend.com) over its REST API.

    Environment: RESEND_API_KEY and PASSWORD_RESET_FROM_EMAIL (a sender on a Resend-verified
    domain, e.g. "SplitMate <no-reply@yourdomain.com>"). Read on every call, never hardcoded.
    """

    API_URL = "https://api.resend.com/emails"
    TIMEOUT_SECONDS = 10

    @staticmethod
    def _settings() -> tuple:
        api_key = os.environ.get("RESEND_API_KEY", "").strip()
        sender = os.environ.get("PASSWORD_RESET_FROM_EMAIL", "").strip()
        missing = [
            name
            for name, value in (("RESEND_API_KEY", api_key), ("PASSWORD_RESET_FROM_EMAIL", sender))
            if not value
        ]
        if missing:
            raise EmailNotConfiguredError(f"Missing environment variable(s): {', '.join(missing)}")
        return api_key, sender

    def ensure_configured(self) -> None:
        self._settings()

    def send(self, to: str, subject: str, html: str, text: str) -> None:
        api_key, sender = self._settings()
        payload = json.dumps({"from": sender, "to": [to], "subject": subject, "html": html, "text": text})
        request = urllib.request.Request(
            self.API_URL,
            data=payload.encode("utf-8"),
            method="POST",
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
                "User-Agent": "splitmate-backend/1.0",  # Resend's edge rejects the default Python UA
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.TIMEOUT_SECONDS):
                pass
        except urllib.error.HTTPError as exc:
            # Body only: it never contains our key. Never log the message content (it holds the code).
            detail = exc.read().decode("utf-8", "replace")[:300]
            raise EmailDeliveryError(f"Resend responded {exc.code}: {detail}") from None
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise EmailDeliveryError(f"Could not reach Resend: {exc}") from None
