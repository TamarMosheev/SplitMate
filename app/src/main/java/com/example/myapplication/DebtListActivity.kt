package com.example.myapplication

import android.os.Bundle
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
import com.example.myapplication.databinding.ActivityDebtListBinding
import com.example.myapplication.ui.balance.BalanceNavigation
import com.example.myapplication.ui.balance.DebtAdapter
import com.example.myapplication.ui.balance.DebtListContent
import com.example.myapplication.ui.balance.DebtListUiState
import com.example.myapplication.ui.balance.DebtListViewModel
import com.example.myapplication.ui.balance.formatMoney
import kotlinx.coroutines.launch

/** The single reusable debt list: "את חייבת" or "חייבים לך", selected by [EXTRA_OWED_TO_ME]. */
class DebtListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebtListBinding
    private val viewModel: DebtListViewModel by viewModels()
    private val adapter = DebtAdapter(
        onDebtClick = { debt -> BalanceNavigation.openDebtDetails(this, debt.groupId, debt.settlementId) },
        onClaimClick = { debt -> viewModel.claimPayment(debt) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebtListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.debtListRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.tvTitle.text = if (viewModel.owedToMe) "חייבים לך" else "את חייבת"
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { viewModel.load() }
        binding.rvDebts.layoutManager = LinearLayoutManager(this)
        binding.rvDebts.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.messages.collect { Toast.makeText(this@DebtListActivity, it, Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    /** Back from Debt Details: the debt may have been paid or confirmed, so reload from the server. */
    override fun onRestart() {
        super.onRestart()
        viewModel.load(silent = true)
    }

    private fun render(state: DebtListUiState) {
        binding.progress.visibility = if (state is DebtListUiState.Loading) View.VISIBLE else View.GONE
        binding.errorGroup.visibility = if (state is DebtListUiState.Error) View.VISIBLE else View.GONE
        binding.content.visibility = if (state is DebtListUiState.Success) View.VISIBLE else View.GONE
        when (state) {
            is DebtListUiState.Error -> binding.tvError.text = state.message
            is DebtListUiState.Success -> renderContent(state.content)
            DebtListUiState.Loading -> Unit
        }
    }

    private fun renderContent(c: DebtListContent) {
        binding.tvTotal.text = formatMoney(c.total)
        binding.tvTotal.setTextColor(
            ContextCompat.getColor(this, if (c.owedToMe) R.color.home_positive else R.color.home_negative)
        )
        adapter.submitList(c.debts)
        binding.tvEmpty.text = if (c.owedToMe) "אף אחד לא חייב לך כרגע" else "אין לך חובות פתוחים"
        binding.tvEmpty.visibility = if (c.debts.isEmpty()) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_OWED_TO_ME = DebtListViewModel.EXTRA_OWED_TO_ME
    }
}
