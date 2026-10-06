package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.databinding.ActivityExpenseDetailsBinding
import com.example.myapplication.databinding.ItemGroupMemberBinding
import com.example.myapplication.ui.balance.formatMoney
import com.example.myapplication.ui.expense.ExpenseDetailsContent
import com.example.myapplication.ui.expense.ExpenseDetailsUiState
import com.example.myapplication.ui.expense.ExpenseDetailsViewModel
import com.example.myapplication.ui.expense.ExpenseResultUi
import kotlinx.coroutines.launch

/**
 * The one Expense Details screen. Every place that shows an expense opens it with the real
 * groupId + expenseId (see [ExpenseDetailsViewModel.EXTRA_GROUP_ID] / [ExpenseDetailsViewModel.EXTRA_EXPENSE_ID]).
 */
class ExpenseDetailsActivity : AppCompatActivity() {

    companion object {
        /** The only way the app opens an expense: always real ids, never expense data. */
        fun intent(context: Context, groupId: String, expenseId: String): Intent =
            Intent(context, ExpenseDetailsActivity::class.java)
                .putExtra(ExpenseDetailsViewModel.EXTRA_GROUP_ID, groupId)
                .putExtra(ExpenseDetailsViewModel.EXTRA_EXPENSE_ID, expenseId)
    }

    private lateinit var binding: ActivityExpenseDetailsBinding
    private val viewModel: ExpenseDetailsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExpenseDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.expenseRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { viewModel.load() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    /** Back from any other screen: re-read the expense so it never shows stale data. */
    override fun onRestart() {
        super.onRestart()
        viewModel.load(silent = true)
    }

    private fun render(state: ExpenseDetailsUiState) {
        binding.progressExpense.visibility = if (state is ExpenseDetailsUiState.Loading) View.VISIBLE else View.GONE
        binding.errorGroup.visibility = if (state is ExpenseDetailsUiState.Error) View.VISIBLE else View.GONE
        binding.contentScroll.visibility = if (state is ExpenseDetailsUiState.Success) View.VISIBLE else View.GONE

        when (state) {
            is ExpenseDetailsUiState.Error -> binding.tvExpenseError.text = state.message
            is ExpenseDetailsUiState.Success -> renderContent(state.content)
            ExpenseDetailsUiState.Loading -> Unit
        }
    }

    private fun renderContent(c: ExpenseDetailsContent) {
        binding.tvExpenseTitle.text = c.description
        binding.tvExpenseTotal.text = formatMoney(c.amount)
        binding.tvGroupName.text = c.groupName
        binding.tvGroupIcon.text = c.groupIcon
        binding.tvGroupIcon.visibility = if (c.groupIcon.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.tvExpenseDate.text = c.dateText
        binding.tvExpenseDate.visibility = if (c.dateText == null) View.GONE else View.VISIBLE
        binding.tvSplitBadge.text = c.splitLabel
        binding.tvSplitBadge.visibility = if (c.splitLabel == null) View.GONE else View.VISIBLE

        renderResult(c.result)

        // Paid by
        binding.payerHolder.removeAllViews()
        addRow(
            binding.payerHolder,
            name = c.payerName,
            isMe = c.payerIsMe,
            amountText = null,
            isLast = true
        )

        // Split between the participants
        binding.participantsList.removeAllViews()
        c.participants.forEachIndexed { i, p ->
            addRow(
                binding.participantsList,
                name = p.name,
                isMe = p.isMe,
                amountText = p.share?.let { formatMoney(it) },
                isLast = i == c.participants.lastIndex
            )
        }
        binding.tvSharesNote.text = c.sharesNote
        binding.tvSharesNote.visibility = if (c.sharesNote == null) View.GONE else View.VISIBLE
    }

    private fun renderResult(result: ExpenseResultUi?) {
        val view = binding.tvResult
        when (result) {
            null -> view.visibility = View.GONE
            is ExpenseResultUi.Receive -> {
                view.text = "מגיע לך ${formatMoney(result.amount)} מהחברים"
                view.setTextColor(ContextCompat.getColor(this, R.color.home_positive))
                view.setBackgroundResource(R.drawable.bg_badge_positive)
                view.visibility = View.VISIBLE
            }
            is ExpenseResultUi.Owe -> {
                view.text = "את חייבת ל${result.payerName ?: "משתמש"} ${formatMoney(result.amount)}"
                view.setTextColor(ContextCompat.getColor(this, R.color.home_negative))
                view.setBackgroundResource(R.drawable.bg_badge_negative)
                view.visibility = View.VISIBLE
            }
        }
    }

    /** One avatar + name (+ amount) row; the same row layout the Group Details member list uses. */
    private fun addRow(parent: ViewGroup, name: String?, isMe: Boolean, amountText: String?, isLast: Boolean) {
        val row = ItemGroupMemberBinding.inflate(LayoutInflater.from(this), parent, false)
        val shown = name ?: "משתמש"
        row.tvMemberAvatar.text = name?.trim()?.firstOrNull()?.toString() ?: "?"
        row.tvMemberName.text = if (isMe) "$shown (אני)" else shown
        row.memberDivider.visibility = if (isLast) View.GONE else View.VISIBLE
        if (amountText == null) {
            row.tvMemberBalance.visibility = View.GONE
        } else {
            row.tvMemberBalance.text = amountText
            row.tvMemberBalance.setTextColor(ContextCompat.getColor(this, R.color.home_navy))
            row.tvMemberBalance.background = null
        }
        parent.addView(row.root)
    }
}
