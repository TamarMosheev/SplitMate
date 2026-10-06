package com.example.myapplication

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.databinding.ActivityDebtDetailsBinding
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.myapplication.ui.balance.Breakdown
import com.example.myapplication.ui.balance.BreakdownAdapter
import com.example.myapplication.ui.balance.DebtActionMode
import com.example.myapplication.ui.balance.DebtDetailsContent
import com.example.myapplication.ui.balance.DebtDetailsUiState
import com.example.myapplication.ui.balance.DebtDetailsViewModel
import com.example.myapplication.ui.balance.formatMoney
import kotlinx.coroutines.launch

class DebtDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebtDetailsBinding
    private val viewModel: DebtDetailsViewModel by viewModels()
    private val breakdownAdapter = BreakdownAdapter { expenseId ->
        val groupId = (viewModel.state.value as? DebtDetailsUiState.Success)?.content?.groupId
        if (groupId != null) startActivity(ExpenseDetailsActivity.intent(this, groupId, expenseId))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebtDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.debtRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.rvBreakdown.layoutManager = LinearLayoutManager(this)
        binding.rvBreakdown.adapter = breakdownAdapter
        binding.btnRetry.setOnClickListener { viewModel.load() }
        binding.btnMarkPaid.setOnClickListener { confirmPrimary() }
        binding.btnRemind.setOnClickListener { viewModel.sendReminder() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.busy.collect { updateActions() } }
                launch {
                    viewModel.messages.collect {
                        Toast.makeText(this@DebtDetailsActivity, it, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** Asks for confirmation before the money actions (they change real payment state); texts only. */
    private fun confirmPrimary() {
        val content = (viewModel.state.value as? DebtDetailsUiState.Success)?.content ?: return
        val amount = formatMoney(content.amount)
        val (title, message, action) = when (content.mode) {
            DebtActionMode.DEBTOR_CAN_CLAIM ->
                Triple("שלחתי תשלום", "לעדכן שהתשלום של $amount נשלח?", "כן, שלחתי")
            DebtActionMode.CREDITOR_CLAIM_PENDING ->
                Triple("אישור קבלת תשלום", "לאשר שקיבלת תשלום של $amount?", "אשר")
            DebtActionMode.CREDITOR_OPEN ->
                Triple("סימון כשולם", "לסמן את החוב על סך $amount כשולם?", "סמן כשולם")
            else -> return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ -> viewModel.onPrimaryAction() }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun render(state: DebtDetailsUiState) {
        binding.progressDebt.visibility = if (state is DebtDetailsUiState.Loading) View.VISIBLE else View.GONE
        binding.errorGroup.visibility = if (state is DebtDetailsUiState.Error) View.VISIBLE else View.GONE
        binding.contentScroll.visibility = if (state is DebtDetailsUiState.Success) View.VISIBLE else View.GONE

        when (state) {
            is DebtDetailsUiState.Error -> binding.tvDebtError.text = state.message
            is DebtDetailsUiState.Success -> renderContent(state.content)
            DebtDetailsUiState.Loading -> Unit
        }
        if (state !is DebtDetailsUiState.Success) binding.actionsGroup.visibility = View.GONE
    }

    private fun renderBreakdown(b: Breakdown) {
        val rows = when (b) {
            is Breakdown.Rows -> b.rows
            is Breakdown.Netted -> b.rows
            else -> emptyList()
        }
        breakdownAdapter.submitList(rows)
        binding.rvBreakdown.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        binding.tvBreakdownTitle.text = if (b is Breakdown.Netted) "הוצאות שתרמו למאזן" else "ממה זה מורכב"
        val info = when (b) {
            is Breakdown.Rows -> if (rows.isEmpty()) "אין הוצאות להצגה" else null
            is Breakdown.Netted -> b.explanation
            is Breakdown.Unavailable -> b.message
            is Breakdown.Error -> b.message
        }
        binding.tvBreakdownInfo.text = info
        binding.tvBreakdownInfo.visibility = if (info == null) View.GONE else View.VISIBLE
    }

    private fun renderContent(c: DebtDetailsContent) {
        val name = c.otherName ?: "משתמש"
        binding.tvAvatar.text = c.otherName?.trim()?.firstOrNull()?.toString() ?: "?"
        binding.tvDebtTitle.text = if (c.owedToMe) "$name חייב/ת לך" else "את חייבת ל$name"
        binding.tvDebtAmount.text = formatMoney(c.amount)
        binding.tvDebtAmount.setTextColor(
            ContextCompat.getColor(this, if (c.owedToMe) R.color.home_positive else R.color.home_negative)
        )
        binding.tvGroupName.text = c.groupName
        binding.tvGroupIcon.text = c.groupIcon
        binding.tvGroupIcon.visibility = if (c.groupIcon.isNullOrBlank()) View.GONE else View.VISIBLE

        binding.tvStatus.text = if (c.isPaid) "סטטוס: שולם" else "סטטוס: פתוח"
        val paidInfo = listOfNotNull(
            c.paidAtText?.let { "שולם ב-$it" },
            c.markedByName?.let { "סומן על ידי $it" }
        ).joinToString(" · ")
        binding.tvPaidInfo.text = paidInfo
        binding.tvPaidInfo.visibility = if (c.isPaid && paidInfo.isNotEmpty()) View.VISIBLE else View.GONE

        renderBreakdown(c.breakdown)

        binding.btnRemind.text = "שלח תזכורת ל$name"
        updateActions()
    }

    /** Renders [DebtDetailsContent.mode]; all decisions were made from the real settlement upstream. */
    private fun updateActions() {
        val c = (viewModel.state.value as? DebtDetailsUiState.Success)?.content
        if (c == null || c.mode == DebtActionMode.NONE) {
            binding.actionsGroup.visibility = View.GONE
            return
        }
        val name = c.otherName ?: "משתמש"
        val idle = !viewModel.busy.value
        binding.actionsGroup.visibility = View.VISIBLE

        val notice: String? = when (c.mode) {
            DebtActionMode.CREDITOR_CLAIM_PENDING -> "$name עדכן/ה שהתשלום נשלח"
            DebtActionMode.DEBTOR_CAN_CLAIM -> if (c.claimRejected) "התשלום שדיווחת עליו לא אושר" else null
            else -> null
        }
        binding.tvActionNotice.text = notice
        binding.tvActionNotice.visibility = if (notice == null) View.GONE else View.VISIBLE

        binding.btnMarkPaid.text = when (c.mode) {
            DebtActionMode.DEBTOR_CAN_CLAIM -> "שלחתי תשלום"
            DebtActionMode.DEBTOR_CLAIM_PENDING -> "מחכה לאישור התשלום"
            DebtActionMode.CREDITOR_CLAIM_PENDING -> "אשר קבלת תשלום"
            else -> "סמן כשולם"
        }
        binding.btnMarkPaid.isEnabled = c.mode != DebtActionMode.DEBTOR_CLAIM_PENDING && idle
        // Reminder: only the creditor, and only while no claim is waiting for confirmation.
        binding.btnRemind.visibility = if (c.mode == DebtActionMode.CREDITOR_OPEN) View.VISIBLE else View.GONE
        binding.btnRemind.isEnabled = idle
    }
}
