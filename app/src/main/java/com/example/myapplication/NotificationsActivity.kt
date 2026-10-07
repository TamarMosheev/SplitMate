package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.myapplication.ui.notifications.SwipeToDeleteCallback
import com.example.myapplication.databinding.ActivityNotificationsBinding
import com.example.myapplication.ui.balance.BalanceNavigation
import com.example.myapplication.ui.notifications.NotificationAdapter
import com.example.myapplication.ui.notifications.NotificationsUiState
import com.example.myapplication.ui.notifications.NotificationsViewModel
import kotlinx.coroutines.launch

class NotificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationsBinding
    private val viewModel: NotificationsViewModel by viewModels()
    private val adapter = NotificationAdapter(
        onClick = { viewModel.onNotificationClick(it) },
        onCheckClick = { viewModel.markRead(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.notificationsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { viewModel.load() }
        binding.rvNotifications.layoutManager = LinearLayoutManager(this)
        binding.rvNotifications.adapter = adapter
        ItemTouchHelper(SwipeToDeleteCallback(this) { position ->
            adapter.currentList.getOrNull(position)?.let { viewModel.delete(it) }
        }).attachToRecyclerView(binding.rvNotifications)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.messages.collect {
                        Toast.makeText(this@NotificationsActivity, it, Toast.LENGTH_LONG).show()
                    }
                }
                launch {
                    // A delete failed: bring the swiped row back.
                    viewModel.restoreRow.collect { id ->
                        val index = adapter.currentList.indexOfFirst { it.id == id }
                        if (index >= 0) adapter.notifyItemChanged(index)
                    }
                }
                launch {
                    viewModel.openDebt.collect { debt ->
                        BalanceNavigation.openDebtDetails(this@NotificationsActivity, debt.groupId, debt.settlementId)
                    }
                }
            }
        }
    }

    private fun render(state: NotificationsUiState) {
        binding.progressNotifications.visibility = if (state is NotificationsUiState.Loading) View.VISIBLE else View.GONE
        binding.errorGroup.visibility = if (state is NotificationsUiState.Error) View.VISIBLE else View.GONE
        val items = (state as? NotificationsUiState.Success)?.items
        binding.rvNotifications.visibility = if (items != null) View.VISIBLE else View.GONE
        binding.tvEmpty.visibility = if (items != null && items.isEmpty()) View.VISIBLE else View.GONE

        when (state) {
            is NotificationsUiState.Error -> binding.tvNotificationsError.text = state.message
            is NotificationsUiState.Success -> adapter.submitList(state.items)
            NotificationsUiState.Loading -> Unit
        }
    }
}
