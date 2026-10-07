package com.example.myapplication.ui.home

import android.util.Log
import com.example.myapplication.data.api.ApiErrorMapper
import com.example.myapplication.data.api.ApiService
import com.example.myapplication.data.api.RetrofitClient
import com.example.myapplication.repository.AuthRepository
import com.example.myapplication.repository.BalanceRepository
import com.example.myapplication.ui.group.canDeleteGroup
import com.example.myapplication.ui.group.logGroupDeleteDebug
import com.example.myapplication.utils.Resource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import retrofit2.HttpException
import kotlinx.coroutines.tasks.await

/**
 * Supplies real Home data.
 *
 * The user's profile is observed live from users/{uid} (uid = FirebaseAuth uid).
 * Groups come from the FastAPI backend (GET /groups); the overall balance is summed from GET /groups/{id}/balances.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRepository(
    private val authRepository: AuthRepository = AuthRepository(),
    private val apiService: ApiService = RetrofitClient.apiService,
    private val balanceRepository: BalanceRepository = BalanceRepository()
) {

    private val firestore by lazy { FirebaseFirestore.getInstance() }

    /**
     * Emits a new [HomeContent] every time users/{uid} changes (remote or local write).
     * The snapshot listener lives only while the flow is collected and is removed in [awaitClose].
     */
    /** Makes sure users/{uid} exists and is searchable (see [AuthRepository.ensureUserProfile]). */
    suspend fun ensureUserProfile() = authRepository.ensureUserProfile()

    fun observeHome(): Flow<Resource<HomeContent>> =
        combine(observeProfile(), observeGroups()) { profile, groups ->
            when {
                profile is Resource.Error -> profile
                groups is Resource.Error -> groups
                profile is Resource.Success && groups is Resource.Success ->
                    Resource.Success(
                        profile.data.copy(
                            groups = groups.data.groups,
                            generalBalance = groups.data.balance,
                            balanceLoading = groups.data.balanceLoading
                        )
                    )
                else -> Resource.Loading
            }
        }

    private val memberNames = ConcurrentHashMap<String, String>()

    /** Bumped by [refreshGroups]; every change re-fetches GET /groups. */
    private val refreshTrigger = MutableStateFlow(0)

    fun refreshGroups() {
        refreshTrigger.update { it + 1 }
    }

    /** Groups the signed-in user belongs to, loaded from the FastAPI backend (GET /groups), newest first. */
    private fun observeGroups(): Flow<Resource<GroupsSnapshot>> = refreshTrigger.transformLatest {
        val groups = try {
            apiService.getGroups()
                .mapNotNull { it.toGroup() }
                .sortedByDescending { it.createdAtMillis }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GET /groups failed", e)
            emit(Resource.Error(ApiErrorMapper.message(e)))
            return@transformLatest
        }
        Log.d(TAG, "GET /groups returned ${groups.size} group(s)")
        loadMemberNames(groups.flatMap { it.memberIds }.toSet())
        val myUid = authRepository.currentUser?.uid
        val items = groups.map { g ->
            GroupItemUi(
                id = g.id,
                name = g.name,
                members = g.memberIds.map { GroupMemberUi(memberNames[it]) },
                personalBalance = null, // TODO: per-group balance on the group card
                icon = g.icon.takeIf { it.isNotBlank() },
                createdBy = g.createdBy,
                canDelete = canDeleteGroup(g.createdBy, myUid).also {
                    logGroupDeleteDebug("Home", g.id, g.name, g.createdBy, myUid, it)
                }
            )
        }
        // Show the groups right away, then fill in the overall balance when the requests finish.
        emit(Resource.Success(GroupsSnapshot(items, balance = null, balanceLoading = true)))
        emit(Resource.Success(GroupsSnapshot(items, balanceRepository.loadOverallBalance(groups.map { it.id }), balanceLoading = false)))
    }

    private data class GroupsSnapshot(
        val groups: List<GroupItemUi>,
        val balance: BigDecimal?,
        val balanceLoading: Boolean
    )

    /** Fetches users/{uid}.name for members not seen yet; failures leave a generic avatar. */
    private suspend fun loadMemberNames(uids: Set<String>) = coroutineScope {
        uids.filter { !memberNames.containsKey(it) }.map { id ->
            async {
                try {
                    firestore.collection(USERS).document(id).get().await()
                        .getString("name")?.takeIf { it.isNotBlank() }?.let { memberNames[id] = it }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read name of users/$id", e)
                }
            }
        }.awaitAll()
    }

    private fun observeProfile(): Flow<Resource<HomeContent>> = callbackFlow {
        val user = authRepository.currentUser
        if (user == null) {
            trySend(Resource.Error("המשתמש אינו מחובר"))
            close()
            return@callbackFlow
        }

        val registration = firestore.collection(USERS).document(user.uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Snapshot listener failed for users/${user.uid}", error)
                    trySend(Resource.Error("טעינת הנתונים נכשלה. בדקו את החיבור ונסו שוב"))
                    return@addSnapshotListener
                }
                val name = snapshot?.getString("name")?.takeIf { it.isNotBlank() }
                    ?: user.displayName?.takeIf { it.isNotBlank() }
                Log.d(TAG, "users/${user.uid} snapshot: name=$name exists=${snapshot?.exists()} " +
                        "fromCache=${snapshot?.metadata?.isFromCache}")
                trySend(
                    Resource.Success(
                        HomeContent(
                            userName = name,
                            groups = emptyList(), // TODO: load the user's real groups once a data source exists
                            generalBalance = null // TODO: derive from real expenses once balance logic exists
                        )
                    )
                )
            }

        awaitClose {
            Log.d(TAG, "Removing snapshot listener for users/${user.uid}")
            registration.remove()
        }
    }

    /** Writes the name to users/{uid}; the snapshot listener then pushes it back to the UI. */
    suspend fun updateName(name: String): Resource<Unit> {
        val uid = authRepository.currentUser?.uid
            ?: return Resource.Error("המשתמש אינו מחובר")
        return try {
            firestore.collection(USERS).document(uid)
                .set(mapOf("name" to name), SetOptions.merge())
                .await()
            Log.d(TAG, "Wrote users/$uid name=$name")
            Resource.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write users/$uid", e)
            Resource.Error("שמירת השם נכשלה. בדקו את החיבור ונסו שוב")
        }
    }

    private companion object {
        const val TAG = "HomeRepository"
        const val USERS = "users"
    }
}
