from pydantic import BaseModel


class PasswordResetRequestBody(BaseModel):
    email: str


class PasswordResetVerifyBody(BaseModel):
    email: str
    code: str


class PasswordResetCompleteBody(BaseModel):
    resetToken: str
    newPassword: str


class PasswordResetRequestResult(BaseModel):
    success: bool
    message: str


class PasswordResetVerifyResult(BaseModel):
    verified: bool
    resetToken: str


class PasswordResetCompleteResult(BaseModel):
    success: bool
