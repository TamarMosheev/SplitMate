package com.example.myapplication

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.databinding.ActivityMainBinding
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

        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text?.toString().orEmpty()
            val password = binding.etPassword.text?.toString().orEmpty()
            viewModel.login(email, password)
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