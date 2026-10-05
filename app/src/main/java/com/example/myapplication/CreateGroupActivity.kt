package com.example.myapplication

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.myapplication.data.model.GroupMember
import com.example.myapplication.databinding.ActivityCreateGroupBinding
import com.example.myapplication.databinding.ItemMemberBinding
import com.example.myapplication.ui.group.CreateGroupUiState
import com.example.myapplication.ui.group.CreateGroupViewModel
import com.example.myapplication.ui.group.IconAdapter
import com.example.myapplication.ui.group.SearchResultAdapter
import kotlinx.coroutines.launch

class CreateGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateGroupBinding
    private val viewModel: CreateGroupViewModel by viewModels()
    private val iconAdapter = IconAdapter { viewModel.onIconSelected(it) }
    private val searchAdapter = SearchResultAdapter { viewModel.addMember(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.createRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.rvIcons.layoutManager = GridLayoutManager(this, 5)
        binding.rvIcons.adapter = iconAdapter

        binding.etGroupName.doAfterTextChanged { viewModel.onNameChanged(it?.toString().orEmpty()) }
        binding.etSearch.doAfterTextChanged { viewModel.onSearchQueryChanged(it?.toString().orEmpty()) }

        binding.rvSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvSearchResults.adapter = searchAdapter
        binding.btnCreate.setOnClickListener { viewModel.create() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                // Home observes groups live, so returning is enough for the new group to show up.
                launch { viewModel.groupCreated.collect { finish() } }
            }
        }
    }

    private fun render(s: CreateGroupUiState) {
        iconAdapter.selected = s.icon

        binding.tvNameError.visibility = if (s.nameError != null) View.VISIBLE else View.GONE
        binding.tvNameError.text = s.nameError

        binding.tvError.visibility = if (s.error != null) View.VISIBLE else View.GONE
        binding.tvError.text = s.error

        binding.progressSearch.visibility = if (s.searching) View.VISIBLE else View.GONE
        binding.btnSearch.visibility = if (s.searching) View.GONE else View.VISIBLE
        binding.tvSearchMessage.visibility = if (s.searchMessage != null) View.VISIBLE else View.GONE
        binding.tvSearchMessage.text = s.searchMessage

        binding.membersContainer.removeAllViews()
        s.me?.let { me ->
            addRow(binding.membersContainer, me) { badge, action ->
                badge.visibility = View.VISIBLE
                action.visibility = View.GONE
            }
        }
        s.members.forEach { member ->
            addRow(binding.membersContainer, member) { _, action ->
                action.visibility = View.VISIBLE
                action.text = "הסר"
                action.setOnClickListener { viewModel.removeMember(member.uid) }
            }
        }

        searchAdapter.submit(s.searchResults, s.searchQuery)
        val rows = s.searchResults.size.coerceAtMost(MAX_VISIBLE_RESULTS)
        binding.rvSearchResults.visibility = if (rows > 0) View.VISIBLE else View.GONE
        binding.rvSearchResults.layoutParams = binding.rvSearchResults.layoutParams.apply {
            height = if (s.searchResults.size > MAX_VISIBLE_RESULTS) {
                (MAX_VISIBLE_RESULTS * RESULT_ROW_DP * resources.displayMetrics.density).toInt()
            } else ViewGroup.LayoutParams.WRAP_CONTENT
        }

        binding.btnCreate.isEnabled = !s.saving && s.me != null
        binding.btnCreate.text = if (s.saving) "יוצר קבוצה..." else "צור קבוצה"

    }

    private companion object {
        const val MAX_VISIBLE_RESULTS = 3
        const val RESULT_ROW_DP = 64 // 56dp row + 8dp margin
    }

    private fun addRow(
        container: ViewGroup,
        member: GroupMember,
        configure: (badge: View, action: com.google.android.material.button.MaterialButton) -> Unit
    ) {
        val row = ItemMemberBinding.inflate(LayoutInflater.from(this), container, false)
        row.tvMemberName.text = member.name.ifBlank { member.email }
        row.tvMemberEmail.text = member.email
        configure(row.tvBadge, row.btnMemberAction)
        container.addView(row.root)
    }
}
