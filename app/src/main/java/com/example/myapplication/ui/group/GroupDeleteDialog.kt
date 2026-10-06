package com.example.myapplication.ui.group

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.myapplication.R

/** The one confirmation dialog for deleting a group, used by every screen. Nothing is sent before "מחק קבוצה". */
fun confirmDeleteGroup(context: Context, onConfirm: () -> Unit) {
    val dialog = AlertDialog.Builder(context)
        .setTitle("מחיקת קבוצה")
        .setMessage("מחיקת הקבוצה תמחק את הנתונים הקשורים אליה ולא ניתן יהיה לבטל את הפעולה.")
        .setNegativeButton("ביטול", null)
        .setPositiveButton("מחק קבוצה") { _, _ -> onConfirm() }
        .create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(ContextCompat.getColor(context, R.color.home_negative))
    }
    dialog.show()
}
