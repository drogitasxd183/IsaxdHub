package recloudstream

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny translation helper: Spanish if the phone is in Spanish, English otherwise. */
internal fun tr(es: String, en: String): String {
    return if (Locale.getDefault().language == "es") es else en
}

/** Keeps everything configured in the settings dialog. */
class XtreamStore(private val prefs: SharedPreferences) {

    fun accountsText(): String = prefs.getString(KEY_ACCOUNTS, "").orEmpty()
    fun accounts(): List<XtreamAccount> = XtreamAccounts.parse(accountsText())

    fun showLive(): Boolean = prefs.getBoolean(KEY_LIVE, true)
    fun showMovies(): Boolean = prefs.getBoolean(KEY_MOVIES, true)
    fun showSeries(): Boolean = prefs.getBoolean(KEY_SERIES, true)

    fun categoryFilter(): String = prefs.getString(KEY_FILTER, "").orEmpty()
    fun userAgent(): String = prefs.getString(KEY_USER_AGENT, "").orEmpty().trim()
    fun preferTs(): Boolean = prefs.getBoolean(KEY_PREFER_TS, false)

    fun enabledSections(): List<XtreamSection> {
        return XtreamSection.entries.filter {
            when (it) {
                XtreamSection.LIVE -> showLive()
                XtreamSection.VOD -> showMovies()
                XtreamSection.SERIES -> showSeries()
            }
        }
    }

    fun save(
        accountsText: String,
        live: Boolean,
        movies: Boolean,
        series: Boolean,
        filter: String,
        userAgent: String,
        preferTs: Boolean,
    ) {
        prefs.edit()
            .putString(KEY_ACCOUNTS, accountsText)
            .putBoolean(KEY_LIVE, live)
            .putBoolean(KEY_MOVIES, movies)
            .putBoolean(KEY_SERIES, series)
            .putString(KEY_FILTER, filter)
            .putString(KEY_USER_AGENT, userAgent.trim())
            .putBoolean(KEY_PREFER_TS, preferTs)
            .apply()
    }

    private companion object {
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_LIVE = "show_live"
        const val KEY_MOVIES = "show_movies"
        const val KEY_SERIES = "show_series"
        const val KEY_FILTER = "category_filter"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_PREFER_TS = "prefer_ts"
    }
}

private val HELP_ES = """
Una cuenta por línea:
• Mi proveedor | http://servidor:8080 | usuario | contraseña
• http://servidor:8080 | usuario | contraseña
• O pega el enlace M3U que te dio tu proveedor (el que contiene username= y password=)

Las líneas que empiezan con # se ignoran. Después de guardar, vuelve a abrir este proveedor.
""".trimIndent()

private val HELP_EN = """
One account per line:
• My provider | http://server:8080 | username | password
• http://server:8080 | username | password
• Or paste the M3U link your provider gave you (the one that contains username= and password=)

Lines starting with # are ignored. After saving, reopen this provider.
""".trimIndent()

/** Opened from the gear icon next to the provider name (or Settings → Extensions → Xtream Player). */
internal fun showXtreamSettings(context: Context, store: XtreamStore, onSaved: () -> Unit) {
    val density = context.resources.displayMetrics.density
    fun px(dp: Int): Int = (dp * density).toInt()

    fun label(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setPadding(0, px(16), 0, px(4))
    }

    fun singleLine(value: String, hintText: String) = EditText(context).apply {
        setText(value)
        hint = hintText
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    val accountsInput = EditText(context).apply {
        setText(store.accountsText())
        hint = "http://server:8080 | username | password"
        inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 4
        maxLines = 8
        gravity = Gravity.TOP or Gravity.START
    }
    val liveBox = CheckBox(context).apply {
        text = tr("TV en vivo", "Live TV")
        isChecked = store.showLive()
    }
    val moviesBox = CheckBox(context).apply {
        text = tr("Películas", "Movies")
        isChecked = store.showMovies()
    }
    val seriesBox = CheckBox(context).apply {
        text = tr("Series", "Series")
        isChecked = store.showSeries()
    }
    val filterInput = singleLine(store.categoryFilter(), "LATINO, ES, -ADULT")
    val userAgentInput = singleLine(store.userAgent(), "VLC/3.0.20 LibVLC/3.0.20")
    val tsBox = CheckBox(context).apply {
        text = tr("Canales en vivo: usar TS en vez de HLS (m3u8)", "Live channels: use TS instead of HLS (m3u8)")
        isChecked = store.preferTs()
    }

    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(12), px(20), 0)
        addView(TextView(context).apply {
            text = tr(HELP_ES, HELP_EN)
            textSize = 13f
        })
        addView(
            accountsInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(12) }
        )
        addView(label(tr("Secciones", "Sections")))
        addView(liveBox)
        addView(moviesBox)
        addView(seriesBox)
        addView(
            label(
                tr(
                    "Filtro de categorías (opcional)\nPalabras separadas por coma; con - se excluyen. Ej.: LATINO, ES, -ADULTOS",
                    "Category filter (optional)\nWords separated by commas; a leading - excludes. E.g. LATINO, ES, -ADULT"
                )
            )
        )
        addView(filterInput)
        addView(label(tr("User-Agent (opcional, algunos proveedores lo exigen)", "User-Agent (optional, some providers require it)")))
        addView(userAgentInput)
        addView(tsBox)
    }

    val dialog = AlertDialog.Builder(context)
        .setTitle("Xtream Player")
        .setView(ScrollView(context).apply { addView(layout) })
        .setPositiveButton(tr("Guardar", "Save")) { _, _ ->
            val text = accountsInput.text.toString()
            store.save(
                accountsText = text,
                live = liveBox.isChecked,
                movies = moviesBox.isChecked,
                series = seriesBox.isChecked,
                filter = filterInput.text.toString(),
                userAgent = userAgentInput.text.toString(),
                preferTs = tsBox.isChecked,
            )
            onSaved()
            val count = XtreamAccounts.parse(text).size
            Toast.makeText(
                context,
                tr(
                    "$count cuenta(s) guardada(s). Vuelve a abrir el proveedor para recargar.",
                    "$count account(s) saved. Reopen the provider to reload."
                ),
                Toast.LENGTH_LONG
            ).show()
        }
        .setNeutralButton(tr("Probar conexión", "Test connection"), null)
        .setNegativeButton(tr("Cancelar", "Cancel"), null)
        .create()

    // The neutral button must not close the dialog, so its listener is installed after show().
    dialog.setOnShowListener {
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            runConnectionTest(context, accountsInput.text.toString(), userAgentInput.text.toString())
        }
    }
    dialog.show()
}

// -------------------------------------------------------------------------------------------------
// "Test connection"
// -------------------------------------------------------------------------------------------------

private fun runConnectionTest(context: Context, accountsText: String, userAgent: String) {
    val accounts = XtreamAccounts.parse(accountsText)
    if (accounts.isEmpty()) {
        Toast.makeText(context, tr("Escribe al menos una cuenta.", "Enter at least one account."), Toast.LENGTH_LONG).show()
        return
    }
    Toast.makeText(context, tr("Probando la conexión...", "Testing the connection..."), Toast.LENGTH_SHORT).show()

    val mainHandler = Handler(Looper.getMainLooper())
    Thread {
        val report = accounts.joinToString("\n\n") { describeAccount(it, userAgent) }
        mainHandler.post {
            try {
                AlertDialog.Builder(context)
                    .setTitle(tr("Resultado", "Result"))
                    .setMessage(report)
                    .setPositiveButton("OK", null)
                    .show()
            } catch (_: Exception) {
                // the screen was closed while the test was running
            }
        }
    }.start()
}

private fun describeAccount(account: XtreamAccount, userAgent: String): String {
    return try {
        val info = fetchUserInfo(account, userAgent)
        if (!info.authenticated) {
            return "❌ ${account.name}\n" + tr("Usuario o contraseña incorrectos.", "Wrong username or password.")
        }
        val expiry = info.expiry?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it * 1000L)) }
            ?: tr("sin vencimiento", "no expiry")
        val mark = if (info.isBlocked) "⚠️" else "✅"
        buildString {
            append("$mark ${account.name}\n")
            append(tr("Estado: ", "Status: ")).append(info.status ?: "?").append('\n')
            append(tr("Vence: ", "Expires: ")).append(expiry).append('\n')
            append(tr("Conexiones: ", "Connections: ")).append(info.activeConnections ?: "?").append('/').append(info.maxConnections ?: "?").append('\n')
            append(tr("Formatos en vivo: ", "Live formats: ")).append(if (info.formats.isEmpty()) "?" else info.formats.joinToString(", "))
            if (!info.message.isNullOrBlank()) append('\n').append(info.message)
        }
    } catch (e: IllegalArgumentException) {
        "❌ ${account.name}\n" + tr(
            "El servidor no devolvió datos Xtream válidos (¿dirección o puerto incorrectos?).",
            "The server did not return valid Xtream data (wrong address or port?)."
        )
    } catch (e: Exception) {
        "❌ ${account.name}\n${e.message ?: e.javaClass.simpleName}"
    }
}

private fun fetchUserInfo(account: XtreamAccount, userAgent: String): XtreamUserInfo {
    val connection = URL(account.apiUrl()).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        if (userAgent.isNotBlank()) connection.setRequestProperty("User-Agent", userAgent.trim())
        val code = connection.responseCode
        check(code in 200..299) { "HTTP $code" }

        val text = StringBuilder()
        connection.inputStream.bufferedReader().use { reader ->
            val buffer = CharArray(4096)
            while (text.length < 1_000_000) {
                val read = reader.read(buffer)
                if (read < 0) break
                text.append(buffer, 0, read)
            }
        }
        return XtreamParser.userInfo(XtreamJson.parseTree(text.toString()))
    } finally {
        connection.disconnect()
    }
}
