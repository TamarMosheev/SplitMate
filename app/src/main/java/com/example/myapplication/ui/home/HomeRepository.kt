package com.example.myapplication.ui.home

import android.util.Log
import com.example.myapplication.data.model.GROUPS_COLLECTION
import com.example.myapplication.data.model.Group
import com.example.myapplication.repository.AuthRepository
import com.example.myapplication.utils.Resource
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.tasks.await

/**
 * Supplies real Home data.
 *
 * The user's profile is observed live from users/{uid} (uid = FirebaseAuth uid).
 * Groups are observed live from groups where memberIds contains the uid. Balances have no data source yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRepository(
    private val authRepository: AuthRepository = AuthRepository()
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
            when (profile) {
                is Resource.Success -> Resource.Success(profile.data.copy(groups = groups))
                is Resource.Error -> profile
                Resource.Loading -> Resource.Loading
            }
        }

    private val memberNames = ConcurrentHashMap<String, String>()

    /** Live groups where the signed-in user is a member (groups/{id}.memberIds contains uid), newest first. */
    private fun observeGroups(): Flow<List<GroupItemUi>> = callbackFlow<List<Group>> {
        val uid = authRepository.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val registration = firestore.collection(GROUPS_COLLECTION)
            .whereArrayContains("memberIds", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Groups listener failed for uid=$uid", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val groups = snapshot?.documents.orEmpty().map { doc ->
                    @Suppress("UNCHECKED_CAST")
                    Group(
                        id = doc.id,
                        name = doc.getString("name").orEmpty(),
                        icon = doc.getString("icon").orEmpty(),
                        createdBy = doc.getString("createdBy").orEmpty(),
                        memberIds = (doc.get("memberIds") as? List<String>).orEmpty(),
                        createdAtMillis = doc.getTimestamp("createdAt", ServerTimestampBehavior.ESTIMATE)
                            ?.toDate()?.time ?: Long.MAX_VALUE
                    )
                }.sortedByDescending { it.createdAtMillis }
                Log.d(TAG, "uid=$uid groups snapshot: ${groups.size} groups fromCache=${snapshot?.metadata?.isFromCache}")
                groups.forEach { Log.d(TAG, "  group id=${it.id} name='${it.name}' icon=${it.icon} members=${it.memberIds.size}") }
                trySend(groups)
            }
        awaitClose {
            Log.d(TAG, "Removing groups listener for uid=$uid")
            registration.remove()
        }
    }.mapLatest { groups ->
        loadMemberNames(groups.flatMap { it.memberIds }.toSet())
        val uid = authRepository.currentUser?.uid
        groups.map { g ->
            GroupItemUi(
                id = g.id,
                name = g.name,
                members = g.memberIds.map { GroupMemberUi(memberNames[it]) },
                personalBalance = null, // TODO: derive from real expenses once balance logic exists
                icon = g.icon.takeIf { it.isNotBlank() },
                // Same rule as the backend: only the creator may delete a group.
                canDelete = uid != null && g.createdBy.isNotBlank() && g.createdBy == uid
            )
        }
    }

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
