package com.example.myapplication

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.data.model.GroupMember
import com.example.myapplication.databinding.ActivityAddExpenseBinding
import com.example.myapplication.databinding.ItemExpenseParticipantBinding
import com.example.myapplication.databinding.ItemPayerChipBinding
import com.example.myapplication.ui.expense.AddExpenseUiState
import com.example.myapplication.ui.expense.AddExpenseViewModel
import com.example.myapplication.ui.expense.SplitMode
import java.math.BigDecimal
import java.util.Locale
import kotlinx.coroutines.launch

class AddExpenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddExpenseBinding
    private val viewModel: AddExpenseViewModel by viewModels()

    private var payerKey: Any? = null
    private var participantsKey: Any? = null
    private val participantRows = mutableMapOf<String, ItemExpenseParticipantBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddExpenseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.expenseRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        binding.btnClose.setOnClickListener { finish() }
        binding.etAmount.doAfterTextChanged { viewModel.onAmountChanged(it?.toString().orEmpty()) }
        binding.etDescription.doAfterTextChanged { viewModel.onDescriptionChanged(it?.toString().orEmpty()) }
        binding.btnQuick10.setOnClickListener { viewModel.addToAmount(10) }
        binding.btnQuick50.setOnClickListener { viewModel.addToAmount(50) }
        binding.btnQuick100.setOnClickListener { viewModel.addToAmount(100) }
        binding.btnQuick500.setOnClickListener { viewModel.addToAmount(500) }
        binding.cardSplitEqual.setOnClickListener { viewModel.onSplitModeChanged(SplitMode.EQUAL) }
        binding.cardSplitExact.setOnClickListener { viewModel.onSplitModeChanged(SplitMode.EXACT) }
        binding.btnSave.setOnClickListener { viewModel.save() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                // Navigate back only after Firestore confirmed the save.
                launch { viewModel.saved.collect { finish() } }
            }
        }
    }

    private fun render(s: AddExpenseUiState) {
        binding.progressLoad.visibility = if (s.loading) View.VISIBLE else View.GONE

        binding.tvGroupChip.visibility = if (s.loading || s.loadError != null) View.GONE else View.VISIBLE
        binding.tvGroupChip.text = listOf(s.groupIcon, s.groupName).filter { it.isNotBlank() }.joinToString(" ")

        if (binding.etAmount.text.toString() != s.amountText) {
            binding.etAmount.setText(s.amountText)
            binding.etAmount.setSelection(s.amountText.length)
        }

        renderPayers(s)
        renderSplitCards(s)
        renderParticipants(s)

        val message = s.loadError ?: s.error
        binding.tvError.visibility = if (message != null) View.VISIBLE else View.GONE
        binding.tvError.text = message

        binding.btnSave.isEnabled = !s.saving && !s.loading && s.loadError == null
        binding.btnSave.text = if (s.saving) "שומר..." else "שמור הוצאה"
    }

    private fun renderPayers(s: AddExpenseUiState) {
        val key = s.members to s.payerId
        if (key == payerKey) return
        payerKey = key

        val density = resources.displayMetrics.density
        val available = resources.displayMetrics.widthPixels - (2 * (24 + 12) * density)
        // About 3 chips visible; slightly narrower when more exist so the next one peeks out.
        val chipWidth = (available / if (s.members.size > 3) 3.4f else 3f - 0.1f).toInt()

        binding.payerRow.removeAllViews()
        s.members.forEach { member ->
            val chip = ItemPayerChipBinding.inflate(LayoutInflater.from(this), binding.payerRow, false)
            chip.tvPayerName.text = displayName(member)
            chip.root.setBackgroundResource(
                if (member.uid == s.payerId) R.drawable.bg_payer_selected else R.drawable.bg_payer_unselected
            )
            chip.root.setOnClickListener { viewModel.onPayerSelected(member.uid) }
            val params = android.widget.LinearLayout.LayoutParams(chipWidth, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            params.marginEnd = (8 * density).toInt()
            binding.payerRow.addView(chip.root, params)
        }
    }

    private fun renderSplitCards(s: AddExpenseUiState) {
        styleSplitCard(binding.cardSplitEqual, s.splitMode == SplitMode.EQUAL)
        styleSplitCard(binding.cardSplitExact, s.splitMode == SplitMode.EXACT)
    }

    private fun styleSplitCard(card: com.google.android.material.card.MaterialCardView, selected: Boolean) {
        card.strokeColor = ContextCompat.getColor(this, if (selected) R.color.home_primary else R.color.white)
        card.setCardBackgroundColor(
            ContextCompat.getColor(this, if (selected) R.color.home_light_surface else R.color.white)
        )
    }

    private fun renderParticipants(s: AddExpenseUiState) {
        val key = Triple(s.members, s.participantIds, s.splitMode)
        if (key != participantsKey) {
            participantsKey = key
            rebuildParticipants(s)
        }
        // Amount labels update in place so an exact-amount field never loses focus while typing.
        val shares = s.equalShares
        participantRows.forEach { (uid, row) ->
            val selected = uid in s.participantIds
            if (s.splitMode == SplitMode.EQUAL || !selected) {
                row.tvParticipantAmount.text = formatShekel(if (selected) shares[uid] else null)
            }
        }
    }

    private fun rebuildParticipants(s: AddExpenseUiState) {
        val container = binding.participantsContainer
        container.removeAllViews()
        participantRows.clear()
        val density = resources.displayMetrics.density

        s.members.forEachIndexed { index, member ->
            val row = ItemExpenseParticipantBinding.inflate(LayoutInflater.from(this), container, false)
            val selected = member.uid in s.participantIds
            row.tvParticipantName.text = displayName(member)
            row.cbParticipant.isChecked = selected
            row.cbParticipant.setOnCheckedChangeListener { _, _ -> viewModel.onParticipantToggled(member.uid) }

            val exact = s.splitMode == SplitMode.EXACT && selected
            row.etParticipantExact.visibility = if (exact) View.VISIBLE else View.GONE
            row.tvParticipantAmount.visibility = if (exact) View.GONE else View.VISIBLE
            if (exact) {
                row.etParticipantExact.setText(s.exactTexts[member.uid].orEmpty())
                row.etParticipantExact.doAfterTextChanged {
                    viewModel.onExactAmountChanged(member.uid, it?.toString().orEmpty())
                }
            }

            container.addView(row.root)
            participantRows[member.uid] = row

            if (index < s.members.lastIndex) {
                container.addView(View(this).apply {
                    setBackgroundColor(ContextCompat.getColor(context, R.color.home_light_surface))
                }, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
                ).apply { marginStart = (16 * density).toInt(); marginEnd = (16 * density).toInt() })
            }
        }
    }

    private fun displayName(member: GroupMember) = member.name.ifBlank { "משתמש" }

    private fun formatShekel(value: BigDecimal?): String =
        "₪" + String.format(Locale.US, "%.2f", (value ?: BigDecimal.ZERO).toDouble())
}
