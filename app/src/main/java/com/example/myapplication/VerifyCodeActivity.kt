package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
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
import com.example.myapplication.databinding.ActivityVerifyCodeBinding
import com.example.myapplication.ui.auth.VerifyCodeEvent
import com.example.myapplication.ui.auth.VerifyCodeViewModel
import kotlinx.coroutines.launch

/**
 * Password reset step 2: six digit boxes for the code emailed by the backend. Only the email is passed in
 * ([EXTRA_EMAIL]); on a verified code the server's resetToken goes on to [NewPasswordActivity].
 */
class VerifyCodeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVerifyCodeBinding
    private val viewModel: VerifyCodeViewModel by viewModels()
    private val email: String by lazy { intent.getStringExtra(EXTRA_EMAIL).orEmpty() }
    private val boxes = mutableListOf<EditText>()
    private var busy = false
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerifyCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        if (email.isBlank()) {
            finish()
            return
        }

        buildBoxes()
        binding.btnBack.setOnClickListener { finish() }
        binding.btnVerify.setOnClickListener { verify() }
        binding.btnResend.setOnClickListener { viewModel.resend(email) }
        updateVerifyEnabled()
        boxes.first().requestFocus()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.busy.collect { loading ->
                        busy = loading
                        binding.progressVerify.visibility = if (loading) View.VISIBLE else View.GONE
                        binding.btnResend.isEnabled = !loading
                        binding.btnResend.alpha = if (loading) 0.5f else 1f
                        updateVerifyEnabled()
                    }
                }
                launch { viewModel.events.collect(::onEvent) }
            }
        }
    }

    private fun onEvent(event: VerifyCodeEvent) {
        when (event) {
            is VerifyCodeEvent.Verified -> startActivity(
                Intent(this, NewPasswordActivity::class.java)
                    .putExtra(NewPasswordActivity.EXTRA_RESET_TOKEN, event.resetToken)
            )
            VerifyCodeEvent.Resent -> {
                clearBoxes()
                binding.tvCodeError.visibility = View.GONE
                Toast.makeText(this, "קוד חדש נשלח למייל", Toast.LENGTH_LONG).show()
            }
            is VerifyCodeEvent.Message -> {
                binding.tvCodeError.text = event.text
                binding.tvCodeError.visibility = View.VISIBLE
                Toast.makeText(this, event.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun verify() {
        viewModel.verify(email, code())
    }

    private fun code() = boxes.joinToString("") { it.text.toString() }

    private fun updateVerifyEnabled() {
        val enabled = code().length == VerifyCodeViewModel.CODE_LENGTH && !busy
        binding.btnVerify.isEnabled = enabled
        binding.btnVerify.alpha = if (enabled) 1f else 0.5f
    }

    private fun clearBoxes() {
        updating = true
        boxes.forEach { it.text.clear() }
        updating = false
        updateVerifyEnabled()
        boxes.first().requestFocus()
    }

    private fun buildBoxes() {
        val margin = (4 * resources.displayMetrics.density).toInt()
        repeat(VerifyCodeViewModel.CODE_LENGTH) { index ->
            val box = EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, (58 * resources.displayMetrics.density).toInt(), 1f)
                    .apply { setMargins(margin, 0, margin, 0) }
                background = getDrawable(R.drawable.bg_otp_box)
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 24f
                setTextColor(getColor(R.color.login_text))
                isCursorVisible = false
                setSelectAllOnFocus(true)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                contentDescription = "ספרה ${index + 1}"
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = onBoxChanged(index, s?.toString().orEmpty())
                })
                setOnKeyListener { _, keyCode, event ->
                    // Backspace on an empty box: clear and go back to the previous one.
                    if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN &&
                        text.isEmpty() && index > 0
                    ) {
                        boxes[index - 1].apply { text.clear(); requestFocus() }
                        true
                    } else {
                        false
                    }
                }
            }
            boxes += box
            binding.codeRow.addView(box)
        }
    }

    /** One digit per box; anything longer (a paste, or typing over a filled box) is spread over the boxes. */
    private fun onBoxChanged(index: Int, text: String) {
        if (updating) return
        val digits = text.filter { it.isDigit() }
        if (digits.length > 1) {
            // A full code always fills from the first box, wherever it was pasted.
            val start = if (digits.length >= VerifyCodeViewModel.CODE_LENGTH) 0 else index
            updating = true
            boxes.forEach { if (digits.length >= VerifyCodeViewModel.CODE_LENGTH) it.text.clear() }
            var i = start
            digits.take(boxes.size - start).forEach { d -> boxes[i++].setText(d.toString()) }
            updating = false
            boxes[minOf(i, boxes.size - 1)].requestFocus()
        } else if (digits.length == 1) {
            if (text != digits) {
                updating = true
                boxes[index].setText(digits)
                updating = false
            }
            if (index < boxes.size - 1) boxes[index + 1].requestFocus()
        } else if (text.isNotEmpty()) {
            updating = true
            boxes[index].text.clear()
            updating = false
        }
        binding.tvCodeError.visibility = View.GONE
        updateVerifyEnabled()
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

    companion object {
        const val EXTRA_EMAIL = "extra_email"
    }
}
