package com.example.myapplication.data.api

import java.io.IOException
import org.json.JSONObject
import retrofit2.HttpException

/** Which call failed; the same status code means different things for different actions. */
enum class ApiAction { GENERAL, MARK_PAID, REMINDER, CLAIM_PAYMENT, CONFIRM_PAYMENT }

/** Turns API failures into user-facing (Hebrew) messages. */
object ApiErrorMapper {

    /** The `detail` text of an error body (`{"detail": "..."}`), if any. */
    fun detail(e: Throwable): String? {
        val body = (e as? HttpException)?.response()?.errorBody() ?: return null
        return runCatching { JSONObject(body.string()).optString("detail") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 404/409 on an action mean the screen data was out of date: reload it. */
    fun isStale(e: Throwable): Boolean = e is HttpException && (e.code() == 404 || e.code() == 409)

    private val isPaymentFlow = setOf(ApiAction.CLAIM_PAYMENT, ApiAction.CONFIRM_PAYMENT)

    fun message(e: Throwable, action: ApiAction = ApiAction.GENERAL): String = when {
        e is HttpException -> {
            val detail = detail(e).orEmpty()
            when (e.code()) {
                400 -> if (action == ApiAction.REMINDER) "לא ניתן לשלוח תזכורת: החייב כבר לא חבר בקבוצה"
                else "הבקשה לא תקינה"
                401 -> "ההתחברות פגה. התחברו מחדש"
                403 -> when (action) {
                    ApiAction.MARK_PAID -> "רק מי שמגיע לו הכסף יכול לסמן חוב כשולם"
                    ApiAction.REMINDER -> "רק מי שמגיע לו הכסף יכול לשלוח תזכורת"
                    ApiAction.CLAIM_PAYMENT, ApiAction.CONFIRM_PAYMENT -> "אין לך הרשאה לבצע פעולה זו"
                    ApiAction.GENERAL -> "אין לכם הרשאה לבצע פעולה זו"
                }
                404 -> if (action == ApiAction.GENERAL) "הפריט המבוקש לא נמצא"
                else "החוב השתנה או נסגר. הנתונים רועננו"
                409 -> when {
                    "already pending" in detail -> "כבר עדכנת שהתשלום נשלח. ממתינים לאישור."
                    "no pending payment claim" in detail -> "אין דיווח תשלום שממתין לאישור. הנתונים רועננו"
                    "paid without a payment claim" in detail -> "החוב סומן כשולם ללא דיווח תשלום"
                    "since the payment was claimed" in detail -> "סכום החוב השתנה מאז דווח התשלום. הנתונים רועננו"
                    "creditor is no longer a member" in detail -> "מקבל התשלום כבר לא חבר בקבוצה"
                    "already been paid" in detail -> "החוב כבר שולם"
                    "sent recently" in detail -> "כבר נשלחה תזכורת לחוב הזה לאחרונה"
                    "amount of this settlement changed" in detail -> "סכום החוב השתנה. הנתונים רועננו"
                    else -> "הפעולה לא יכולה להתבצע כרגע. הנתונים רועננו"
                }
                in 500..599 -> if (action in isPaymentFlow) "לא ניתן להתחבר לשרת. נסי שוב."
                else "שגיאת שרת. נסו שוב מאוחר יותר"
                else -> "הבקשה נכשלה (${e.code()}). נסו שוב"
            }
        }
        e is IOException && e.message == "NOT_SIGNED_IN" -> "המשתמש אינו מחובר"
        e is IOException && (e.message == "NO_TOKEN" || e.message == "TOKEN_FAILED") ->
            "אימות המשתמש נכשל. התחברו מחדש"
        e is IOException ->
            if (action in isPaymentFlow) "לא ניתן להתחבר לשרת. נסי שוב."
            else "אין חיבור לשרת. בדקו שהטלפון והמחשב באותה רשת ושהשרת פועל"
        else -> "אירעה שגיאה. נסו שוב"
    }
}
