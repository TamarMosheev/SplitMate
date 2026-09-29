package com.example.myapplication.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.example.myapplication.data.model.User
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    val currentUser: FirebaseUser?
        get() = firebaseAuth.currentUser

    suspend fun loginWithEmail(email: String, password: String): Resource<User> {
        return try {
            val result = firebaseAuth.signInWithEmailAndPassword(email, password).await()
            val firebaseUser = result.user
            if (firebaseUser != null) {
                val user = User(
                    uid = firebaseUser.uid,
                    email = firebaseUser.email ?: "",
                    displayName = firebaseUser.displayName
                )
                Resource.Success(user)
            } else {
                Resource.Error("ההתחברות נכשלה: משתמש לא נמצא")
            }
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: "שגיאה לא צפויה בהתחברות")
        }
    }

    fun logout() {
        firebaseAuth.signOut()
    }
}