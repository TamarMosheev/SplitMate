package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.databinding.ActivityRegisterBinding
import com.example.myapplication.ui.auth.GoogleSignInHelper
import com.example.myapplication.ui.auth.RegisterViewModel
import com.example.myapplication.utils.Resource
import com.example.myapplication.utils.applyLightSystemBarsAndInsets
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private val viewModel: RegisterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyLightSystemBarsAndInsets(binding.root)

        binding.btnRegister.setOnClickListener { submit() }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        binding.btnGoogleRegister.setOnClickListener { registerWithGoogle() }
        binding.tvLogin.setOnClickListener { finish() }

        observeViewModel()
    }

    private fun submit() {
        viewModel.register(
            name = binding.etName.text?.toString().orEmpty(),
            email = binding.etEmail.text?.toString().orEmpty(),
            password = binding.etPassword.text?.toString().orEmpty()
        )
    }

    private fun registerWithGoogle() {
        binding.btnGoogleRegister.isEnabled = false
        lifecycleScope.launch {
            try {
                when (val result = GoogleSignInHelper(this@RegisterActivity).getIdToken()) {
                    is Resource.Success -> viewModel.registerWithGoogle(result.data)
                    is Resource.Error -> viewModel.onGoogleFailed(result.message)
                    else -> Unit // dismissed by the user
                }
            } finally {
                binding.btnGoogleRegister.isEnabled = true
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.errors.collect { errors ->
                        binding.tilName.error = errors.nameError
                        binding.tilEmail.error = errors.emailError
                        binding.tilPassword.error = errors.passwordError
                    }
                }

                launch {
                    viewModel.registerState.collect { state ->
                        setLoading(state is Resource.Loading)
                        when (state) {
                            is Resource.Success -> {
                                viewModel.clearRegisterState()
                                openHome()
                            }
                            is Resource.Error -> {
                                toast(state.message)
                                viewModel.clearRegisterState()
                            }
                            else -> Unit
                        }
                    }
                }

                launch {
                    viewModel.googleState.collect { state ->
                        setLoading(state is Resource.Loading)
                        when (state) {
                            is Resource.Success -> {
                                viewModel.clearGoogleState()
                                if (state.data.hasProfile) {
                                    openHome()
                                } else {
                                    startActivity(
                                        Intent(this@RegisterActivity, GoogleProfileActivity::class.java)
                                            .putExtra(GoogleProfileActivity.EXTRA_PHOTO_URL, state.data.photoUrl)
                                    )
                                }
                            }
                            is Resource.Error -> {
                                toast(state.message)
                                viewModel.clearGoogleState()
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressRegister.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnRegister.isEnabled = !loading
        binding.btnGoogleRegister.isEnabled = !loading
    }

    private fun openHome() {
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
