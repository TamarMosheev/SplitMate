package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.myapplication.databinding.ActivityHomeBinding
import com.example.myapplication.ui.expense.AddExpenseViewModel
import com.example.myapplication.ui.group.GroupDetailsViewModel
import com.example.myapplication.ui.group.showDeleteGroupConfirmation
import com.example.myapplication.ui.home.GroupAdapter
import com.example.myapplication.ui.home.HomeContent
import com.example.myapplication.ui.home.HomeUiState
import com.example.myapplication.ui.home.HomeViewModel
import com.example.myapplication.ui.home.formatShekel
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()
    private val groupAdapter = GroupAdapter(
        onGroupClick = { startGroupDetails(it.id) },
        // The trash icon only opens the confirmation; the delete itself happens when it is confirmed.
        onDeleteClick = { group -> showDeleteGroupConfirmation(this) { viewModel.deleteGroup(group.id) } }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        applyInsets()

        binding.rvGroups.layoutManager = LinearLayoutManager(this)
        binding.rvGroups.adapter = groupAdapter

        // UI callbacks only - these features do not exist in the app yet
        binding.btnBell.setOnClickListener { /* TODO: notifications */ }
        binding.btnNewExpense.setOnClickListener { openNewExpense() }
        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, CreateGroupActivity::class.java))
        }

        // Long-press the greeting to rename; the UI updates via the live Firestore listener.
        binding.tvGreeting.setOnLongClickListener {
            showEditNameDialog()
            true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.deletingGroupId.collect { groupAdapter.deletingGroupId = it } }
                launch {
                    viewModel.messages.collect {
                        Toast.makeText(this@HomeActivity, it, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** Expenses belong to a real group: pick one of the user's groups (if more than one), then open the screen. */
    private fun openNewExpense() {
        val groups = (viewModel.state.value as? HomeUiState.Success)?.content?.groups.orEmpty()
        when (groups.size) {
            0 -> Toast.makeText(this, "יש ליצור קבוצה לפני הוספת הוצאה", Toast.LENGTH_LONG).show()
            1 -> startAddExpense(groups.first().id)
            else -> AlertDialog.Builder(this)
                .setTitle("באיזו קבוצה?")
                .setItems(groups.map { listOfNotNull(it.icon, it.name).joinToString(" ") }.toTypedArray()) { _, i ->
                    startAddExpense(groups[i].id)
                }
                .setNegativeButton("ביטול", null)
                .show()
        }
    }

    private fun startGroupDetails(groupId: String) {
        startActivity(
            Intent(this, GroupDetailsActivity::class.java)
                .putExtra(GroupDetailsViewModel.EXTRA_GROUP_ID, groupId)
        )
    }

    private fun startAddExpense(groupId: String) {
        startActivity(
            Intent(this, AddExpenseActivity::class.java)
                .putExtra(AddExpenseViewModel.EXTRA_GROUP_ID, groupId)
        )
    }

    private fun showEditNameDialog() {
        val input = EditText(this).apply { setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("עריכת שם")
            .setView(input)
            .setPositiveButton("שמירה") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) viewModel.updateName(name)
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun applyInsets() {
        val scrollBottom = binding.homeScroll.paddingBottom
        val actionsBottom = binding.bottomActions.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.homeRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.homeScroll.updatePadding(top = bars.top, bottom = scrollBottom + bars.bottom)
            binding.bottomActions.updatePadding(bottom = actionsBottom + bars.bottom)
            insets
        }
    }

    private fun render(state: HomeUiState) {
        binding.progressHome.visibility = if (state is HomeUiState.Loading) View.VISIBLE else View.GONE
        binding.contentGroup.visibility = if (state is HomeUiState.Success) View.VISIBLE else View.GONE
        binding.tvError.visibility = if (state is HomeUiState.Error) View.VISIBLE else View.GONE

        when (state) {
            is HomeUiState.Loading -> binding.tvGreeting.text = greeting(null)
            is HomeUiState.Error -> {
                binding.tvGreeting.text = greeting(null)
                binding.tvError.text = state.message
            }
            is HomeUiState.Success -> renderContent(state.content)
        }
    }

    private fun renderContent(content: HomeContent) {
        binding.tvGreeting.text = greeting(content.userName)

        val balance = content.generalBalance
        binding.tvBalanceAmount.visibility = if (balance != null) View.VISIBLE else View.GONE
        binding.tvBalanceUnavailable.visibility = if (balance == null) View.VISIBLE else View.GONE
        if (balance != null) binding.tvBalanceAmount.text = formatShekel(balance, withSign = false)

        val count = content.groups.size
        binding.chipGroupCount.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) binding.chipGroupCount.text = "$count קבוצות פעילות"

        binding.tvEmptyTitle.visibility = if (count == 0) View.VISIBLE else View.GONE
        groupAdapter.submitList(content.groups)
    }

    private fun greeting(name: String?) =
        if (name.isNullOrBlank()) "שלום 👋" else "שלום, $name 👋"
}
