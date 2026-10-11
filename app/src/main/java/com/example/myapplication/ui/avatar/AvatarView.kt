package com.example.myapplication.ui.avatar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.example.myapplication.data.api.AvatarDto
import com.example.myapplication.data.model.Avatar
import com.example.myapplication.data.model.toAvatar

/**
 * The one SplitMate avatar renderer: a circular, layered drawing (background, shoulders, neck, back hair,
 * face, expression, front hair, glasses) of a backend [Avatar]. It is purely a function of the avatar ids,
 * so the same avatar looks identical in the editor, member lists, search results and debt rows.
 * Null / unknown values render the server's default avatar (never blank).
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var avatar: Avatar = Avatar.DEFAULT

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val clip = Path()
    private val path = Path()
    private val rect = RectF()

    fun setAvatar(value: Avatar?) {
        val next = value ?: Avatar.DEFAULT
        if (next == avatar) return
        avatar = next
        invalidate()
    }

    /** Convenience for API objects: a null dto renders the default avatar. */
    fun setAvatar(dto: AvatarDto?) = setAvatar(dto.toAvatar())

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        canvas.save()
        canvas.translate((width - size) / 2f, (height - size) / 2f)
        canvas.scale(size / 100f, size / 100f)

        clip.reset()
        clip.addCircle(50f, 50f, 50f, Path.Direction.CW)
        canvas.clipPath(clip)

        val skin = SKIN[avatar.skinTone] ?: SKIN.getValue(Avatar.DEFAULT_SKIN_TONE)
        val hair = HAIR[avatar.hairColor] ?: HAIR.getValue(Avatar.DEFAULT_HAIR_COLOR)
        val background = BACKGROUND[avatar.backgroundColor] ?: BACKGROUND.getValue(Avatar.DEFAULT_BACKGROUND)
        val shade = ColorUtils.blendARGB(skin, Color.BLACK, 0.12f)

        canvas.drawColor(background)

        // Shoulders and neck.
        fill.color = SHIRT
        rect.set(16f, 78f, 84f, 126f)
        canvas.drawOval(rect, fill)
        fill.color = shade
        rect.set(43f, 62f, 57f, 82f)
        canvas.drawRoundRect(rect, 4f, 4f, fill)

        fill.color = hair
        drawBackHair(canvas, avatar.hairStyle)

        // Ears and face.
        fill.color = skin
        canvas.drawCircle(30.5f, 50f, 3.6f, fill)
        canvas.drawCircle(69.5f, 50f, 3.6f, fill)
        rect.set(30f, 25f, 70f, 71f)
        canvas.drawOval(rect, fill)

        drawFace(canvas, avatar.faceStyle)

        fill.color = hair
        stroke.color = hair
        drawFrontHair(canvas, avatar.hairStyle)

        if (avatar.glassesEnabled) drawGlasses(canvas)
        canvas.restore()
    }

    /** Hair drawn behind the face (volume, long hair, bun). */
    private fun drawBackHair(canvas: Canvas, style: String) {
        when (style) {
            "hair_3" -> { // long
                rect.set(26f, 22f, 74f, 84f)
                canvas.drawRoundRect(rect, 22f, 22f, fill)
            }
            "hair_4" -> { // curly
                for ((x, y) in CURLS) canvas.drawCircle(x, y, 8f, fill)
            }
            "hair_5" -> { // bun
                rect.set(27f, 20f, 73f, 62f)
                canvas.drawOval(rect, fill)
                canvas.drawCircle(50f, 17f, 9f, fill)
            }
            "hair_6" -> { // bob
                rect.set(25f, 22f, 75f, 66f)
                canvas.drawRoundRect(rect, 18f, 18f, fill)
            }
            "hair_7" -> canvas.drawCircle(50f, 40f, 31f, fill) // afro
            else -> { // short styles and spiky: a cap behind the face
                rect.set(27f, 20f, 73f, 60f)
                canvas.drawOval(rect, fill)
            }
        }
    }

    /** Hair drawn over the forehead. */
    private fun drawFrontHair(canvas: Canvas, style: String) {
        path.reset()
        when (style) {
            "hair_2" -> { // side part
                path.moveTo(29f, 46f)
                path.quadTo(26f, 21f, 52f, 21f)
                path.quadTo(76f, 22f, 71f, 46f)
                path.quadTo(63f, 32f, 44f, 36f)
                path.quadTo(35f, 38f, 29f, 46f)
            }
            "hair_4" -> { // curly fringe
                canvas.drawCircle(39f, 30f, 6.5f, fill)
                canvas.drawCircle(50f, 27f, 6.5f, fill)
                canvas.drawCircle(61f, 30f, 6.5f, fill)
                return
            }
            "hair_7" -> { // thin fringe under the afro
                path.moveTo(31f, 42f)
                path.quadTo(32f, 26f, 50f, 26f)
                path.quadTo(68f, 26f, 69f, 42f)
                path.quadTo(60f, 33f, 50f, 33f)
                path.quadTo(40f, 33f, 31f, 42f)
            }
            "hair_8" -> { // spiky
                path.moveTo(30f, 42f)
                path.lineTo(32f, 21f)
                path.lineTo(40f, 29f)
                path.lineTo(44f, 14f)
                path.lineTo(50f, 28f)
                path.lineTo(56f, 14f)
                path.lineTo(60f, 29f)
                path.lineTo(68f, 21f)
                path.lineTo(70f, 42f)
                path.quadTo(60f, 33f, 50f, 33f)
                path.quadTo(40f, 33f, 30f, 42f)
            }
            else -> { // short / long / bun / bob
                path.moveTo(29f, 46f)
                path.quadTo(27f, 22f, 50f, 22f)
                path.quadTo(73f, 22f, 71f, 46f)
                path.quadTo(63f, 33f, 50f, 33f)
                path.quadTo(37f, 33f, 29f, 46f)
            }
        }
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun drawFace(canvas: Canvas, style: String) {
        fill.color = FEATURE
        stroke.color = FEATURE
        stroke.strokeWidth = 2.2f
        val wink = style == "face_4"
        val eyeR = if (style == "face_5") 2.7f else 2f
        // Eyes.
        canvas.drawCircle(42f, 48f, eyeR, fill)
        if (wink) {
            path.reset()
            path.moveTo(54f, 48.5f)
            path.quadTo(58f, 45f, 62f, 48.5f)
            canvas.drawPath(path, stroke)
        } else {
            canvas.drawCircle(58f, 48f, eyeR, fill)
        }
        // Mouth.
        path.reset()
        when (style) {
            "face_2" -> { // big open grin
                path.moveTo(41f, 56f)
                path.quadTo(50f, 68f, 59f, 56f)
                path.close()
                fill.color = Color.WHITE
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
            "face_3" -> { // neutral
                path.moveTo(44f, 60f)
                path.lineTo(56f, 60f)
                canvas.drawPath(path, stroke)
            }
            "face_5" -> { // surprised
                canvas.drawCircle(50f, 60f, 3.4f, fill)
            }
            "face_6" -> { // smirk
                path.moveTo(43f, 59f)
                path.quadTo(52f, 62f, 58f, 55f)
                canvas.drawPath(path, stroke)
            }
            else -> { // face_1 / face_4 smile
                path.moveTo(43f, 56f)
                path.quadTo(50f, 64f, 57f, 56f)
                canvas.drawPath(path, stroke)
            }
        }
    }

    private fun drawGlasses(canvas: Canvas) {
        stroke.color = GLASSES
        stroke.strokeWidth = 2.2f
        canvas.drawCircle(41.5f, 48f, 7.5f, stroke)
        canvas.drawCircle(58.5f, 48f, 7.5f, stroke)
        canvas.drawLine(49f, 47.5f, 51f, 47.5f, stroke)
        canvas.drawLine(34f, 47f, 30.5f, 46f, stroke)
        canvas.drawLine(66f, 47f, 69.5f, 46f, stroke)
    }

    companion object {
        private val SHIRT = Color.parseColor("#1E6FD9")
        private val FEATURE = Color.parseColor("#2B1B12")
        private val GLASSES = Color.parseColor("#0D2A4F")

        private val CURLS = listOf(
            30f to 42f, 28f to 54f, 33f to 30f, 43f to 22f, 57f to 22f, 67f to 30f, 72f to 42f, 72f to 54f
        )

        private val SKIN = mapOf(
            "skin_1" to Color.parseColor("#FFE0C7"),
            "skin_2" to Color.parseColor("#F5C9A6"),
            "skin_3" to Color.parseColor("#E0A97F"),
            "skin_4" to Color.parseColor("#C68642"),
            "skin_5" to Color.parseColor("#8D5524"),
            "skin_6" to Color.parseColor("#5C3A21")
        )
        private val HAIR = mapOf(
            "black" to Color.parseColor("#1C1C1C"),
            "dark_brown" to Color.parseColor("#3B2417"),
            "brown_1" to Color.parseColor("#5A3825"),
            "brown_2" to Color.parseColor("#7A4B2A"),
            "brown_3" to Color.parseColor("#A0693D"),
            "blonde" to Color.parseColor("#E6C36A"),
            "red" to Color.parseColor("#B5432A"),
            "gray" to Color.parseColor("#A8A8A8")
        )
        private val BACKGROUND = mapOf(
            "light_blue" to Color.parseColor("#D6E8FF"),
            "light_green" to Color.parseColor("#D7F2DF"),
            "light_pink" to Color.parseColor("#FBDDE8"),
            "light_yellow" to Color.parseColor("#FFF1C4"),
            "light_purple" to Color.parseColor("#E5DBFA"),
            "light_orange" to Color.parseColor("#FFE0C2"),
            "light_gray" to Color.parseColor("#E6E9EE")
        )

        /** The swatch colors used by the editor's option circles. */
        fun skinColor(id: String) = SKIN[id] ?: SKIN.getValue(Avatar.DEFAULT_SKIN_TONE)
        fun hairColor(id: String) = HAIR[id] ?: HAIR.getValue(Avatar.DEFAULT_HAIR_COLOR)
        fun backgroundColor(id: String) = BACKGROUND[id] ?: BACKGROUND.getValue(Avatar.DEFAULT_BACKGROUND)
    }
}
