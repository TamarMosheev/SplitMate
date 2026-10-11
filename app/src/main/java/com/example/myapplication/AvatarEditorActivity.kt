package com.example.myapplication

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import com.example.myapplication.data.model.Avatar
import com.example.myapplication.databinding.ActivityAvatarEditorBinding
import com.example.myapplication.ui.avatar.AvatarEditorState
import com.example.myapplication.ui.avatar.AvatarEditorViewModel
import com.example.myapplication.ui.avatar.AvatarView
import kotlinx.coroutines.launch

/** "האווטאר שלי": loads the real avatar (GET /users/me), previews edits live and saves with PATCH /users/me/avatar. */
class AvatarEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAvatarEditorBinding
    private val viewModel: AvatarEditorViewModel by viewModels()

    /** Restyles one option circle for the current avatar (swatch content + selected ring). */
    private val optionBinders = mutableListOf<(Avatar) -> Unit>()
    private var glassesListenerActive = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAvatarEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.avatarRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { viewModel.load() }
        binding.btnSave.setOnClickListener { viewModel.save() }

        buildRow(binding.rowHairStyle, Avatar.HAIR_STYLES, { it.hairStyle }, { a, id -> a.copy(hairStyle = id) }) { id, a ->
            avatarSwatch(a.copy(hairStyle = id))
        }
        buildRow(binding.rowFace, Avatar.FACE_STYLES, { it.faceStyle }, { a, id -> a.copy(faceStyle = id) }) { id, a ->
            avatarSwatch(a.copy(faceStyle = id))
        }
        buildRow(binding.rowSkin, Avatar.SKIN_TONES, { it.skinTone }, { a, id -> a.copy(skinTone = id) }) { id, _ ->
            colorSwatch(AvatarView.skinColor(id))
        }
        buildRow(binding.rowHairColor, Avatar.HAIR_COLORS, { it.hairColor }, { a, id -> a.copy(hairColor = id) }) { id, _ ->
            colorSwatch(AvatarView.hairColor(id))
        }
        buildRow(binding.rowBackground, Avatar.BACKGROUNDS, { it.backgroundColor }, { a, id -> a.copy(backgroundColor = id) }) { id, _ ->
            colorSwatch(AvatarView.backgroundColor(id), outlined = true)
        }
        binding.switchGlasses.setOnCheckedChangeListener { _, checked ->
            if (glassesListenerActive) viewModel.update { it.copy(glassesEnabled = checked) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.messages.collect { Toast.makeText(this@AvatarEditorActivity, it, Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun render(s: AvatarEditorState) {
        binding.progressLoad.visibility = if (s.loading) View.VISIBLE else View.GONE
        binding.errorGroup.visibility = if (s.loadError != null) View.VISIBLE else View.GONE
        binding.tvError.text = s.loadError
        binding.contentScroll.visibility = if (s.ready) View.VISIBLE else View.GONE
        binding.btnSave.isEnabled = s.ready && !s.saving
        binding.btnSave.text = if (s.saving) "שומר..." else "שמור אווטאר"

        binding.tvUserName.text = s.name.orEmpty()
        binding.avatarPreview.setAvatar(s.avatar)

        glassesListenerActive = false
        binding.switchGlasses.isChecked = s.avatar.glassesEnabled
        glassesListenerActive = true
        binding.switchGlasses.isEnabled = !s.saving

        optionBinders.forEach { it(s.avatar) }
    }

    /** Fills [row] with one circular option per allowed id; tapping one updates only the local preview. */
    private fun buildRow(
        row: LinearLayout,
        ids: List<String>,
        selected: (Avatar) -> String,
        apply: (Avatar, String) -> Avatar,
        swatch: (String, Avatar) -> View
    ) {
        val d = resources.displayMetrics.density
        val size = (56 * d).toInt()
        ids.forEach { id ->
            val container = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (10 * d).toInt() }
                val pad = (4 * d).toInt()
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                contentDescription = id
                setOnClickListener { viewModel.update { apply(it, id) } }
            }
            row.addView(container)
            optionBinders += { avatar ->
                container.removeAllViews()
                container.addView(
                    swatch(id, avatar),
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                container.background = ring(selected(avatar) == id)
            }
        }
    }

    private fun avatarSwatch(avatar: Avatar) = AvatarView(this).apply { setAvatar(avatar) }

    private fun colorSwatch(color: Int, outlined: Boolean = false): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (outlined) {
                setStroke(
                    resources.displayMetrics.density.toInt(),
                    ContextCompat.getColor(this@AvatarEditorActivity, R.color.login_stroke)
                )
            }
        }
    }

    /** Blue ring for the selected option, a faint one otherwise. */
    private fun ring(selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        val width = (if (selected) 3 else 1) * resources.displayMetrics.density
        setStroke(
            width.toInt(),
            ContextCompat.getColor(this@AvatarEditorActivity, if (selected) R.color.home_primary else R.color.login_stroke)
        )
    }
}
