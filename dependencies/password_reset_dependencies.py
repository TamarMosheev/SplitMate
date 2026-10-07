from firebase.firebase_service import firebase_service
from repositories.auth_user_repository import AuthUserRepository
from repositories.password_reset_repository import PasswordResetRepository
from services.email_provider import ResendEmailProvider
from services.password_reset_service import PasswordResetService

# Wired once: firebase -> repositories, Resend provider -> service.
password_reset_service = PasswordResetService(
    PasswordResetRepository(firebase_service),
    AuthUserRepository(firebase_service),
    ResendEmailProvider(),
)
