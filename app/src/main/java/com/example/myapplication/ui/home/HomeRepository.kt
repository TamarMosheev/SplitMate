package com.example.myapplication.ui.home

import com.example.myapplication.repository.AuthRepository
import com.example.myapplication.utils.Resource

/**
 * Supplies real Home data.
 *
 * Currently the app stores no groups, expenses or balances anywhere (no Firestore
 * structure exists yet), so [loadHome] returns only the signed-in user's name and an
 * empty group list. When a real groups/expenses data source is added, load it here and
 * compute [HomeContent.generalBalance] and each group's personal balance from it.
 */
class HomeRepository(
    private val authRepository: AuthRepository = AuthRepository()
) {

    suspend fun loadHome(): Resource<HomeContent> {
        val user = authRepository.currentUser
            ?: return Resource.Error("המשתמש אינו מחובר")

        return Resource.Success(
            HomeContent(
                userName = user.displayName?.takeIf { it.isNotBlank() },
                groups = emptyList(), // TODO: load the user's real groups once a data source exists
                generalBalance = null // TODO: derive from real expenses once balance logic exists
            )
        )
    }
}
