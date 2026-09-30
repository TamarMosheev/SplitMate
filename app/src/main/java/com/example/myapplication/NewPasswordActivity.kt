package com.example.myapplication

import android.content.Intent
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
import com.example.myapplication.databinding.ActivityNewPasswordBinding
import com.example.myapplication.ui.auth.NewPasswordViewModel
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.launch

/**
 * Sets a new password using the Firebase reset code (oobCode) passed in [EXTRA_OOB_CODE].
 * The code comes from the password-reset email link; until the app handles that link
 * (custom action URL / deep link), launching without a code reports an invalid link.
 */
class NewPasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewPasswordBinding
    private val viewModel: NewPasswordViewModel by viewModels()
    private val oobCode: String? by lazy { intent.getStringExtra(EXTRA_OOB_CODE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnUpdatePassword.setOnClickListener { submit() }
        binding.etConfirmPassword.setOnEditorActionListener { _, actionId, _ ->
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
        viewModel.updatePassword(
            oobCode = oobCode,
            password = binding.etNewPassword.text?.toString().orEmpty(),
            confirm = binding.etConfirmPassword.text?.toString().orEmpty()
        )
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
                    viewModel.errors.collect { errors ->
                        binding.tilNewPassword.error = errors.passwordError
                        binding.tilConfirmPassword.error = errors.confirmError
                    }
                }

                launch {
                    viewModel.updateState.collect { state ->
                        val loading = state is Resource.Loading
                        binding.progressUpdate.visibility = if (loading) View.VISIBLE else View.GONE
                        binding.btnUpdatePassword.isEnabled = !loading

                        when (state) {
                            is Resource.Success -> {
                                Toast.makeText(
                                    this@NewPasswordActivity,
                                    "הסיסמה עודכנה בהצלחה",
                                    Toast.LENGTH_LONG
                                ).show()
                                viewModel.clearUpdateState()
                                startActivity(
                                    Intent(this@NewPasswordActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                finish()
                            }
                            is Resource.Error -> {
                                Toast.makeText(
                                    this@NewPasswordActivity,
                                    state.message,
                                    Toast.LENGTH_LONG
                                ).show()
                                viewModel.clearUpdateState()
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_OOB_CODE = "extra_oob_code"
    }
}
