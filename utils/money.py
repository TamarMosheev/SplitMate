"""Money helpers. All balance/settlement arithmetic is done in integer cents.

Floats only exist at the edges: values read from Firestore/JSON are converted to cents with
Decimal (never with float math), and cents are converted back to a float amount only when
building a response or a stored document.
"""
import zlib
from decimal import ROUND_HALF_UP, Decimal
from typing import Dict, Iterable


def to_cents(value) -> int:
    """Convert a money amount (float, int, str or Decimal) to integer cents, rounding half up."""
    return int((Decimal(str(value)) * 100).to_integral_value(rounding=ROUND_HALF_UP))


def is_whole_cents(value) -> bool:
    """True if the amount has at most 2 decimal places."""
    amount = Decimal(str(value))
    return amount == amount.quantize(Decimal("0.01"))


def cents_to_amount(cents: int) -> float:
    """Integer cents -> the amount as a float (exact decimal text, so 11667 -> 116.67)."""
    return float(Decimal(cents) / 100)


def format_cents(cents: int) -> str:
    """Integer cents -> display text without a trailing '.00': 11666 -> '116.66', 20000 -> '200'."""
    whole, fraction = divmod(abs(cents), 100)
    sign = "-" if cents < 0 else ""
    return f"{sign}{whole}" if fraction == 0 else f"{sign}{whole}.{fraction:02d}"


def split_cents_equally(total_cents: int, participants: Iterable[str], seed: str) -> Dict[str, int]:
    """Split `total_cents` between the participants so the shares add up to exactly the total.

    Every share is the floor of total/n; the leftover cents (fewer than n) go one each to
    consecutive participants. Participants are put in sorted order and the starting point is
    derived from `seed` (the expense id), so the result is deterministic - the same expense
    always splits the same way - while the extra cents are spread fairly across expenses.
    """
    ordered = sorted(set(participants))
    base, remainder = divmod(total_cents, len(ordered))
    start = zlib.crc32(seed.encode("utf-8")) % len(ordered)
    shares = {user_id: base for user_id in ordered}
    for i in range(remainder):
        shares[ordered[(start + i) % len(ordered)]] += 1
    return shares
