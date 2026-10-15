from typing import Dict, List, Tuple

from repositories.group_repository import GroupRepository
from repositories.user_repository import UserRepository
from services.balance_service import BalanceService
from utils.money import cents_to_amount, to_cents


class DirectDebtService:
    """The signed-in user's direct debts from expenses ("חייבים לך" / "את חייבת"), across all their groups.

    This is NOT the group settlement: nothing is routed through third people and the settlement
    algorithm is not used. A person appears if the expenses make them owe the user (or the reverse)
    directly, whatever their overall group balance is. See BalanceService.calculate_direct_debts_cents.
    """

    def __init__(self, group_repository: GroupRepository, balance_service: BalanceService, user_repository: UserRepository,
                 claim_repository=None):
        self._claims = claim_repository
        self._groups = group_repository
        self._balances = balance_service
        self._users = user_repository

    def receivable(self, uid: str) -> dict:
        """People who directly owe `uid` money: `uid` paid for them."""
        return self._collect(uid, owed_to_me=True)

    def payable(self, uid: str) -> dict:
        """People `uid` directly owes money: they paid for `uid`."""
        return self._collect(uid, owed_to_me=False)

    def _collect(self, uid: str, owed_to_me: bool) -> dict:
        per_person: Dict[str, Dict[str, Tuple[str, int]]] = {}  # other uid -> {group id: (group name, cents)}
        for group in self._groups.list_by_member(uid):
            for (debtor, creditor), cents in self._balances.calculate_direct_debts_cents(group["id"]).items():
                if owed_to_me and creditor == uid:
                    other = debtor
                elif not owed_to_me and debtor == uid:
                    other = creditor
                else:
                    continue
                per_person.setdefault(other, {})[group["id"]] = (group.get("name", ""), cents)

        debts: List[dict] = []
        total_cents = 0
        for other, by_group in per_person.items():
            groups = sorted(by_group.items(), key=lambda item: (-item[1][1], item[0]))
            person_cents = sum(cents for _, cents in by_group.values())
            total_cents += person_cents
            debts.append({
                "userId": other,
                "displayName": self._users.get_display_name(other),
                "amount": cents_to_amount(person_cents),
                "groups": [{"groupId": gid, "groupName": name, "amount": cents_to_amount(cents)}
                           for gid, (name, cents) in groups],
            })
        debts.sort(key=lambda d: (-d["amount"], d["userId"]))
        return {"total": cents_to_amount(total_cents), "debts": debts}

    def details(self, uid: str, owed_to_me: bool, other_uid: str) -> dict:
        """One row of receivable/payable opened: the same person, the same amounts per group, plus the real
        expenses/payments behind each group's amount. Returns {} when `uid` has no direct debt with `other_uid`."""
        debtor, creditor = (other_uid, uid) if owed_to_me else (uid, other_uid)
        groups: List[dict] = []
        total_cents = 0
        for group in self._groups.list_by_member(uid):
            cents = self._balances.calculate_direct_debts_cents(group["id"]).get((debtor, creditor), 0)
            if cents <= 0:
                continue
            rows = self._balances.direct_debt_rows(group["id"], debtor, creditor)
            rows.sort(key=lambda r: (r["createdAt"] is None, str(r["createdAt"] or ""), r.get("expenseId") or r.get("settlementId") or ""))
            items = []
            for r in rows:
                item = {k: v for k, v in r.items() if k != "relevantCents"}
                item["relevantAmount"] = cents_to_amount(r["relevantCents"])
                items.append(item)
            total_cents += cents
            # Only the creditor acts. A reminder also needs the debtor to still be in the group (a removed
            # member keeps the debt but cannot be notified about that group); marking paid does not.
            pending = None
            if self._claims is not None:
                pending = next((c for c in self._claims.list_for_pair(group["id"], debtor, creditor)
                                if c["status"] == "pending"), None)
            groups.append({"groupId": group["id"], "groupName": group.get("name", ""),
                           "canClaimPaid": (not owed_to_me) and pending is None,
                           "claimPending": pending is not None,
                           "claimedAmount": pending["amount"] if pending else None,
                           "claimId": pending["id"] if pending else None,
                           "claimedAt": pending.get("createdAt") if pending else None,
                           "canConfirmClaim": owed_to_me and pending is not None,
                           "canRejectClaim": owed_to_me and pending is not None,
                           "amount": cents_to_amount(cents), "itemsExact": sum(r["relevantCents"] for r in rows) == cents,
                           "items": items, "canMarkPaid": owed_to_me,
                           "canSendReminder": owed_to_me and debtor in group.get("memberIds", [])})
        if not groups:
            return {}
        groups.sort(key=lambda g: (-g["amount"], g["groupId"]))
        return {"userId": other_uid, "displayName": self._users.get_display_name(other_uid),
                "owedToMe": owed_to_me, "amount": cents_to_amount(total_cents), "groups": groups,
                "debtorUid": debtor, "creditorUid": creditor,
                "canMarkPaid": owed_to_me,
                "canClaimPaid": any(g["canClaimPaid"] for g in groups),
                "claimPending": any(g["claimPending"] for g in groups),
                "claimedAmount": cents_to_amount(sum(to_cents(g["claimedAmount"]) for g in groups if g["claimPending"]))
                                 if any(g["claimPending"] for g in groups) else None,
                "canConfirmClaim": any(g["canConfirmClaim"] for g in groups),
                "canRejectClaim": any(g["canRejectClaim"] for g in groups),
                "canSendReminder": any(g["canSendReminder"] for g in groups),
                "requiresGroupSelection": len(groups) > 1}
