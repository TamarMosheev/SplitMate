package com.example.myapplication.data.api

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/** GET /groups/{id}/settlement. `transfers` = open debts, `paid` = history. */
data class SettlementResponse(
    val balances: Map<String, BigDecimal>? = null,
    val transfers: List<SettlementDto>? = null,
    val paid: List<SettlementDto>? = null
)

/** One settlement (open or paid). `from` = debtor uid, `to` = creditor uid. Also the mark-as-paid response. */
data class SettlementDto(
    val id: String? = null,
    @SerializedName("from") val from: String? = null,
    @SerializedName("to") val to: String? = null,
    val amount: BigDecimal? = null,
    /** "open" | "paid" */
    val status: String? = null,
    val createdAt: String? = null,
    val paidAt: String? = null,
    val markedBy: String? = null,
    /** "none" | "pending" | "confirmed" | "rejected" (two-step payment confirmation). */
    val paymentClaimStatus: String? = null,
    val paymentClaimedAt: String? = null,
    /** uid of the debtor who reported "I paid". */
    val paymentClaimedBy: String? = null,
    /** The amount the debtor reported paying; a claim is current only if it equals `amount`. */
    val paymentClaimAmount: BigDecimal? = null,
    val paymentConfirmedAt: String? = null,
    val paymentConfirmedBy: String? = null,
    val paymentRejectedAt: String? = null,
    val paymentRejectedBy: String? = null
) {
    val isOpen: Boolean get() = status != "paid"

    /** A pending claim made for the settlement's current amount (same rule as the server's claim_is_current). */
    val hasCurrentClaim: Boolean
        get() = paymentClaimStatus == "pending" && paymentClaimAmount != null && amount != null &&
            paymentClaimAmount.compareTo(amount) == 0
}

/** PATCH .../paid body: the amount the user saw, so the server can reject a changed debt with 409. */
data class MarkPaidRequest(val expectedAmount: BigDecimal)
