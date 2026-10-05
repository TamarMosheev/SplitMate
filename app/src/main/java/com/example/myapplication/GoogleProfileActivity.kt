package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.databinding.ActivityGoogleProfileBinding
import com.example.myapplication.repository.AuthRepository
import com.example.myapplication.ui.auth.RegisterViewModel
import com.example.myapplication.utils.Resource
import com.example.myapplication.utils.applyLightSystemBarsAndInsets
import kotlinx.coroutines.launch

/**
 * Shown after Google authentication succeeds for a user with no users/{uid} profile yet.
 * All shown data comes from the signed-in Firebase user.
 */
class GoogleProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGoogleProfileBinding
    private val viewModel: RegisterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val user = AuthRepository().currentUser
        if (user == null) {
            // No authenticated session to complete a profile for.
            finish()
            return
        }

        binding = ActivityGoogleProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyLightSystemBarsAndInsets(binding.root)

        val displayName = user.displayName?.takeIf { it.isNotBlank() }
        binding.tvWelcome.text = if (displayName != null) "ברוכים הבאים, $displayName" else "ברוכים הבאים"
        binding.tvAvatar.text = (displayName ?: user.email)?.trim()?.firstOrNull()?.uppercase().orEmpty()
        binding.etGoogleEmail.setText(user.email.orEmpty())
        if (savedInstanceState == null) binding.etGoogleName.setText(displayName.orEmpty())

        binding.btnBack.setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        binding.btnContinue.setOnClickListener { submit() }
        binding.etGoogleName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }

        observeViewModel()
    }

    private fun submit() {
        viewModel.completeGoogleProfile(binding.etGoogleName.text?.toString().orEmpty())
    }

    /** Leaving without a profile: sign out the half-registered Google session. */
    private fun leave() {
        viewModel.cancelGoogleRegistration()
        finish() // back to the registration screen
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.errors.collect { binding.tilGoogleName.error = it.nameError }
                }

                launch {
                    viewModel.completeState.collect { state ->
                        val loading = state is Resource.Loading
                        binding.progressContinue.visibility = if (loading) View.VISIBLE else View.GONE
                        binding.btnContinue.isEnabled = !loading

                        when (state) {
                            is Resource.Success -> {
                                viewModel.clearCompleteState()
                                startActivity(
                                    Intent(this@GoogleProfileActivity, HomeActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                )
                                finish()
                            }
                            is Resource.Error -> {
                                Toast.makeText(this@GoogleProfileActivity, state.message, Toast.LENGTH_LONG).show()
                                viewModel.clearCompleteState()
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Reserved for showing the Google photo; avatar currently uses the user's initial. */
        const val EXTRA_PHOTO_URL = "extra_photo_url"
    }
}
