from fastapi import APIRouter, BackgroundTasks, HTTPException
from fastapi.responses import JSONResponse

from dependencies.password_reset_dependencies import password_reset_service
from models.password_reset_models import (
    PasswordResetCompleteBody,
    PasswordResetCompleteResult,
    PasswordResetRequestBody,
    PasswordResetRequestResult,
    PasswordResetVerifyBody,
    PasswordResetVerifyResult,
)
from services.password_reset_service import (
    GENERIC_REQUEST_MESSAGE,
    InvalidCodeError,
    InvalidEmailError,
    InvalidResetTokenError,
    PasswordResetUnavailableError,
    ResendCooldownError,
    ResetTokenUsedError,
    TooManyAttemptsError,
    WeakPasswordError,
)

# Public endpoints: the caller is not logged in, so there is no Authorization header.
router = APIRouter(prefix="/auth/password-reset", tags=["password-reset"])


def _send_code(body: PasswordResetRequestBody, background_tasks: BackgroundTasks):
    try:
        password_reset_service.request_code(body.email, background_tasks.add_task)
    except InvalidEmailError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except ResendCooldownError as exc:
        return JSONResponse(
            status_code=429,
            content={"detail": str(exc)},
            headers={"Retry-After": str(exc.retry_after_seconds)},
        )
    except PasswordResetUnavailableError:
        raise HTTPException(status_code=500, detail="Password reset is temporarily unavailable")
    return {"success": True, "message": GENERIC_REQUEST_MESSAGE}


@router.post("/request", response_model=PasswordResetRequestResult)
def request_password_reset(body: PasswordResetRequestBody, background_tasks: BackgroundTasks):
    return _send_code(body, background_tasks)


@router.post("/resend", response_model=PasswordResetRequestResult)
def resend_password_reset(body: PasswordResetRequestBody, background_tasks: BackgroundTasks):
    """Same as /request: replaces the previous code (which stops working), 60 s cooldown."""
    return _send_code(body, background_tasks)


@router.post("/verify", response_model=PasswordResetVerifyResult)
def verify_password_reset(body: PasswordResetVerifyBody):
    try:
        token = password_reset_service.verify_code(body.email, body.code)
    except InvalidCodeError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except TooManyAttemptsError as exc:
        raise HTTPException(status_code=429, detail=str(exc))
    return {"verified": True, "resetToken": token}


@router.post("/complete", response_model=PasswordResetCompleteResult)
def complete_password_reset(body: PasswordResetCompleteBody):
    try:
        password_reset_service.complete(body.resetToken, body.newPassword)
    except WeakPasswordError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except InvalidResetTokenError as exc:
        raise HTTPException(status_code=401, detail=str(exc))
    except ResetTokenUsedError as exc:
        raise HTTPException(status_code=409, detail=str(exc))
    return {"success": True}
