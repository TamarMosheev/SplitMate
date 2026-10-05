package com.example.myapplication.ui.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.myapplication.R
import com.example.myapplication.utils.Resource
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** Opens the Google account chooser (Credential Manager) and returns a Google ID token. */
class GoogleSignInHelper(private val activity: Activity) {

    private val credentialManager = CredentialManager.create(activity)

    /** Returns null when the user dismissed the account chooser. */
    suspend fun getIdToken(): Resource<String>? {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(activity.getString(R.string.default_web_client_id))
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return try {
            val credential = credentialManager.getCredential(activity, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                Resource.Success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                Resource.Error("ההתחברות עם Google נכשלה. נסו שוב")
            }
        } catch (e: GetCredentialCancellationException) {
            null
        } catch (e: NoCredentialException) {
            Resource.Error("לא נמצא חשבון Google במכשיר. הוסיפו חשבון ונסו שוב")
        } catch (e: GoogleIdTokenParsingException) {
            Resource.Error("ההתחברות עם Google נכשלה. נסו שוב")
        } catch (e: GetCredentialException) {
            Resource.Error("ההתחברות עם Google נכשלה. בדקו את החיבור ונסו שוב")
        }
    }
}
