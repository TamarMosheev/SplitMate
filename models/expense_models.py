from typing import Dict, List, Literal, Optional

from pydantic import BaseModel, Field, field_validator, model_validator

from utils.money import is_whole_cents, to_cents


class ExpenseCreate(BaseModel):
    amount: float = Field(gt=0)
    description: str
    paidBy: str
    splitType: Literal["equal", "exact"]
    participantIds: List[str] = Field(min_length=1)
    exactAmounts: Optional[Dict[str, float]] = None

    @field_validator("description", "paidBy")
    @classmethod
    def not_blank(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("must not be empty")
        return value

    @field_validator("amount")
    @classmethod
    def amount_in_whole_cents(cls, value: float) -> float:
        if not is_whole_cents(value):
            raise ValueError("must have at most 2 decimal places")
        return value

    @field_validator("exactAmounts")
    @classmethod
    def exact_amounts_in_whole_cents(cls, value: Optional[Dict[str, float]]):
        if value and not all(is_whole_cents(amount) for amount in value.values()):
            raise ValueError("every amount must have at most 2 decimal places")
        return value

    @model_validator(mode="after")
    def check_exact_amounts(self):
        if self.splitType == "exact":
            if not self.exactAmounts:
                raise ValueError("exactAmounts is required when splitType is 'exact'")
            if sum(to_cents(v) for v in self.exactAmounts.values()) != to_cents(self.amount):
                raise ValueError("sum of exactAmounts must equal amount")
        return self
