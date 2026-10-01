package com.example.myapplication.ui.expense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.model.GroupMember
import com.example.myapplication.repository.ExpenseRepository
import com.example.myapplication.utils.Resource
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SplitMode(val firestoreValue: String) { EQUAL("equal"), EXACT("exact") }

private val ZERO = BigDecimal.ZERO.setScale(2)

/** Parses user input to a 2-decimal amount; null when it is not a number. */
fun parseAmount(text: String): BigDecimal? =
    text.trim().replace(',', '.').toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)

data class AddExpenseUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val groupName: String = "",
    val groupIcon: String = "",
    val members: List<GroupMember> = emptyList(),
    val amountText: String = "",
    val description: String = "",
    val payerId: String? = null,
    val splitMode: SplitMode = SplitMode.EQUAL,
    val participantIds: Set<String> = emptySet(),
    val exactTexts: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    val error: String? = null
) {
    val total: BigDecimal? get() = parseAmount(amountText)

    /** Equal split by UID; any leftover cents go to the first participants so the shares sum to the total. */
    val equalShares: Map<String, BigDecimal>
        get() {
            val selected = members.filter { it.uid in participantIds }
            val total = total
            if (selected.isEmpty() || total == null || total.signum() <= 0) {
                return selected.associate { it.uid to ZERO }
            }
            val n = BigDecimal(selected.size)
            val base = total.divide(n, 2, RoundingMode.DOWN)
            var leftoverCents = total.subtract(base.multiply(n)).movePointRight(2).toInt()
            return selected.associate { m ->
                val extra = if (leftoverCents > 0) { leftoverCents--; BigDecimal("0.01") } else BigDecimal.ZERO
                m.uid to base.add(extra).setScale(2)
            }
        }
}

class AddExpenseViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val repository = ExpenseRepository()
    private val groupId: String = savedStateHandle.get<String>(EXTRA_GROUP_ID).orEmpty()
    private val expenseId: String? = if (groupId.isEmpty()) null else repository.newExpenseId(groupId)

    private val _state = MutableStateFlow(AddExpenseUiState())
    val state: StateFlow<AddExpenseUiState> = _state.asStateFlow()

    /** Emitted once, only after Firestore confirmed the write. */
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved: Flow<Unit> = _saved.receiveAsFlow()

    init {
        viewModelScope.launch {
            if (groupId.isEmpty()) {
                _state.update { it.copy(loading = false, loadError = "לא נבחרה קבוצה") }
                return@launch
            }
            when (val result = repository.loadGroup(groupId)) {
                is Resource.Success -> {
                    val g = result.data
                    val me = repository.currentUid
                    _state.update {
                        it.copy(
                            loading = false,
                            groupName = g.name,
                            groupIcon = g.icon,
                            members = g.members,
                            payerId = g.members.firstOrNull { m -> m.uid == me }?.uid ?: g.members.firstOrNull()?.uid,
                            participantIds = g.members.map { m -> m.uid }.toSet()
                        )
                    }
                }
                is Resource.Error -> _state.update { it.copy(loading = false, loadError = result.message) }
                Resource.Loading -> Unit
            }
        }
    }

    fun onAmountChanged(text: String) = _state.update { it.copy(amountText = text, error = null) }

    /** The quick "10+" style buttons add to the current amount. */
    fun addToAmount(delta: Int) = _state.update {
        val sum = (parseAmount(it.amountText) ?: ZERO).add(BigDecimal(delta))
        it.copy(amountText = sum.stripTrailingZeros().toPlainString(), error = null)
    }

    fun onDescriptionChanged(text: String) = _state.update { it.copy(description = text) }

    fun onPayerSelected(uid: String) = _state.update { it.copy(payerId = uid, error = null) }

    fun onSplitModeChanged(mode: SplitMode) = _state.update { it.copy(splitMode = mode, error = null) }

    fun onParticipantToggled(uid: String) = _state.update {
        it.copy(
            participantIds = if (uid in it.participantIds) it.participantIds - uid else it.participantIds + uid,
            error = null
        )
    }

    fun onExactAmountChanged(uid: String, text: String) = _state.update {
        it.copy(exactTexts = it.exactTexts + (uid to text), error = null)
    }

    fun save() {
        val s = _state.value
        if (s.saving || s.loading || s.loadError != null || expenseId == null) return

        val total = s.total
        val payer = s.payerId
        val participants = s.members.map { it.uid }.filter { it in s.participantIds }
        val error = when {
            total == null || total.signum() <= 0 -> "יש להזין סכום תקין"
            payer == null || s.members.none { it.uid == payer } -> "יש לבחור מי שילם"
            participants.isEmpty() -> "יש לבחור לפחות משתתף אחד"
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        total!!; payer!!

        val exact: Map<String, Double>? = if (s.splitMode == SplitMode.EXACT) {
            val parsed = participants.associateWith { parseAmount(s.exactTexts[it].orEmpty()) }
            val sum = parsed.values.fold(ZERO) { acc, v -> acc.add(v ?: ZERO) }
            if (parsed.values.any { it == null || it.signum() < 0 } || sum.compareTo(total) != 0) {
                _state.update { it.copy(error = "הסכומים אינם שווים לסכום ההוצאה") }
                return
            }
            parsed.mapValues { it.value!!.toDouble() }
        } else null

        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = repository.saveExpense(
                groupId = groupId,
                expenseId = expenseId,
                description = s.description.trim(),
                amount = total.toDouble(),
                paidBy = payer,
                participantIds = participants,
                splitType = s.splitMode.firestoreValue,
                exactAmounts = exact
            )
            when (result) {
                is Resource.Success -> {
                    _state.update { it.copy(saving = false) }
                    _saved.send(Unit)
                }
                is Resource.Error -> _state.update { it.copy(saving = false, error = result.message) }
                Resource.Loading -> Unit
            }
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "groupId"
    }
}
