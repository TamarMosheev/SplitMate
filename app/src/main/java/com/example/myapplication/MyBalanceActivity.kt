package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import com.example.myapplication.ui.balance.DebtDetailsViewModel
import android.widget.Toast
import com.example.myapplication.ui.balance.GroupBalanceUi
import com.example.myapplication.ui.group.GroupDetailsViewModel
import com.example.myapplication.ui.group.confirmDeleteGroup
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
import com.example.myapplication.databinding.ActivityMyBalanceBinding
import com.example.myapplication.ui.balance.DebtAdapter
import com.example.myapplication.ui.balance.GroupBalanceAdapter
import com.example.myapplication.ui.balance.MyBalanceContent
import com.example.myapplication.ui.balance.MyBalanceUiState
import com.example.myapplication.ui.balance.MyBalanceViewModel
import com.example.myapplication.ui.balance.formatMoney
import com.example.myapplication.ui.balance.isSettled
import kotlinx.coroutines.launch

class MyBalanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyBalanceBinding
    private val viewModel: MyBalanceViewModel by viewModels()
    private val debtAdapter = DebtAdapter { debt ->
        startActivity(
            Intent(this, DebtDetailsActivity::class.java)
                .putExtra(DebtDetailsViewModel.EXTRA_GROUP_ID, debt.groupId)
                .putExtra(DebtDetailsViewModel.EXTRA_SETTLEMENT_ID, debt.settlementId)
        )
    }
    // Only the real groupId is passed; the details screen loads everything fresh from the backend.
    private val groupAdapter = GroupBalanceAdapter(
        onGroupClick = { group ->
            startActivity(
                Intent(this, GroupDetailsActivity::class.java)
                    .putExtra(GroupDetailsViewModel.EXTRA_GROUP_ID, group.id)
            )
        },
        // The shared confirmation; the delete itself runs in the ViewModel's shared GroupDeleter.
        onDeleteClick = { group -> confirmDeleteGroup(this) { viewModel.deleteGroup(group) } }
    )
    private var shownGroups: List<GroupBalanceUi> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyBalanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.balanceRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { viewModel.load() }
        binding.rvDebts.layoutManager = LinearLayoutManager(this)
        binding.rvDebts.adapter = debtAdapter
        binding.rvGroupBalances.layoutManager = LinearLayoutManager(this)
        binding.rvGroupBalances.adapter = groupAdapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.deleter.deletingIds.collect { submitGroups() } }
                launch {
                    viewModel.deleter.events.collect {
                        Toast.makeText(this@MyBalanceActivity, it.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** The group list with each group's "deleting" state merged in. */
    private fun submitGroups() {
        val deleting = viewModel.deleter.deletingIds.value
        groupAdapter.submitList(shownGroups.map { it.copy(isDeleting = it.id in deleting) })
    }

    /** Back from Debt Details (or anywhere): the debt may have been paid, so reload from the server. */
    override fun onRestart() {
        super.onRestart()
        viewModel.load()
    }

    private fun render(state: MyBalanceUiState) {
        binding.progressBalance.visibility = if (state is MyBalanceUiState.Loading) View.VISIBLE else View.GONE
        binding.errorGroup.visibility = if (state is MyBalanceUiState.Error) View.VISIBLE else View.GONE
        binding.contentScroll.visibility = if (state is MyBalanceUiState.Success) View.VISIBLE else View.GONE

        when (state) {
            is MyBalanceUiState.Error -> binding.tvBalanceError.text = state.message
            is MyBalanceUiState.Success -> renderContent(state.content)
            MyBalanceUiState.Loading -> Unit
        }
    }

    private fun renderContent(c: MyBalanceContent) {
        val total = c.totalBalance
        when {
            total == null -> {
                binding.tvBalanceLabel.text = "המאזן שלך"
                binding.tvBalanceAmount.text = "—"
            }
            isSettled(total) -> {
                binding.tvBalanceLabel.text = "הכול מאוזן"
                binding.tvBalanceAmount.text = formatMoney(java.math.BigDecimal.ZERO)
            }
            total.signum() > 0 -> {
                binding.tvBalanceLabel.text = "מגיע לך"
                binding.tvBalanceAmount.text = formatMoney(total)
            }
            else -> {
                binding.tvBalanceLabel.text = "את חייבת"
                binding.tvBalanceAmount.text = formatMoney(total)
            }
        }
        binding.tvGroupCount.text = "${c.groupCount} קבוצות פעילות"

        binding.tvPartialWarning.visibility = if (c.failedGroups > 0) View.VISIBLE else View.GONE
        binding.tvPartialWarning.text = "נתוני ${c.failedGroups} קבוצות לא נטענו, ייתכן שהמאזן חלקי"

        binding.tvOwedToMe.text = formatMoney(c.owedToMe)
        binding.tvOwedByMe.text = formatMoney(c.owedByMe)

        debtAdapter.submitList(c.debts)
        binding.tvNoDebts.visibility = if (c.debts.isEmpty()) View.VISIBLE else View.GONE

        shownGroups = c.groups
        submitGroups()
        binding.tvNoGroups.visibility = if (c.groups.isEmpty()) View.VISIBLE else View.GONE
    }
}
