package com.example.myapplication.data.model

import com.example.myapplication.data.api.AvatarDto

/**
 * A user's avatar as stable backend ids (docs/ANDROID_API_CONTRACT.md, "Avatars"). The same [Avatar]
 * is drawn by AvatarView everywhere, so a person looks identical on every screen and for every viewer.
 */
data class Avatar(
    val hairStyle: String = DEFAULT_HAIR_STYLE,
    val faceStyle: String = DEFAULT_FACE_STYLE,
    val glassesEnabled: Boolean = false,
    val skinTone: String = DEFAULT_SKIN_TONE,
    val hairColor: String = DEFAULT_HAIR_COLOR,
    val backgroundColor: String = DEFAULT_BACKGROUND
) {
    fun toDto() = AvatarDto(hairStyle, faceStyle, glassesEnabled, skinTone, hairColor, backgroundColor)

    companion object {
        const val DEFAULT_HAIR_STYLE = "hair_1"
        const val DEFAULT_FACE_STYLE = "face_1"
        const val DEFAULT_SKIN_TONE = "skin_3"
        const val DEFAULT_HAIR_COLOR = "brown_2"
        const val DEFAULT_BACKGROUND = "light_blue"

        /** Exactly the allowed values from the contract, in display order. */
        val HAIR_STYLES = (1..8).map { "hair_$it" }
        val FACE_STYLES = (1..6).map { "face_$it" }
        val SKIN_TONES = (1..6).map { "skin_$it" }
        val HAIR_COLORS = listOf("black", "dark_brown", "brown_1", "brown_2", "brown_3", "blonde", "red", "gray")
        val BACKGROUNDS = listOf(
            "light_blue", "light_green", "light_pink", "light_yellow", "light_purple", "light_orange", "light_gray"
        )

        /** The server's default avatar (users who never customized theirs). */
        val DEFAULT = Avatar()
    }
}

/** A missing or unknown field falls back to the server's default for that field: never a crash or a blank. */
fun AvatarDto?.toAvatar(): Avatar = Avatar(
    hairStyle = this?.hairStyle?.takeIf { it in Avatar.HAIR_STYLES } ?: Avatar.DEFAULT_HAIR_STYLE,
    faceStyle = this?.faceStyle?.takeIf { it in Avatar.FACE_STYLES } ?: Avatar.DEFAULT_FACE_STYLE,
    glassesEnabled = this?.glassesEnabled ?: false,
    skinTone = this?.skinTone?.takeIf { it in Avatar.SKIN_TONES } ?: Avatar.DEFAULT_SKIN_TONE,
    hairColor = this?.hairColor?.takeIf { it in Avatar.HAIR_COLORS } ?: Avatar.DEFAULT_HAIR_COLOR,
    backgroundColor = this?.backgroundColor?.takeIf { it in Avatar.BACKGROUNDS } ?: Avatar.DEFAULT_BACKGROUND
)

/** The `avatar` map the backend stores in users/{uid} (read via Firestore by older screens); missing -> default. */
fun avatarFromProfile(raw: Any?): Avatar {
    val map = raw as? Map<*, *>
    return AvatarDto(
        hairStyle = map?.get("hairStyle") as? String,
        faceStyle = map?.get("faceStyle") as? String,
        glassesEnabled = map?.get("glassesEnabled") as? Boolean,
        skinTone = map?.get("skinTone") as? String,
        hairColor = map?.get("hairColor") as? String,
        backgroundColor = map?.get("backgroundColor") as? String
    ).toAvatar()
}
