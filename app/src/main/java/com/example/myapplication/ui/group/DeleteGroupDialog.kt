package com.example.myapplication.ui.group

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.myapplication.R

/**
 * The confirmation shown before a group is deleted. Nothing is deleted until the destructive button is
 * pressed; cancelling (or tapping outside) changes nothing. Shared by the group list and the group screen.
 */
fun showDeleteGroupConfirmation(context: Context, onConfirm: () -> Unit) {
    val dialog = AlertDialog.Builder(context)
        .setTitle("כדי למחוק את הקבוצה?")
        .setMessage("מחיקת הקבוצה תמחק את הנתונים הקשורים אליה ולא ניתן יהיה לבטל את הפעולה.")
        .setNegativeButton("ביטול", null)
        .setPositiveButton("מחק קבוצה") { _, _ -> onConfirm() }
        .create()
    dialog.show()
    // Destructive action: red button.
    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        .setTextColor(ContextCompat.getColor(context, R.color.home_negative))
}
