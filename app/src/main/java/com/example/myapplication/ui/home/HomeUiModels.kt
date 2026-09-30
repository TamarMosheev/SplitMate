package com.example.myapplication.ui.home

/** UI-level models. They describe what the Home screen renders, not a Firestore schema. */
data class GroupMemberUi(val displayName: String?)

data class GroupItemUi(
    val id: String,
    val name: String,
    val members: List<GroupMemberUi>,
    val personalBalance: Double
)

data class HomeContent(
    val userName: String?,
    val groups: List<GroupItemUi>,
    /** Null when no balance data source exists / nothing to compute. */
    val generalBalance: Double?
)

sealed class HomeUiState {
    object Loading : HomeUiState()
    data class Success(val content: HomeContent) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}
