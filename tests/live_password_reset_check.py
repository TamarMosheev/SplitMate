"""Live end-to-end check of the password-reset flow against the real Firebase project.

Creates a throwaway Firebase Auth user, drives the HTTP API through FastAPI's TestClient, and
deletes the user and its reset document at the end. The email provider is replaced by a capturing
fake so the code can be read without an inbox (Resend itself is covered by test_resend_provider).

Run from the repo root:  python tests/live_password_reset_check.py
"""
import json
import re
import secrets
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests
from firebase_admin import auth
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import main  # noqa: E402
from dependencies.password_reset_dependencies import password_reset_service as service  # noqa: E402
from firebase.firebase_service import firebase_service  # noqa: E402
from services.email_provider import EmailProvider  # noqa: E402


class CapturingProvider(EmailProvider):
    def __init__(self):
        self.sent = []

    def ensure_configured(self):
        pass

    def send(self, to, subject, html, text):
        self.sent.append((to, re.search(r"\b(\d{6})\b", text).group(1)))


API_KEY = json.loads((ROOT / "app" / "google-services.json").read_text(encoding="utf-8"))["client"][0][
    "api_key"
][0]["current_key"]
firebase_service.initialize()
fake = CapturingProvider()
service._email = fake
client = TestClient(main.app)

EMAIL = f"splitmate.pwreset.test.{secrets.token_hex(4)}@example.com"
OLD, NEW = "OldPass123", "NewPass456"
URL = "/auth/password-reset"
results = []


def check(name, condition, info=""):
    results.append(bool(condition))
    print(f"[{'PASS' if condition else 'FAIL'}] {name} {info}")


def sign_in(password):
    r = requests.post(
        f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key={API_KEY}",
        json={"email": EMAIL, "password": password, "returnSecureToken": True},
        timeout=20,
    )
    return r.status_code == 200


def last_code():
    return fake.sent[-1][1]


def rewind_last_sent():
    service._repo.update(rid, {"lastSentAt": datetime.now(timezone.utc) - timedelta(minutes=2)})


user = auth.create_user(email=EMAIL, password=OLD)
rid = service.request_id_for(EMAIL)
ghost = f"nobody.{secrets.token_hex(4)}@example.com"
try:
    check("precondition: old password signs in", sign_in(OLD))

    # 1 + 2. known and unknown email give the same generic response
    r1 = client.post(f"{URL}/request", json={"email": EMAIL})
    r2 = client.post(f"{URL}/request", json={"email": ghost})
    check("1. valid email -> 200 generic + code emailed", r1.status_code == 200 and len(fake.sent) == 1, r1.json())
    check("2. unknown email -> identical response, nothing sent", r2.json() == r1.json() and len(fake.sent) == 1)
    check("2b. malformed email -> 400", client.post(f"{URL}/request", json={"email": "nope"}).status_code == 400)

    # 12. rate limit (uniform for known and unknown emails)
    r = client.post(f"{URL}/request", json={"email": EMAIL})
    g = client.post(f"{URL}/request", json={"email": ghost})
    check("12. resend within 60s -> 429 + Retry-After", r.status_code == 429 and "retry-after" in r.headers)
    check("12b. same 429 for unknown email", g.status_code == 429)
    check("stored doc has no plaintext code", last_code() not in json.dumps(service._repo.get(rid), default=str))

    # 3 + 4. wrong code, then lockout after 5
    good = last_code()
    bad = "000000" if good != "000000" else "111111"
    codes = [client.post(f"{URL}/verify", json={"email": EMAIL, "code": bad}).status_code for _ in range(5)]
    check("3. wrong OTP rejected (x5 -> 400)", codes == [400] * 5, codes)
    r = client.post(f"{URL}/verify", json={"email": EMAIL, "code": good})
    check("4. after 5 failures even the correct code is blocked (429)", r.status_code == 429, r.json())

    # 11. resend invalidates the old code
    rewind_last_sent()
    old_code = last_code()
    check("11. resend accepted after cooldown", client.post(f"{URL}/resend", json={"email": EMAIL}).status_code == 200)
    new_code = last_code()
    if new_code == old_code:  # 1-in-a-million collision: resend again so the test stays meaningful
        rewind_last_sent()
        client.post(f"{URL}/resend", json={"email": EMAIL})
        new_code = last_code()
    r = client.post(f"{URL}/verify", json={"email": EMAIL, "code": old_code})
    check("11b. old code no longer works", r.status_code == 400, r.json())

    # 5. expired code
    service._repo.update(rid, {"expiresAt": datetime.now(timezone.utc) - timedelta(seconds=1), "attempts": 0})
    r = client.post(f"{URL}/verify", json={"email": EMAIL, "code": new_code})
    check("5. expired OTP rejected", r.status_code == 400, r.json())

    # 6. correct code -> token
    rewind_last_sent()
    client.post(f"{URL}/request", json={"email": EMAIL})
    code = last_code()
    r = client.post(f"{URL}/verify", json={"email": EMAIL, "code": code})
    token = r.json().get("resetToken", "")
    check("6. correct OTP -> verified + resetToken", r.status_code == 200 and r.json()["verified"] and token, "")
    stored = json.dumps(service._repo.get(rid), default=str)
    check("6b. no plaintext token/code stored", token.split(".")[1] not in stored and code not in stored)
    check(
        "6c. OTP cannot be replayed after verification",
        client.post(f"{URL}/verify", json={"email": EMAIL, "code": code}).status_code == 400,
    )

    # 7-9. complete
    check("policy: short password -> 400", client.post(f"{URL}/complete", json={"resetToken": token, "newPassword": "a1"}).status_code == 400)
    check("policy: no digit -> 400", client.post(f"{URL}/complete", json={"resetToken": token, "newPassword": "abcdefghij"}).status_code == 400)
    check("bogus token -> 401", client.post(f"{URL}/complete", json={"resetToken": "x.y", "newPassword": NEW}).status_code == 401)
    r = client.post(f"{URL}/complete", json={"resetToken": token, "newPassword": NEW})
    check("7. valid new password -> success", r.status_code == 200 and r.json() == {"success": True}, r.json())
    check("8. old password no longer works", not sign_in(OLD))
    check("9. new password works", sign_in(NEW))

    # 10. replay
    r = client.post(f"{URL}/complete", json={"resetToken": token, "newPassword": "Another789"})
    check("10. reused resetToken rejected (409/401)", r.status_code in (401, 409), r.status_code)
    check("10b. password unchanged by replay", sign_in(NEW))
finally:
    auth.delete_user(user.uid)
    service._repo._collection().document(rid).delete()
    service._repo._collection().document(service.request_id_for(ghost)).delete()
    print("cleanup done")

print(f"\n{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
