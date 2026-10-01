package com.example.myapplication.repository

import android.util.Log
import com.example.myapplication.data.model.GROUPS_COLLECTION
import com.example.myapplication.data.model.GroupMember
import com.example.myapplication.utils.Resource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.example.myapplication.data.model.Expense
import com.google.firebase.firestore.DocumentSnapshot
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

data class GroupDetails(
    val id: String,
    val name: String,
    val icon: String,
    val members: List<GroupMember>
)

/** groups/{groupId}/expenses/{expenseId}. Everything is keyed by Firebase UID. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseRepository(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    private val firestore by lazy { FirebaseFirestore.getInstance() }

    val currentUid: String?
        get() = firebaseAuth.currentUser?.uid

    /** Generated once per screen so a retried save overwrites the same document instead of duplicating it. */
    fun newExpenseId(groupId: String): String =
        firestore.collection(GROUPS_COLLECTION).document(groupId).collection(EXPENSES).document().id

    /** Loads groups/{id} and resolves every member UID from users/{uid}. */
    suspend fun loadGroup(groupId: String): Resource<GroupDetails> {
        return try {
            val doc = withTimeout(TIMEOUT_MS) {
                firestore.collection(GROUPS_COLLECTION).document(groupId).get().await()
            }
            if (!doc.exists()) return Resource.Error("הקבוצה לא נמצאה")
            @Suppress("UNCHECKED_CAST")
            val memberIds = (doc.get("memberIds") as? List<String>).orEmpty()
            Log.d(TAG, "Loaded groups/$groupId name='${doc.getString("name")}' memberIds=$memberIds")

            val members = coroutineScope {
                memberIds.map { uid -> async { loadMember(uid) } }.awaitAll()
            }
            Resource.Success(
                GroupDetails(
                    id = groupId,
                    name = doc.getString("name").orEmpty(),
                    icon = doc.getString("icon").orEmpty(),
                    members = members
                )
            )
        } catch (e: Exception) {
            val code = (e as? FirebaseFirestoreException)?.code
            Log.e(TAG, "Failed to load groups/$groupId: code=$code message=${e.message}", e)
            Resource.Error(
                when {
                    e is TimeoutCancellationException -> "אין חיבור לאינטרנט. נסו שוב"
                    code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> "אין הרשאה לצפות בקבוצה"
                    else -> "טעינת הקבוצה נכשלה"
                }
            )
        }
    }

    private suspend fun loadMember(uid: String): GroupMember = try {
        val doc = firestore.collection(USERS).document(uid).get().await()
        GroupMember(
            uid = uid,
            name = doc.getString("name").orEmpty().ifBlank { doc.getString("email").orEmpty() },
            email = doc.getString("email").orEmpty()
        )
    } catch (e: Exception) {
        Log.w(TAG, "Could not read users/$uid", e)
        GroupMember(uid = uid, name = "", email = "")
    }

    private class RawGroup(val name: String, val icon: String, val memberIds: List<String>)

    private val memberCache = ConcurrentHashMap<String, GroupMember>()

    /** Live groups/{id} (name, icon, members). Member profiles are resolved from users/{uid} and cached. */
    fun observeGroup(groupId: String): Flow<Resource<GroupDetails>> = callbackFlow<Resource<RawGroup>> {
        val registration = firestore.collection(GROUPS_COLLECTION).document(groupId)
            .addSnapshotListener { doc, error ->
                if (error != null) {
                    Log.e(TAG, "Group listener failed for groups/$groupId: ${error.code} ${error.message}", error)
                    trySend(Resource.Error("טעינת הקבוצה נכשלה"))
                    return@addSnapshotListener
                }
                if (doc == null || !doc.exists()) {
                    trySend(Resource.Error("הקבוצה לא נמצאה"))
                    return@addSnapshotListener
                }
                @Suppress("UNCHECKED_CAST")
                val ids = (doc.get("memberIds") as? List<String>).orEmpty()
                Log.d(TAG, "groups/$groupId snapshot: name='${doc.getString("name")}' members=${ids.size}")
                trySend(Resource.Success(RawGroup(doc.getString("name").orEmpty(), doc.getString("icon").orEmpty(), ids)))
            }
        awaitClose { registration.remove() }
    }.mapLatest { raw ->
        when (raw) {
            is Resource.Success -> {
                val members = coroutineScope {
                    raw.data.memberIds.map { uid ->
                        async {
                            memberCache[uid] ?: loadMember(uid).also { if (it.name.isNotBlank()) memberCache[uid] = it }
                        }
                    }.awaitAll()
                }
                Resource.Success(GroupDetails(groupId, raw.data.name, raw.data.icon, members))
            }
            is Resource.Error -> raw
            Resource.Loading -> Resource.Loading
        }
    }

    /** Live expenses of exactly this group (groups/{groupId}/expenses), newest first. */
    fun observeExpenses(groupId: String): Flow<Resource<List<Expense>>> = callbackFlow {
        val registration = firestore.collection(GROUPS_COLLECTION).document(groupId)
            .collection(EXPENSES)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Expenses listener failed for groups/$groupId: ${error.code} ${error.message}", error)
                    trySend(
                        Resource.Error(
                            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                                "אין הרשאה לצפות בהוצאות הקבוצה"
                            else "טעינת ההוצאות נכשלה"
                        )
                    )
                    return@addSnapshotListener
                }
                val expenses = snapshot?.documents.orEmpty().map { doc ->
                    @Suppress("UNCHECKED_CAST")
                    Expense(
                        id = doc.id,
                        description = doc.getString("description").orEmpty(),
                        amount = doc.getDouble("amount") ?: 0.0,
                        paidBy = doc.getString("paidBy").orEmpty(),
                        participantIds = (doc.get("participantIds") as? List<String>).orEmpty(),
                        splitType = doc.getString("splitType").orEmpty(),
                        createdAtMillis = doc.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            ?.toDate()?.time
                    )
                }.sortedByDescending { it.createdAtMillis ?: Long.MAX_VALUE }
                Log.d(TAG, "groups/$groupId expenses snapshot: ${expenses.size} expense(s) fromCache=${snapshot?.metadata?.isFromCache}")
                trySend(Resource.Success(expenses))
            }
        awaitClose { registration.remove() }
    }

    suspend fun saveExpense(
        groupId: String,
        expenseId: String,
        description: String,
        amount: Double,
        paidBy: String,
        participantIds: List<String>,
        splitType: String,
        exactAmounts: Map<String, Double>?
    ): Resource<Unit> {
        val uid = currentUid ?: return Resource.Error("המשתמש אינו מחובר")
        val data = mutableMapOf<String, Any>(
            "description" to description,
            "amount" to amount,
            "paidBy" to paidBy,
            "participantIds" to participantIds,
            "splitType" to splitType,
            "createdBy" to uid,
            "createdAt" to FieldValue.serverTimestamp()
        )
        if (exactAmounts != null) data["exactAmounts"] = exactAmounts
        Log.d(TAG, "Saving groups/$groupId/expenses/$expenseId $data")
        return try {
            withTimeout(TIMEOUT_MS) {
                firestore.collection(GROUPS_COLLECTION).document(groupId)
                    .collection(EXPENSES).document(expenseId).set(data).await()
            }
            Log.d(TAG, "Saved groups/$groupId/expenses/$expenseId successfully")
            Resource.Success(Unit)
        } catch (e: Exception) {
            val code = (e as? FirebaseFirestoreException)?.code
            Log.e(TAG, "Failed to save expense $expenseId: code=$code message=${e.message}", e)
            Resource.Error(
                when {
                    e is TimeoutCancellationException -> "אין חיבור לאינטרנט. ההוצאה לא נשמרה, נסו שוב"
                    code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                        "אין הרשאה לשמור הוצאה (בדקו את כללי האבטחה של Firestore)"
                    else -> "שמירת ההוצאה נכשלה. נסו שוב"
                }
            )
        }
    }

    private companion object {
        const val TAG = "ExpenseRepository"
        const val USERS = "users"
        const val EXPENSES = "expenses"
        const val TIMEOUT_MS = 15_000L
    }
}
