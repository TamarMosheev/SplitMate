package com.example.myapplication.ui.home

/** UI-level models. They describe what the Home screen renders, not a Firestore schema. */
data class GroupMemberUi(val displayName: String?)

data class GroupItemUi(
    val id: String,
    val name: String,
    val members: List<GroupMemberUi>,
    /** Null until real balance data exists; the card then hides the balance row. */
    val personalBalance: Double?,
    val icon: String? = null,
    /** The group's creator uid as the backend returned it (decides the trash icon at bind time). */
    val createdBy: String? = null,
    /** Contract rule: only the group's creator may delete it (`createdBy == current uid`). */
    val canDelete: Boolean = false,
    /** True while DELETE /groups/{id} is running for this group. */
    val isDeleting: Boolean = false
)

data class HomeContent(
    val userName: String?,
    val groups: List<GroupItemUi>,
    /** Null when no balance data source exists / nothing to compute. */
    val generalBalance: java.math.BigDecimal?,
    /** True while the per-group balance requests are still running. */
    val balanceLoading: Boolean = false
)

sealed class HomeUiState {
    object Loading : HomeUiState()
    data class Success(val content: HomeContent) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}
