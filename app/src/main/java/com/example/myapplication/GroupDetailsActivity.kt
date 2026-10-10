package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.myapplication.databinding.ActivityGroupDetailsBinding
import com.example.myapplication.databinding.ItemGroupMemberBinding
import com.example.myapplication.ui.balance.BalanceNavigation
import com.example.myapplication.ui.balance.formatMoney
import com.example.myapplication.ui.balance.formatSignedMoney
import com.example.myapplication.ui.expense.AddExpenseViewModel
import com.example.myapplication.ui.group.AddMemberViewModel
import com.example.myapplication.ui.group.ExpenseAdapter
import com.example.myapplication.ui.group.GroupDetailsContent
import com.example.myapplication.ui.group.GroupDetailsUiState
import com.example.myapplication.ui.group.GroupDetailsViewModel
import com.example.myapplication.ui.group.MemberBalanceUi
import com.example.myapplication.ui.group.bindGroupDeleteAction
import com.example.myapplication.ui.group.confirmDeleteGroup
import java.math.BigDecimal
import kotlinx.coroutines.launch

class GroupDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupDetailsBinding
    private val viewModel: GroupDetailsViewModel by viewModels()
    private val expenseAdapter = ExpenseAdapter { expense ->
        val groupId = intent.getStringExtra(GroupDetailsViewModel.EXTRA_GROUP_ID).orEmpty()
        startActivity(ExpenseDetailsActivity.intent(this, groupId, expense.id))
    }
    private var renderedMembers: List<MemberBalanceUi>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.detailsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { viewModel.load() }
        binding.rvExpenses.layoutManager = LinearLayoutManager(this)
        binding.rvExpenses.adapter = expenseAdapter

        // The same real groupId this screen was opened with goes straight to Add Expense.
        val groupId = intent.getStringExtra(GroupDetailsViewModel.EXTRA_GROUP_ID).orEmpty()
        binding.groupBalanceCard.setOnClickListener { BalanceNavigation.openMyBalance(this) }
        binding.btnAddExpense.setOnClickListener {
            startActivity(
                Intent(this, AddExpenseActivity::class.java)
                    .putExtra(AddExpenseViewModel.EXTRA_GROUP_ID, groupId)
            )
        }

        // Any current member may add a registered user; the group reloads in onRestart when this returns.
        binding.btnAddMember.setOnClickListener {
            startActivity(
                Intent(this, AddMemberActivity::class.java)
                    .putExtra(AddMemberViewModel.EXTRA_GROUP_ID, groupId)
            )
        }

        // The shared confirmation; the delete itself runs in the ViewModel's shared GroupDeleter.
        binding.btnDeleteGroup.setOnClickListener {
            confirmDeleteGroup(this) { viewModel.deleteGroup() }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.deleter.deletingIds.collect { ids ->
                        binding.btnDeleteGroup.isEnabled = ids.isEmpty()
                        binding.btnAddExpense.isEnabled = ids.isEmpty() && viewModel.state.value is GroupDetailsUiState.Success
                    }
                }
                launch {
                    viewModel.deleter.events.collect {
                        Toast.makeText(this@GroupDetailsActivity, it.message, Toast.LENGTH_LONG).show()
                        // The group is gone: never stay on a deleted group's screen. The previous screen reloads
                        // its lists from the server when it comes back to the foreground.
                        if (it.groupIsGone) finish()
                    }
                }
            }
        }
    }

    /** Back from Add Expense, Debt Details or any other screen: reload the real data. */
    override fun onRestart() {
        super.onRestart()
        viewModel.load(silent = true)
    }

    private fun render(state: GroupDetailsUiState) {
        binding.progressLoad.visibility = if (state is GroupDetailsUiState.Loading) View.VISIBLE else View.GONE
        binding.errorGroup.visibility = if (state is GroupDetailsUiState.Error) View.VISIBLE else View.GONE
        binding.contentScroll.visibility = if (state is GroupDetailsUiState.Success) View.VISIBLE else View.GONE
        binding.btnAddExpense.isEnabled = state is GroupDetailsUiState.Success

        when (state) {
            is GroupDetailsUiState.Error -> binding.tvError.text = state.message
            is GroupDetailsUiState.Success -> renderContent(state.content)
            GroupDetailsUiState.Loading -> Unit
        }
    }

    private fun renderContent(c: GroupDetailsContent) {
        // Same shared rule as the Home / My Balance cards, applied explicitly on every render.
        bindGroupDeleteAction(
            "GroupDetails", "GroupDetailsActivity(activity_group_details)", c.id, c.name, c.createdBy,
            binding.btnDeleteGroup, viewModel.deleter.deletingIds.value.isNotEmpty()
        )
        binding.tvGroupName.text = c.name
        binding.tvGroupIcon.text = c.icon
        binding.tvGroupIcon.visibility = if (c.icon.isNullOrBlank()) View.GONE else View.VISIBLE

        // Group balance card: "+₪300.00" / "-₪80.00" / "₪0.00".
        val mine = c.myBalance
        binding.tvGroupBalance.text = when {
            mine == null -> "—"
            mine.signum() == 0 -> formatMoney(BigDecimal.ZERO)
            else -> formatSignedMoney(mine)
        }
        binding.tvBalanceError.text = c.balanceError
        binding.tvBalanceError.visibility = if (c.balanceError == null) View.GONE else View.VISIBLE

        if (c.members != renderedMembers) {
            renderedMembers = c.members
            binding.membersList.removeAllViews()
            c.members.forEachIndexed { index, m ->
                val row = ItemGroupMemberBinding.inflate(LayoutInflater.from(this), binding.membersList, false)
                bindMember(row, m, isLast = index == c.members.lastIndex)
                binding.membersList.addView(row.root)
            }
        }

        expenseAdapter.submitList(c.expenses)
        binding.tvExpensesError.text = c.expensesError
        binding.tvExpensesError.visibility = if (c.expensesError == null) View.GONE else View.VISIBLE
        binding.tvEmptyExpenses.visibility =
            if (c.expensesError == null && c.expenses.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun bindMember(row: ItemGroupMemberBinding, m: MemberBalanceUi, isLast: Boolean) {
        val name = m.name ?: "משתמש"
        row.tvMemberAvatar.text = m.name?.trim()?.firstOrNull()?.toString() ?: "?"
        row.tvMemberName.text = if (m.isMe) "$name (אני)" else name
        row.memberDivider.visibility = if (isLast) View.GONE else View.VISIBLE

        val b = m.balance
        val (text, color, background) = when {
            b == null -> Triple("—", R.color.home_muted, null)
            b.signum() > 0 -> Triple(formatSignedMoney(b), R.color.home_positive, R.drawable.bg_badge_positive)
            b.signum() < 0 -> Triple(formatSignedMoney(b), R.color.home_negative, R.drawable.bg_badge_negative)
            else -> Triple(formatMoney(b), R.color.home_muted, null)
        }
        row.tvMemberBalance.text = text
        row.tvMemberBalance.setTextColor(ContextCompat.getColor(this, color))
        if (background != null) row.tvMemberBalance.setBackgroundResource(background)
        else row.tvMemberBalance.background = null
    }
}
