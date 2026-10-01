package com.example.myapplication.repository

import android.util.Log
import com.example.myapplication.data.model.GROUPS_COLLECTION
import com.example.myapplication.data.model.GroupMember
import com.example.myapplication.utils.Resource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

class GroupRepository(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    private val firestore by lazy { FirebaseFirestore.getInstance() }

    /** Generated once per screen so a retried save overwrites the same document instead of duplicating it. */
    fun newGroupId(): String = firestore.collection(GROUPS_COLLECTION).document().id

    /** The signed-in user, read from users/{uid}; falls back to Auth data if the read fails. */
    suspend fun loadCurrentUser(): Resource<GroupMember> {
        val user = firebaseAuth.currentUser ?: return Resource.Error("המשתמש אינו מחובר")
        val authEmail = user.email.orEmpty()
        return try {
            val doc = withTimeout(TIMEOUT_MS) { firestore.collection(USERS).document(user.uid).get().await() }
            Resource.Success(
                GroupMember(
                    uid = user.uid,
                    name = doc.getString("name")?.takeIf { it.isNotBlank() }
                        ?: user.displayName.orEmpty(),
                    email = doc.getString("email")?.takeIf { it.isNotBlank() } ?: authEmail
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read users/${user.uid}; using Auth data", e)
            Resource.Success(GroupMember(user.uid, user.displayName.orEmpty(), authEmail))
        }
    }

    /**
     * Live prefix search over users/{uid}.name. Uses the automatic single-field index on name
     * (range + orderBy on the same field), so no composite index is needed.
     */
    suspend fun searchUsersByName(prefix: String): Resource<List<GroupMember>> {
        val q = prefix.trim()
        if (q.isEmpty()) return Resource.Success(emptyList())
        Log.d(TAG, "Name search: prefix='$q' as uid=${firebaseAuth.currentUser?.uid}")
        return try {
            val snap = withTimeout(TIMEOUT_MS) {
                firestore.collection(USERS)
                    .orderBy("name")
                    .startAt(q)
                    .endAt(q + "")
                    .limit(SEARCH_LIMIT)
                    .get().await()
            }
            Log.d(TAG, "Name search '$q' returned ${snap.size()} document(s)")
            Resource.Success(
                snap.documents.map { doc ->
                    GroupMember(
                        uid = doc.id,
                        name = doc.getString("name").orEmpty(),
                        email = doc.getString("email").orEmpty()
                    )
                }
            )
        } catch (e: Exception) {
            val code = (e as? FirebaseFirestoreException)?.code
            Log.e(TAG, "Name search failed: code=$code message=${e.message}", e)
            Resource.Error(
                when {
                    e is TimeoutCancellationException -> "אין חיבור. נסו שוב"
                    code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                        "אין הרשאה לחפש משתמשים (בדקו את כללי האבטחה של Firestore)"
                    else -> "החיפוש נכשל. נסו שוב"
                }
            )
        }
    }

    suspend fun createGroup(id: String, name: String, icon: String, memberIds: List<String>): Resource<Unit> {
        val uid = firebaseAuth.currentUser?.uid ?: return Resource.Error("המשתמש אינו מחובר")
        val members = (listOf(uid) + memberIds).distinct()
        Log.d(TAG, "Saving groups/$id name='$name' icon=$icon createdBy=$uid members=$members")
        return try {
            withTimeout(TIMEOUT_MS) {
                // The Task completes only once the backend has committed the write (or failed it).
                firestore.collection(GROUPS_COLLECTION).document(id).set(
                    mapOf(
                        "name" to name,
                        "icon" to icon,
                        "createdBy" to uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "memberIds" to members
                    )
                ).await()
            }
            Log.d(TAG, "Saved groups/$id successfully (committed by Firestore backend)")
            Resource.Success(Unit)
        } catch (e: Exception) {
            val code = (e as? FirebaseFirestoreException)?.code
            Log.e(TAG, "Failed to save groups/$id: code=$code message=${e.message}", e)
            Resource.Error(
                when {
                    e is TimeoutCancellationException -> "אין חיבור לאינטרנט. הקבוצה לא נשמרה, נסו שוב"
                    code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                        "אין הרשאה ליצור קבוצה (בדקו את כללי האבטחה של Firestore)"
                    else -> "יצירת הקבוצה נכשלה. נסו שוב"
                }
            )
        }
    }

    private companion object {
        const val TAG = "GroupRepository"
        const val USERS = "users"
        const val TIMEOUT_MS = 15_000L
        const val SEARCH_LIMIT = 25L
    }
}
