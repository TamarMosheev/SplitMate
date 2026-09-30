package com.example.myapplication.repository

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.example.myapplication.data.model.GoogleAuthResult
import com.google.firebase.auth.FirebaseAuthActionCodeException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.example.myapplication.data.model.User
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    // Lazy so flows that never touch Firestore (e.g. email login) are unaffected.
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    val currentUser: FirebaseUser?
        get() = firebaseAuth.currentUser

    /** Creates the Auth account, then the users/{uid} profile. Rolls the account back if the profile can't be saved. */
    suspend fun registerWithEmail(name: String, email: String, password: String): Resource<User> {
        val firebaseUser = try {
            firebaseAuth.createUserWithEmailAndPassword(email, password).await().user
        } catch (e: FirebaseAuthUserCollisionException) {
            return Resource.Error("כתובת האימייל כבר רשומה במערכת")
        } catch (e: FirebaseAuthWeakPasswordException) {
            return Resource.Error("הסיסמה חלשה מדי. בחרו סיסמה חזקה יותר")
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            return Resource.Error("כתובת האימייל אינה תקינה")
        } catch (e: FirebaseNetworkException) {
            return Resource.Error("אין חיבור לאינטרנט. נסו שוב מאוחר יותר")
        } catch (e: Exception) {
            return Resource.Error("ההרשמה נכשלה. נסו שוב")
        } ?: return Resource.Error("ההרשמה נכשלה. נסו שוב")

        return try {
            saveUserProfile(firebaseUser.uid, name, email)
            runCatching {
                firebaseUser.updateProfile(
                    UserProfileChangeRequest.Builder().setDisplayName(name).build()
                ).await()
            }
            Resource.Success(User(uid = firebaseUser.uid, email = email, displayName = name))
        } catch (e: Exception) {
            // Keep Auth and Firestore consistent: no account without a profile.
            runCatching { firebaseUser.delete().await() }
            firebaseAuth.signOut()
            Resource.Error("שמירת פרטי המשתמש נכשלה, ההרשמה לא הושלמה. נסו שוב")
        }
    }

    /** Signs in with Google and reports whether a users/{uid} profile already exists. */
    suspend fun registerWithGoogle(idToken: String): Resource<GoogleAuthResult> {
        val firebaseUser = try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            firebaseAuth.signInWithCredential(credential).await().user
        } catch (e: FirebaseNetworkException) {
            return Resource.Error("אין חיבור לאינטרנט. נסו שוב מאוחר יותר")
        } catch (e: Exception) {
            return Resource.Error("ההרשמה עם Google נכשלה. נסו שוב")
        } ?: return Resource.Error("ההרשמה עם Google נכשלה. נסו שוב")

        return try {
            val hasProfile = userProfileExists(firebaseUser.uid)
            Resource.Success(
                GoogleAuthResult(
                    user = User(
                        uid = firebaseUser.uid,
                        email = firebaseUser.email ?: "",
                        displayName = firebaseUser.displayName
                    ),
                    photoUrl = firebaseUser.photoUrl?.toString(),
                    hasProfile = hasProfile
                )
            )
        } catch (e: Exception) {
            firebaseAuth.signOut()
            Resource.Error("לא ניתן לבדוק את פרטי המשתמש. בדקו את החיבור ונסו שוב")
        }
    }

    /** Saves the profile for the signed-in (Google) user. */
    suspend fun completeGoogleProfile(name: String): Resource<Unit> {
        val firebaseUser = firebaseAuth.currentUser
            ?: return Resource.Error("המשתמש אינו מחובר")
        return try {
            saveUserProfile(firebaseUser.uid, name, firebaseUser.email.orEmpty())
            runCatching {
                firebaseUser.updateProfile(
                    UserProfileChangeRequest.Builder().setDisplayName(name).build()
                ).await()
            }
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error("שמירת פרטי המשתמש נכשלה. בדקו את החיבור ונסו שוב")
        }
    }

    private suspend fun userProfileExists(uid: String): Boolean =
        withFirestoreTimeout { firestore.collection(USERS).document(uid).get().await().exists() }

    // Document id is the UID, so a user can never get a duplicate profile.
    private suspend fun saveUserProfile(uid: String, name: String, email: String) {
        withFirestoreTimeout {
            firestore.collection(USERS).document(uid)
                .set(mapOf("name" to name, "email" to email), SetOptions.merge())
                .await()
        }
    }

    // Firestore writes queue forever while offline, so bound them.
    private suspend fun <T> withFirestoreTimeout(block: suspend () -> T): T =
        try {
            withTimeout(FIRESTORE_TIMEOUT_MS) { block() }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Firestore timeout", e)
        }

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

    suspend fun loginWithGoogle(idToken: String): Resource<User> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = firebaseAuth.signInWithCredential(credential).await()
            val firebaseUser = result.user
            if (firebaseUser != null) {
                Resource.Success(
                    User(
                        uid = firebaseUser.uid,
                        email = firebaseUser.email ?: "",
                        displayName = firebaseUser.displayName
                    )
                )
            } else {
                Resource.Error("ההתחברות עם Google נכשלה: משתמש לא נמצא")
            }
        } catch (e: FirebaseNetworkException) {
            Resource.Error("אין חיבור לאינטרנט. נסו שוב מאוחר יותר")
        } catch (e: Exception) {
            Resource.Error("ההתחברות עם Google נכשלה. נסו שוב")
        }
    }

    suspend fun resetPassword(email: String): Resource<Unit> {
        return try {
            firebaseAuth.sendPasswordResetEmail(email).await()
            Resource.Success(Unit)
        } catch (e: FirebaseAuthInvalidUserException) {
            // Do not reveal whether an account exists for this address.
            Resource.Success(Unit)
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            Resource.Error("כתובת האימייל אינה תקינה")
        } catch (e: FirebaseNetworkException) {
            Resource.Error("אין חיבור לאינטרנט. נסו שוב מאוחר יותר")
        } catch (e: FirebaseTooManyRequestsException) {
            Resource.Error("נשלחו יותר מדי בקשות. נסו שוב מאוחר יותר")
        } catch (e: Exception) {
            Resource.Error("שליחת הקישור נכשלה. נסו שוב")
        }
    }

    /** Completes a Firebase password reset using the oobCode from the reset email link. */
    suspend fun confirmPasswordReset(oobCode: String, newPassword: String): Resource<Unit> {
        return try {
            firebaseAuth.confirmPasswordReset(oobCode, newPassword).await()
            Resource.Success(Unit)
        } catch (e: FirebaseAuthWeakPasswordException) {
            Resource.Error("הסיסמה חלשה מדי. בחרו סיסמה חזקה יותר")
        } catch (e: FirebaseAuthActionCodeException) {
            Resource.Error("קישור האיפוס אינו תקף או שפג תוקפו. בקשו קישור חדש")
        } catch (e: FirebaseNetworkException) {
            Resource.Error("אין חיבור לאינטרנט. נסו שוב מאוחר יותר")
        } catch (e: Exception) {
            Resource.Error("עדכון הסיסמה נכשל. נסו שוב")
        }
    }

    fun logout() {
        firebaseAuth.signOut()
    }

    private companion object {
        const val USERS = "users"
        const val FIRESTORE_TIMEOUT_MS = 15_000L
    }
}