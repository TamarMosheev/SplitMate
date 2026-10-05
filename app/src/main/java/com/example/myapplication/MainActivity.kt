package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
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
import com.example.myapplication.databinding.ActivityMainBinding
import com.example.myapplication.ui.auth.GoogleSignInHelper
import com.example.myapplication.ui.auth.LoginViewModel
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        setupListeners()
        observeViewModel()
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

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text?.toString().orEmpty()
            val password = binding.etPassword.text?.toString().orEmpty()
            viewModel.login(email, password)
        }

        // UI callbacks only - backend not wired yet
        binding.tvForgotPassword.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }
        binding.btnGoogle.setOnClickListener { signInWithGoogle() }
        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun signInWithGoogle() {
        binding.btnGoogle.isEnabled = false
        lifecycleScope.launch {
            try {
                when (val result = GoogleSignInHelper(this@MainActivity).getIdToken()) {
                    is Resource.Success -> viewModel.loginWithGoogle(result.data)
                    is Resource.Error -> viewModel.onGoogleSignInFailed(result.message)
                    else -> Unit // dismissed by the user
                }
            } finally {
                binding.btnGoogle.isEnabled = true
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.formErrors.collect { errors ->
                        binding.tilEmail.error = errors.emailError
                        binding.tilPassword.error = errors.passwordError
                    }
                }

                launch {
                    viewModel.loginState.collect { state ->
                        when (state) {
                            is Resource.Loading -> {
                                binding.progressBar.visibility = View.VISIBLE
                                binding.btnLogin.isEnabled = false
                            }
                            is Resource.Success -> {
                                binding.progressBar.visibility = View.GONE
                                binding.btnLogin.isEnabled = true
                                Toast.makeText(
                                    this@MainActivity,
                                    "ברוך הבא, ${state.data.email}!",
                                    Toast.LENGTH_LONG
                                ).show()
                                startActivity(Intent(this@MainActivity, HomeActivity::class.java))
                                finish()
                            }
                            is Resource.Error -> {
                                binding.progressBar.visibility = View.GONE
                                binding.btnLogin.isEnabled = true
                                Toast.makeText(
                                    this@MainActivity,
                                    state.message,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            null -> {
                                binding.progressBar.visibility = View.GONE
                                binding.btnLogin.isEnabled = true
                            }
                        }
                    }
                }
            }
        }
    }
}