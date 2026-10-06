package com.example.myapplication.repository

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/** uid -> display name from Firestore users/{uid}.name, cached for the process. Unknown stays unknown. */
object UserNameResolver {

    private val names = ConcurrentHashMap<String, String>()
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    /** Fetches names not seen yet and returns the known names among [uids]. */
    suspend fun resolve(uids: Set<String>): Map<String, String> {
        coroutineScope {
            uids.filter { !names.containsKey(it) }.map { id ->
                async {
                    try {
                        firestore.collection("users").document(id).get().await()
                            .getString("name")?.takeIf { it.isNotBlank() }?.let { names[id] = it }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not read name of users/$id", e)
                    }
                }
            }.awaitAll()
        }
        return uids.mapNotNull { id -> names[id]?.let { id to it } }.toMap()
    }

    private const val TAG = "UserNameResolver"
}
