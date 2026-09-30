package com.example.myapplication

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.databinding.ActivityForgotPasswordBinding
import com.example.myapplication.ui.auth.LoginViewModel
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.launch

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSendLink.setOnClickListener { submit() }
        binding.etResetEmail.setOnEditorActionListener { _, actionId, _ ->
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
        viewModel.sendPasswordReset(binding.etResetEmail.text?.toString().orEmpty())
    }

    private fun applyWindowInsets() {
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.resetEmailError.collect { binding.tilResetEmail.error = it }
                }

                launch {
                    viewModel.passwordResetState.collect { state ->
                        val loading = state is Resource.Loading
                        binding.progressReset.visibility = if (loading) View.VISIBLE else View.GONE
                        binding.btnSendLink.isEnabled = !loading

                        when (state) {
                            is Resource.Success -> {
                                Toast.makeText(
                                    this@ForgotPasswordActivity,
                                    "נשלח אלייך מייל לאיפוס הסיסמה",
                                    Toast.LENGTH_LONG
                                ).show()
                                binding.etResetEmail.text?.clear()
                                viewModel.clearPasswordResetState()
                            }
                            is Resource.Error -> {
                                Toast.makeText(
                                    this@ForgotPasswordActivity,
                                    state.message,
                                    Toast.LENGTH_LONG
                                ).show()
                                viewModel.clearPasswordResetState()
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}
