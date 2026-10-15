package com.example.myapplication.data.api

import java.io.IOException
import org.json.JSONObject
import retrofit2.HttpException

/** Which call failed; the same status code means different things for different actions. */
enum class ApiAction { GENERAL, MARK_PAID, REMINDER, CLAIM_PAYMENT, CONFIRM_PAYMENT, ADD_MEMBER, REMOVE_MEMBER, LEAVE_GROUP, LOAD_PROFILE, SAVE_AVATAR }

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
        action == ApiAction.SAVE_AVATAR -> when {
            e is IOException -> "לא ניתן להתחבר לשרת"
            e is HttpException && (e.code() == 422 || e.code() == 400) -> "בחירת האווטאר אינה תקינה"
            else -> "לא הצלחנו לשמור את האווטאר"
        }
        action == ApiAction.LOAD_PROFILE -> when {
            e is HttpException && e.code() == 401 -> "ההתחברות פגה. התחברו מחדש"
            e is HttpException && e.code() == 404 -> "פרופיל המשתמש לא נמצא"
            e is IOException -> "לא ניתן להתחבר לשרת"
            else -> "לא הצלחנו לטעון את האווטאר"
        }
        e is HttpException -> {
            val detail = detail(e).orEmpty()
            when (e.code()) {
                400 -> if (action == ApiAction.REMINDER) "לא ניתן לשלוח תזכורת: החייב כבר לא חבר בקבוצה"
                else if (action == ApiAction.REMOVE_MEMBER) "לא ניתן להסיר את עצמך מהקבוצה"
                else "הבקשה לא תקינה"
                401 -> "ההתחברות פגה. התחברו מחדש"
                403 -> when (action) {
                    ApiAction.ADD_MEMBER -> "אין לך הרשאה להוסיף חבר לקבוצה"
                    ApiAction.REMOVE_MEMBER, ApiAction.LEAVE_GROUP -> "אין לך הרשאה לבצע פעולה זו"
                    ApiAction.MARK_PAID -> "רק מי שמגיע לו הכסף יכול לסמן חוב כשולם"
                    ApiAction.REMINDER -> "רק מי שמגיע לו הכסף יכול לשלוח תזכורת"
                    ApiAction.CLAIM_PAYMENT, ApiAction.CONFIRM_PAYMENT -> "אין לך הרשאה לבצע פעולה זו"
                    ApiAction.GENERAL, ApiAction.LOAD_PROFILE, ApiAction.SAVE_AVATAR -> "אין לכם הרשאה לבצע פעולה זו"
                }
                404 -> if (action == ApiAction.ADD_MEMBER) "המשתמש לא נמצא"
                else if (action == ApiAction.REMOVE_MEMBER) "החבר כבר לא נמצא בקבוצה"
                else if (action == ApiAction.LEAVE_GROUP) "הקבוצה כבר לא קיימת או שאינך חברה בה"
                else if (action == ApiAction.GENERAL) "הפריט המבוקש לא נמצא"
                else "החוב השתנה או נסגר. הנתונים רועננו"
                409 -> if (action == ApiAction.ADD_MEMBER) "המשתמש כבר נמצא בקבוצה"
                else if (action == ApiAction.LEAVE_GROUP) "לא ניתן לעזוב את הקבוצה כל עוד את יוצרת הקבוצה"
                else if (action == ApiAction.REMOVE_MEMBER) "חברי הקבוצה השתנו בינתיים. הנתונים רועננו"
                else when {
                    "already pending" in detail -> "כבר נשלחה בקשת תשלום והיא ממתינה לאישור."
                    "no pending payment claim" in detail -> "אין דיווח תשלום שממתין לאישור. הנתונים רועננו"
                    "paid without a payment claim" in detail -> "החוב סומן כשולם ללא דיווח תשלום"
                    "since the payment was claimed" in detail -> "סכום החוב השתנה מאז דווח התשלום. הנתונים רועננו"
                    "creditor is no longer a member" in detail -> "מקבל התשלום כבר לא חבר בקבוצה"
                    "already been paid" in detail -> "החוב כבר שולם"
                    "sent recently" in detail -> "כבר נשלחה תזכורת לאחרונה. נסי שוב מאוחר יותר."
                    "debt changed from" in detail -> "סכום החוב השתנה. הנתונים רועננו"
                    "just paid by another" in detail -> "החוב כבר שולם. הנתונים רועננו"
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
            if (action == ApiAction.ADD_MEMBER || action == ApiAction.REMOVE_MEMBER || action == ApiAction.LEAVE_GROUP) "לא ניתן להתחבר לשרת"
            else if (action in isPaymentFlow) "לא ניתן להתחבר לשרת. נסי שוב."
            else "אין חיבור לשרת. בדקו שהטלפון והמחשב באותה רשת ושהשרת פועל"
        else -> "אירעה שגיאה. נסו שוב"
    }
}
