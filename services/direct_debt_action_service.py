from typing import List, Optional, Tuple

from repositories.direct_claim_repository import DirectClaimRepository
from repositories.notification_repository import NotificationRepository
from repositories.payment_repository import PaymentRepository
from repositories.settlement_repository import SettlementRepository
from repositories.user_repository import UserRepository
from services.balance_service import BalanceService
from services.notification_service import FALLBACK_NAME, REMINDER_COOLDOWN
from repositories.group_repository import GroupRepository
from utils.money import cents_to_amount, format_cents, to_cents

DIRECT_KIND = "direct_debt"


class DirectDebtNotAllowedError(Exception):
    """The caller is not the creditor of this direct debt (403)."""


class DirectDebtNotFoundError(Exception):
    """No outstanding direct debt in the requested scope (404)."""


class DirectDebtConflictError(Exception):
    """Already paid, amount changed, reminder cooldown... (409)."""


class DirectDebtBadRequestError(Exception):
    """The request itself is not valid, e.g. several groups and no scope was chosen (400)."""


class DirectDebtActionService:
    """Mark-as-paid and reminders for a DIRECT debt (debtor, creditor), without any settlement id.

    The exact outstanding amount is always recomputed from the real expense history
    (BalanceService.calculate_direct_debts_cents); the client only identifies the pair and, optionally, the
    group. Only the authenticated creditor may act. A debt can span several groups, and payments are
    per group, so the scope is explicit and never guessed:
      - groupId given: that group only;
      - no groupId, debt in exactly one group: that group;
      - no groupId, several groups: reminders cover all of them in one notification; paying is rejected
        (400) unless `all_groups=True` explicitly asks for one payment per group.
    """

    def __init__(
        self,
        group_repository: GroupRepository,
        balance_service: BalanceService,
        settlement_repository: SettlementRepository,
        payment_repository: PaymentRepository,
        notification_repository: NotificationRepository,
        user_repository: UserRepository,
        claim_repository: DirectClaimRepository,
    ):
        self._claims = claim_repository
        self._groups = group_repository
        self._balances = balance_service
        self._settlements = settlement_repository
        self._payments = payment_repository
        self._notifications = notification_repository
        self._users = user_repository

    # ---------------------------------------------------------------- scope
    def _outstanding(
        self, caller: str, debtor: str, creditor: str, group_id: Optional[str], party: str = "creditor"
    ) -> List[Tuple[dict, int]]:
        """[(group, cents)] with debt > 0 among the caller's groups, from the real expenses.
        `party` is who may act: the creditor (pay / remind / confirm / reject) or the debtor (claim)."""
        if party == "creditor" and caller != creditor:
            raise DirectDebtNotAllowedError("Only the person owed money can act on this debt")
        if party == "debtor" and caller != debtor:
            raise DirectDebtNotAllowedError("Only the person who owes this debt can report paying it")
        if debtor == creditor:
            raise DirectDebtBadRequestError("A person cannot owe themselves")
        groups = self._groups.list_by_member(caller)
        if group_id is not None:
            groups = [g for g in groups if g["id"] == group_id]
            if not groups:
                raise DirectDebtNotFoundError(f"Group '{group_id}' not found or you are not a member of it")
        found = []
        for group in sorted(groups, key=lambda g: g["id"]):
            cents = self._balances.calculate_direct_debts_cents(group["id"]).get((debtor, creditor), 0)
            if cents > 0:
                found.append((group, cents))
        if not found:
            already_paid = any(
                s["status"] == "paid" and s.get("kind") == DIRECT_KIND and (s["from"], s["to"]) == (debtor, creditor)
                for g in groups for s in self._settlements.list_all(g["id"])
            )
            if already_paid:
                raise DirectDebtConflictError("This direct debt has already been paid")
            raise DirectDebtNotFoundError("No outstanding direct debt with this person")
        return found

    def _name(self, uid: str) -> str:
        return self._users.get_display_name(uid) or FALLBACK_NAME

    # ---------------------------------------------------------------- mark paid
    def mark_paid(
        self, caller: str, debtor: str, creditor: str, group_id: Optional[str] = None,
        all_groups: bool = False, expected_amount: Optional[float] = None,
    ) -> dict:
        found = self._outstanding(caller, debtor, creditor, group_id)
        if len(found) > 1 and group_id is None and not all_groups:
            names = ", ".join(f"{g['id']} ({g.get('name', '')}: {cents_to_amount(c)})" for g, c in found)
            raise DirectDebtBadRequestError(
                f"This debt spans {len(found)} groups: pass groupId to pay one of them, or allGroups=true to pay "
                f"all of them. Groups: {names}"
            )
        total = sum(cents for _, cents in found)
        if expected_amount is not None and to_cents(expected_amount) != total:
            raise DirectDebtConflictError(
                f"The amount of this debt changed from {expected_amount} to {cents_to_amount(total)}. Refetch the details."
            )

        creditor_name = self._name(creditor)
        payments = []
        for group, cents in found:
            gid = group["id"]
            existing = self._settlements.list_all(gid)
            n = 1 + sum(1 for s in existing if s.get("kind") == DIRECT_KIND and (s["from"], s["to"]) == (debtor, creditor))
            payment_id = f"direct_{debtor}_{creditor}_{n}"
            amount = cents_to_amount(cents)
            payment = {
                "id": payment_id, "from": debtor, "to": creditor, "amount": amount, "status": "paid",
                "kind": DIRECT_KIND, "markedBy": caller,
                "paymentClaimStatus": "none", "paymentClaimedAt": None, "paymentClaimedBy": None,
            }
            breakdown = self._breakdown(gid, debtor, creditor, cents)
            if breakdown is not None:
                payment["breakdown"] = breakdown  # what the debt was made of, frozen at payment time
            notification = {
                "type": "payment_confirmed", "recipientUid": debtor, "senderUid": caller, "groupId": gid,
                "settlementId": payment_id, "amount": amount,
                "message": f"התשלום שלך של ₪{format_cents(cents)} ל{creditor_name} אושר",
                "isRead": False, "readAt": None,
            }
            pending = self._pending(gid, debtor, creditor)
            claim_id = pending["id"] if pending else None  # paying directly also confirms a pending claim
            if self._payments.record_direct_payment(gid, payment_id, payment, notification, claim_id) != "ok":
                raise DirectDebtConflictError("This debt was just paid by another request. Refetch the details.")
            payments.append({"groupId": gid, "groupName": group.get("name", ""), "paymentId": payment_id, "amount": amount})

        remaining = sum(
            self._balances.calculate_direct_debts_cents(g["id"]).get((debtor, creditor), 0)
            for g in self._groups.list_by_member(caller)
        )
        return {"debtorUid": debtor, "creditorUid": creditor, "paidAmount": cents_to_amount(total),
                "payments": payments, "remainingAmount": cents_to_amount(remaining)}

    def _breakdown(self, group_id: str, debtor: str, creditor: str, cents: int) -> Optional[dict]:
        """The settlement-style snapshot of the real rows behind the payment, only when they add up exactly."""
        rows = self._balances.direct_debt_rows(group_id, debtor, creditor)
        if sum(r["relevantCents"] for r in rows) != cents:
            return None
        items = [
            {"type": r["type"], "expenseId": r.get("expenseId"), "description": r.get("description"),
             "expenseAmount": r.get("expenseAmount"), "payerUid": r.get("payerUid"),
             "settlementId": r.get("settlementId"), "relevantAmount": cents_to_amount(r["relevantCents"]),
             "createdAt": r.get("createdAt")}
            for r in rows
        ]
        return {"available": True, "type": "exact", "reason": None, "items": items, "contributingExpenses": []}

    # ---------------------------------------------------------------- reminder
    def send_reminder(self, caller: str, debtor: str, creditor: str, group_id: Optional[str] = None) -> dict:
        found = self._outstanding(caller, debtor, creditor, group_id)
        remindable = [(g, c) for g, c in found if debtor in g.get("memberIds", [])]
        if not remindable:
            raise DirectDebtBadRequestError("The debtor is no longer a member of the group(s) with this debt")
        total = sum(cents for _, cents in remindable)
        scope_group = remindable[0][0] if len(remindable) == 1 else None

        text = f"{self._name(caller)} שלח לך תזכורת לגבי חוב של ₪{format_cents(total)}"
        text += f' בקבוצה "{scope_group.get("name", "")}"' if scope_group else f" ב-{len(remindable)} קבוצות"
        scope_id = scope_group["id"] if scope_group else None
        data = {
            "type": "debt_reminder", "recipientUid": debtor, "senderUid": caller, "groupId": scope_id,
            "settlementId": None, "debtKind": "direct", "amount": cents_to_amount(total), "message": text,
            "isRead": False, "readAt": None,
        }
        created = self._notifications.create_unless_recent(
            debtor, data,
            same_as={"type": "debt_reminder", "senderUid": caller, "debtKind": "direct", "groupId": scope_id},
            cooldown=REMINDER_COOLDOWN,
        )
        if created is None:
            raise DirectDebtConflictError("A reminder for this debt was already sent recently")
        return created

    # ---------------------------------------------------------------- payment claim ("שילמתי")
    def _pending(self, group_id: str, debtor: str, creditor: str) -> Optional[dict]:
        return next((c for c in self._claims.list_for_pair(group_id, debtor, creditor) if c["status"] == "pending"), None)

    @staticmethod
    def _require_scope(groups: List[dict], group_id: Optional[str], all_groups: bool, verb: str) -> None:
        if len(groups) > 1 and group_id is None and not all_groups:
            names = ", ".join(f"{g['id']} ({g.get('name', '')})" for g in groups)
            raise DirectDebtBadRequestError(
                f"This debt spans {len(groups)} groups: pass groupId to {verb} one of them, or allGroups=true for all. "
                f"Groups: {names}")

    def claim_paid(
        self, caller: str, debtor: str, creditor: str, group_id: Optional[str] = None,
        all_groups: bool = False, expected_amount: Optional[float] = None,
    ) -> dict:
        """The DEBTOR says they paid. Creates a pending claim per group for the current outstanding amount and
        notifies the creditor. No balance or debt changes until the creditor confirms."""
        found = self._outstanding(caller, debtor, creditor, group_id, party="debtor")
        self._require_scope([g for g, _ in found], group_id, all_groups, "claim")
        total = sum(cents for _, cents in found)
        if expected_amount is not None and to_cents(expected_amount) != total:
            raise DirectDebtConflictError(
                f"The amount of this debt changed from {expected_amount} to {cents_to_amount(total)}. Refetch the details.")
        for group, _ in found:
            if creditor not in group.get("memberIds", []):
                raise DirectDebtBadRequestError(f"The creditor is no longer a member of group '{group['id']}'")
            if self._pending(group["id"], debtor, creditor):
                raise DirectDebtConflictError("A payment claim for this debt is already pending confirmation")

        debtor_name = self._name(debtor)
        claims = []
        for group, cents in found:
            gid = group["id"]
            n = 1 + len(self._claims.list_for_pair(gid, debtor, creditor))
            claim_id = f"claim_{debtor}_{creditor}_{n}"
            amount = cents_to_amount(cents)
            claim = {"debtorUid": debtor, "creditorUid": creditor, "groupId": gid, "amount": amount, "status": "pending"}
            notification = {
                "type": "payment_claim", "recipientUid": creditor, "senderUid": debtor, "groupId": gid,
                "settlementId": None, "claimId": claim_id, "debtKind": "direct", "amount": amount,
                "message": f"תשלום של ₪{format_cents(cents)} מ{debtor_name} ממתין לאישורך",
                "isRead": False, "readAt": None,
            }
            if self._claims.create_pending(gid, claim_id, claim, notification) != "ok":
                raise DirectDebtConflictError("A payment claim for this debt was just created. Refetch the details.")
            claims.append({"claimId": claim_id, "groupId": gid, "groupName": group.get("name", ""),
                           "amount": amount, "status": "pending"})
        return {"debtorUid": debtor, "creditorUid": creditor, "claimedAmount": cents_to_amount(total), "claims": claims}

    def confirm_claim(
        self, caller: str, debtor: str, creditor: str, group_id: Optional[str] = None, all_groups: bool = False,
    ) -> dict:
        """The CREDITOR confirms pending claim(s): the CLAIMED amount (capped at what is still owed) becomes a
        real payment and the debtor is notified. If the debt grew since the claim, the rest stays outstanding."""
        found = self._outstanding(caller, debtor, creditor, group_id)
        with_claim = []
        for group, outstanding in found:
            claim = self._pending(group["id"], debtor, creditor)
            if claim:
                with_claim.append((group, outstanding, claim))
        if not with_claim:
            raise DirectDebtConflictError("There is no pending payment claim for this debt")
        self._require_scope([g for g, _, _ in with_claim], group_id, all_groups, "confirm")

        creditor_name = self._name(creditor)
        payments, total = [], 0
        for group, outstanding, claim in with_claim:
            gid = group["id"]
            cents = min(to_cents(claim["amount"]), outstanding)
            n = 1 + sum(1 for s in self._settlements.list_all(gid)
                        if s.get("kind") == DIRECT_KIND and (s["from"], s["to"]) == (debtor, creditor))
            payment_id = f"direct_{debtor}_{creditor}_{n}"
            amount = cents_to_amount(cents)
            payment = {"id": payment_id, "from": debtor, "to": creditor, "amount": amount, "status": "paid",
                       "kind": DIRECT_KIND, "markedBy": caller, "claimId": claim["id"],
                       "paymentClaimStatus": "none", "paymentClaimedAt": None, "paymentClaimedBy": None}
            breakdown = self._breakdown(gid, debtor, creditor, cents)
            if breakdown is not None:
                payment["breakdown"] = breakdown
            notification = {
                "type": "payment_confirmed", "recipientUid": debtor, "senderUid": caller, "groupId": gid,
                "settlementId": payment_id, "claimId": claim["id"], "amount": amount,
                "message": f"התשלום שלך של ₪{format_cents(cents)} ל{creditor_name} אושר",
                "isRead": False, "readAt": None,
            }
            if self._payments.record_direct_payment(gid, payment_id, payment, notification, claim["id"]) != "ok":
                raise DirectDebtConflictError("This claim was just confirmed by another request. Refetch the details.")
            total += cents
            payments.append({"groupId": gid, "groupName": group.get("name", ""), "paymentId": payment_id, "amount": amount})
        remaining = sum(self._balances.calculate_direct_debts_cents(g["id"]).get((debtor, creditor), 0)
                        for g in self._groups.list_by_member(caller))
        return {"debtorUid": debtor, "creditorUid": creditor, "paidAmount": cents_to_amount(total),
                "payments": payments, "remainingAmount": cents_to_amount(remaining)}

    def reject_claim(
        self, caller: str, debtor: str, creditor: str, group_id: Optional[str] = None, all_groups: bool = False,
    ) -> dict:
        """The CREDITOR says the money was not received. The claim becomes rejected, the debt is unchanged, the
        debtor is notified (`payment_claim_rejected`) and may claim again."""
        if caller != creditor:
            raise DirectDebtNotAllowedError("Only the person owed money can reject a payment claim")
        groups = [g for g in self._groups.list_by_member(caller) if group_id is None or g["id"] == group_id]
        if group_id is not None and not groups:
            raise DirectDebtNotFoundError(f"Group '{group_id}' not found or you are not a member of it")
        with_claim = []
        for group in sorted(groups, key=lambda g: g["id"]):
            claim = self._pending(group["id"], debtor, creditor)
            if claim:
                with_claim.append((group, claim))
        if not with_claim:
            raise DirectDebtConflictError("There is no pending payment claim for this debt")
        self._require_scope([g for g, _ in with_claim], group_id, all_groups, "reject")

        creditor_name = self._name(creditor)
        rejected = []
        for group, claim in with_claim:
            amount = claim["amount"]
            notification = {
                "type": "payment_claim_rejected", "recipientUid": debtor, "senderUid": caller, "groupId": group["id"],
                "settlementId": None, "claimId": claim["id"], "debtKind": "direct", "amount": amount,
                "message": f"התשלום שלך של ₪{format_cents(to_cents(amount))} ל{creditor_name} לא אושר",
                "isRead": False, "readAt": None,
            }
            if self._claims.reject(group["id"], claim["id"], caller, notification) != "ok":
                raise DirectDebtConflictError("This claim was just resolved by another request. Refetch the details.")
            rejected.append({"claimId": claim["id"], "groupId": group["id"], "groupName": group.get("name", ""),
                             "amount": amount, "status": "rejected"})
        return {"debtorUid": debtor, "creditorUid": creditor,
                "claimedAmount": sum(c["amount"] for c in rejected), "claims": rejected}
