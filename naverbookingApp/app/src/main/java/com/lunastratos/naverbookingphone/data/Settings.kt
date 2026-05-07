package com.lunastratos.naverbookingphone.data

import android.content.Context
import android.content.SharedPreferences

data class SelectedItem(val title: String, val url: String) {
    fun encode(): String = "$title$SEP$url"
    companion object {
        private const val SEP = ""
        fun decode(s: String): SelectedItem? {
            val parts = s.split(SEP, limit = 2)
            return if (parts.size == 2) SelectedItem(parts[0], parts[1]) else null
        }
    }
}

class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var placeId: String
        get() = prefs.getString(KEY_PLACE_ID, DEFAULT_PLACE_ID) ?: DEFAULT_PLACE_ID
        set(v) { prefs.edit().putString(KEY_PLACE_ID, v).apply() }

    var intervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL).coerceAtLeast(MIN_INTERVAL)
        set(v) { prefs.edit().putInt(KEY_INTERVAL, v.coerceAtLeast(MIN_INTERVAL)).apply() }

    var monthsToCheck: Int
        get() = prefs.getInt(KEY_MONTHS, DEFAULT_MONTHS).coerceIn(1, 12)
        set(v) { prefs.edit().putInt(KEY_MONTHS, v.coerceIn(1, 12)).apply() }

    var lastSeenKeys: Set<String>
        get() = prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        set(v) { prefs.edit().putStringSet(KEY_SEEN, v).apply() }

    var selectedItems: List<SelectedItem>
        get() = (prefs.getStringSet(KEY_SELECTED, emptySet()) ?: emptySet())
            .mapNotNull { SelectedItem.decode(it) }
            .sortedBy { it.title }
        set(v) {
            prefs.edit().putStringSet(KEY_SELECTED, v.map { it.encode() }.toSet()).apply()
        }

    var emailEnabled: Boolean
        get() = prefs.getBoolean(KEY_EMAIL_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_EMAIL_ENABLED, v).apply() }

    var smtpHost: String
        get() = prefs.getString(KEY_SMTP_HOST, "smtp.naver.com") ?: "smtp.naver.com"
        set(v) { prefs.edit().putString(KEY_SMTP_HOST, v).apply() }

    var smtpPort: Int
        get() = prefs.getInt(KEY_SMTP_PORT, 465)
        set(v) { prefs.edit().putInt(KEY_SMTP_PORT, v).apply() }

    var smtpSsl: Boolean
        get() = prefs.getBoolean(KEY_SMTP_SSL, true)
        set(v) { prefs.edit().putBoolean(KEY_SMTP_SSL, v).apply() }

    var smtpUser: String
        get() = prefs.getString(KEY_SMTP_USER, "") ?: ""
        set(v) { prefs.edit().putString(KEY_SMTP_USER, v).apply() }

    var smtpPassword: String
        get() = prefs.getString(KEY_SMTP_PW, "") ?: ""
        set(v) { prefs.edit().putString(KEY_SMTP_PW, v).apply() }

    var mailFrom: String
        get() = prefs.getString(KEY_MAIL_FROM, "") ?: ""
        set(v) { prefs.edit().putString(KEY_MAIL_FROM, v).apply() }

    var mailTo: String
        get() = prefs.getString(KEY_MAIL_TO, "") ?: ""
        set(v) { prefs.edit().putString(KEY_MAIL_TO, v).apply() }

    fun resetBaseline() {
        prefs.edit().remove(KEY_SEEN).apply()
    }

    companion object {
        const val PREFS = "naver_booking_prefs"
        const val KEY_PLACE_ID = "place_id"
        const val KEY_INTERVAL = "interval_seconds"
        const val KEY_MONTHS = "months_to_check"
        const val KEY_SEEN = "last_seen_keys"
        const val KEY_SELECTED = "selected_items"

        const val KEY_EMAIL_ENABLED = "email_enabled"
        const val KEY_SMTP_HOST = "smtp_host"
        const val KEY_SMTP_PORT = "smtp_port"
        const val KEY_SMTP_SSL = "smtp_ssl"
        const val KEY_SMTP_USER = "smtp_user"
        const val KEY_SMTP_PW = "smtp_password"
        const val KEY_MAIL_FROM = "mail_from"
        const val KEY_MAIL_TO = "mail_to"

        const val DEFAULT_PLACE_ID = "1610165006"
        const val DEFAULT_INTERVAL = 60
        const val DEFAULT_MONTHS = 4
        const val MIN_INTERVAL = 10
    }
}
