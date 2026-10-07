"""Offline tests for ResendEmailProvider (no network). Run: python tests/test_email_provider.py"""
import json
import os
import sys
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from services.email_provider import EmailDeliveryError, EmailNotConfiguredError, ResendEmailProvider  # noqa: E402

ENV = {"RESEND_API_KEY": "re_test_key", "PASSWORD_RESET_FROM_EMAIL": "SplitMate <no-reply@example.com>"}


class ResendProviderTest(unittest.TestCase):
    def test_missing_env_fails_clearly(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(EmailNotConfiguredError) as ctx:
                ResendEmailProvider().ensure_configured()
            self.assertIn("RESEND_API_KEY", str(ctx.exception))
            with self.assertRaises(EmailNotConfiguredError):
                ResendEmailProvider().send("a@b.co", "s", "<p>h</p>", "t")

    def test_sends_expected_request(self):
        with mock.patch.dict(os.environ, ENV, clear=True), mock.patch("urllib.request.urlopen") as urlopen:
            ResendEmailProvider().send("a@b.co", "subj", "<p>h</p>", "t")
        request = urlopen.call_args[0][0]
        self.assertEqual(request.full_url, "https://api.resend.com/emails")
        self.assertEqual(request.get_header("Authorization"), "Bearer re_test_key")
        body = json.loads(request.data)
        self.assertEqual(body["to"], ["a@b.co"])
        self.assertEqual(body["from"], ENV["PASSWORD_RESET_FROM_EMAIL"])

    def test_http_error_becomes_delivery_error_without_key(self):
        err = urllib.error.HTTPError("u", 403, "Forbidden", {}, mock.Mock(read=lambda: b'{"message":"bad domain"}'))
        with mock.patch.dict(os.environ, ENV, clear=True), mock.patch("urllib.request.urlopen", side_effect=err):
            with self.assertRaises(EmailDeliveryError) as ctx:
                ResendEmailProvider().send("a@b.co", "s", "h", "t")
        self.assertIn("403", str(ctx.exception))
        self.assertNotIn("re_test_key", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
