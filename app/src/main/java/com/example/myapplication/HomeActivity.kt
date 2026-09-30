package com.example.myapplication

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.databinding.ActivityHomeBinding
import com.example.myapplication.ui.home.GroupAdapter
import com.example.myapplication.ui.home.HomeContent
import com.example.myapplication.ui.home.HomeUiState
import com.example.myapplication.ui.home.HomeViewModel
import com.example.myapplication.ui.home.formatShekel
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val viewModel: HomeViewModel by viewModels()
    private val groupAdapter = GroupAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        applyInsets()

        binding.rvGroups.adapter = groupAdapter

        // UI callbacks only - these features do not exist in the app yet
        binding.btnBell.setOnClickListener { /* TODO: notifications */ }
        binding.btnNewExpense.setOnClickListener { /* TODO: new expense flow */ }
        binding.fabAdd.setOnClickListener { /* TODO: create group / expense */ }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
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
