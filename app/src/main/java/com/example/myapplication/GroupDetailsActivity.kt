package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.myapplication.databinding.ActivityGroupDetailsBinding
import com.example.myapplication.databinding.ItemMemberAvatarBinding
import com.example.myapplication.ui.expense.AddExpenseViewModel
import com.example.myapplication.ui.group.ExpenseAdapter
import com.example.myapplication.ui.group.GroupDetailsUiState
import com.example.myapplication.ui.group.GroupDetailsViewModel
import kotlinx.coroutines.launch

class GroupDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupDetailsBinding
    private val viewModel: GroupDetailsViewModel by viewModels()
    private val expenseAdapter = ExpenseAdapter()
    private var membersKey: Any? = null

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
        binding.rvExpenses.layoutManager = LinearLayoutManager(this)
        binding.rvExpenses.adapter = expenseAdapter

        // The same real groupId this screen was opened with goes straight to Add Expense.
        val groupId = intent.getStringExtra(GroupDetailsViewModel.EXTRA_GROUP_ID).orEmpty()
        binding.btnAddExpense.setOnClickListener {
            startActivity(
                Intent(this, AddExpenseActivity::class.java)
                    .putExtra(AddExpenseViewModel.EXTRA_GROUP_ID, groupId)
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(s: GroupDetailsUiState) {
        binding.progressLoad.visibility = if (s.loading) View.VISIBLE else View.GONE

        val group = s.group
        binding.tvGroupName.text = group?.name.orEmpty()
        binding.tvGroupIcon.text = group?.icon
        binding.tvGroupIcon.visibility = if (group != null && group.icon.isNotBlank()) View.VISIBLE else View.GONE
        binding.tvMemberCount.text = when (val n = group?.members?.size ?: 0) {
            0 -> ""
            1 -> "חבר אחד"
            else -> "$n חברים"
        }

        val members = group?.members.orEmpty()
        if (members != membersKey) {
            membersKey = members
            binding.membersRow.removeAllViews()
            members.forEach { m ->
                val item = ItemMemberAvatarBinding.inflate(LayoutInflater.from(this), binding.membersRow, false)
                item.tvMemberAvatarName.text = m.name.ifBlank { "משתמש" }
                binding.membersRow.addView(item.root)
            }
        }

        expenseAdapter.submitList(s.expenses)
        binding.tvEmptyExpenses.visibility =
            if (s.expensesLoaded && s.expensesError == null && s.expenses.isEmpty()) View.VISIBLE else View.GONE

        val message = s.groupError ?: s.expensesError
        binding.tvError.visibility = if (message != null) View.VISIBLE else View.GONE
        binding.tvError.text = message

        // No add-expense without a loaded group
        binding.btnAddExpense.isEnabled = group != null
    }
}
