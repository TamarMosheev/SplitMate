from typing import Dict, List, Literal, Optional

from pydantic import BaseModel, Field, field_validator, model_validator


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

    @model_validator(mode="after")
    def check_exact_amounts(self):
        if self.splitType == "exact":
            if not self.exactAmounts:
                raise ValueError("exactAmounts is required when splitType is 'exact'")
            if round(sum(self.exactAmounts.values()), 2) != round(self.amount, 2):
                raise ValueError("sum of exactAmounts must equal amount")
        return self
