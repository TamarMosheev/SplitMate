package com.example.myapplication

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.myapplication.databinding.ActivityAddMemberBinding
import com.example.myapplication.ui.group.AddMemberAdapter
import com.example.myapplication.ui.group.AddMemberUiState
import com.example.myapplication.ui.group.AddMemberViewModel
import kotlinx.coroutines.launch

/** Search registered SplitMate users and add one to an existing group. */
class AddMemberActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddMemberBinding
    private val viewModel: AddMemberViewModel by viewModels()
    private val adapter = AddMemberAdapter { viewModel.add(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddMemberBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.addMemberRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter
        binding.etSearch.doAfterTextChanged { viewModel.onQueryChanged(it?.toString().orEmpty()) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.messages.collect {
                        Toast.makeText(this@AddMemberActivity, it, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun render(s: AddMemberUiState) {
        adapter.submit(s.results, s.memberIds, s.addingUid)
        binding.progressSearch.visibility = if (s.searching || s.busy) View.VISIBLE else View.GONE
        binding.tvError.text = s.error
        binding.tvError.visibility = if (s.error == null) View.GONE else View.VISIBLE
        binding.tvEmpty.visibility =
            if (s.searched && !s.searching && s.results.isEmpty() && s.error == null) View.VISIBLE else View.GONE
    }
}
