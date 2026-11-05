package recloudstream

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.USER_AGENT
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny translation helper: Spanish if the phone is in Spanish, English otherwise. */
internal fun tr(es: String, en: String): String {
    return if (Locale.getDefault().language == "es") es else en
}

/** Keeps everything configured in the settings screen. */
class XtreamStore(private val prefs: SharedPreferences) {

    /** The lists as typed (also the incomplete ones, so a half-filled form is not lost). */
    fun accountForms(): List<XtreamAccountForm> {
        return XtreamAccountForms.decode(prefs.getString(KEY_ACCOUNTS, "").orEmpty())
    }

    /** The lists that can actually be used. */
    fun accounts(): List<XtreamAccount> = XtreamAccountForms.convert(accountForms()).accounts

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
        accountForms: List<XtreamAccountForm>,
        live: Boolean,
        movies: Boolean,
        series: Boolean,
        filter: String,
        userAgent: String,
        preferTs: Boolean,
    ) {
        prefs.edit()
            .putString(KEY_ACCOUNTS, XtreamAccountForms.encode(accountForms))
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

// -------------------------------------------------------------------------------------------------
// One list = one group of three boxes
// -------------------------------------------------------------------------------------------------

private class AccountRow(
    context: Context,
    form: XtreamAccountForm,
    maskPassword: Boolean,
    onRemove: (AccountRow) -> Unit,
) {
    /** A name given by the first version of the plugin; it is not shown, only kept. */
    private val keptName = form.name

    val title = TextView(context)
    val server = EditText(context)
    val username = EditText(context)
    val password = EditText(context)
    val view = LinearLayout(context)

    init {
        val density = context.resources.displayMetrics.density
        fun px(dp: Int): Int = (dp * density).toInt()

        view.orientation = LinearLayout.VERTICAL
        view.setPadding(px(12), px(10), px(12), px(12))
        view.background = GradientDrawable().apply {
            setStroke(px(1), 0x66888888)
            cornerRadius = px(10).toFloat()
        }

        title.textSize = 15f
        title.setTypeface(title.typeface, Typeface.BOLD)
        val remove = Button(context).apply {
            text = tr("Eliminar", "Remove")
            setAllCaps(false)
            textSize = 12f
            setOnClickListener { onRemove(this@AccountRow) }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(remove)
        }
        view.addView(header)

        configure(
            server, tr("Servidor (ej. http://servidor:8080)", "Server (e.g. http://server:8080)"),
            form.server, InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_NEXT
        )
        configure(username, tr("Usuario", "Username"), form.username, 0, EditorInfo.IME_ACTION_NEXT)
        configure(password, tr("Contraseña", "Password"), form.password, 0, EditorInfo.IME_ACTION_DONE)
        setPasswordVisible(!maskPassword)

        for (box in listOf(server, username, password)) {
            view.addView(
                box,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = px(6) }
            )
        }
    }

    fun toForm() = XtreamAccountForm(
        server = server.text.toString(),
        username = username.text.toString(),
        password = password.text.toString(),
        name = keptName,
    )

    fun setPasswordVisible(visible: Boolean) {
        password.transformationMethod = if (visible) {
            HideReturnsTransformationMethod.getInstance()
        } else {
            PasswordTransformationMethod.getInstance()
        }
    }

    private fun configure(edit: EditText, hintText: String, value: String, variation: Int, ime: Int) {
        edit.setText(value)
        edit.hint = hintText
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or variation
        edit.setSingleLine(true)
        edit.imeOptions = ime
    }
}

private val HELP_ES = """
Escribe el servidor, el usuario y la contraseña de tu lista Xtream. Para otra lista, pulsa «Añadir otra lista».

Si tu proveedor te dio un enlace M3U (el que contiene username= y password=), pégalo entero en «Servidor» y deja las otras dos casillas vacías.
""".trimIndent()

private val HELP_EN = """
Enter the server, username and password of your Xtream list. For another list, tap "Add another list".

If your provider gave you an M3U link (the one that contains username= and password=), paste it whole into "Server" and leave the other two boxes empty.
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

    // ---- the lists --------------------------------------------------------------------------
    val rows = ArrayList<AccountRow>()
    val rowsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var passwordsVisible = false

    fun renumber() {
        rows.forEachIndexed { index, row -> row.title.text = tr("Lista ${index + 1}", "List ${index + 1}") }
    }

    fun addRow(form: XtreamAccountForm): AccountRow {
        val row = AccountRow(context, form, !passwordsVisible) { removed ->
            rows.remove(removed)
            rowsContainer.removeView(removed.view)
            if (rows.isEmpty()) addRow(XtreamAccountForm()) // there is always one list to fill in
            renumber()
        }
        rows.add(row)
        rowsContainer.addView(
            row.view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(10) }
        )
        renumber()
        return row
    }

    store.accountForms().ifEmpty { listOf(XtreamAccountForm()) }.forEach { addRow(it) }

    val addButton = Button(context).apply {
        text = tr("+ Añadir otra lista", "+ Add another list")
        setAllCaps(false)
        setOnClickListener { addRow(XtreamAccountForm()).server.requestFocus() }
    }
    val showPasswordsBox = CheckBox(context).apply {
        text = tr("Mostrar contraseñas", "Show passwords")
        setOnCheckedChangeListener { _, checked ->
            passwordsVisible = checked
            rows.forEach { it.setPasswordVisible(checked) }
        }
    }

    // ---- the rest of the settings ------------------------------------------------------------
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

    fun currentForms(): List<XtreamAccountForm> = rows.map { it.toForm() }.filter { !it.isBlank }

    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(12), px(20), 0)
        addView(TextView(context).apply {
            text = tr(HELP_ES, HELP_EN)
            textSize = 13f
        })
        addView(rowsContainer)
        addView(
            addButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(10) }
        )
        addView(showPasswordsBox)
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
            val forms = currentForms()
            val conversion = XtreamAccountForms.convert(forms)
            store.save(
                accountForms = forms,
                live = liveBox.isChecked,
                movies = moviesBox.isChecked,
                series = seriesBox.isChecked,
                filter = filterInput.text.toString(),
                userAgent = userAgentInput.text.toString(),
                preferTs = tsBox.isChecked,
            )
            onSaved()
            var message = tr(
                "${conversion.accounts.size} lista(s) guardada(s). Vuelve a abrir el proveedor para recargar.",
                "${conversion.accounts.size} list(s) saved. Reopen the provider to reload."
            )
            if (conversion.ignored > 0) {
                message += "\n" + tr(
                    "${conversion.ignored} incompleta(s): falta el servidor, el usuario o la contraseña.",
                    "${conversion.ignored} incomplete: the server, username or password is missing."
                )
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
        .setNeutralButton(tr("Probar conexión", "Test connection"), null)
        .setNegativeButton(tr("Cancelar", "Cancel"), null)
        .create()

    // The neutral button must not close the dialog, so its listener is installed after show().
    dialog.setOnShowListener {
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            runConnectionTest(context, XtreamAccountForms.convert(currentForms()), userAgentInput.text.toString())
        }
    }
    // Lets the form shrink above the keyboard instead of hiding behind it.
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    dialog.show()
}

// -------------------------------------------------------------------------------------------------
// "Test connection"
// -------------------------------------------------------------------------------------------------

private fun runConnectionTest(context: Context, conversion: XtreamConversion, userAgent: String) {
    if (conversion.accounts.isEmpty()) {
        Toast.makeText(
            context,
            tr(
                "Rellena el servidor, el usuario y la contraseña de al menos una lista.",
                "Fill in the server, username and password of at least one list."
            ),
            Toast.LENGTH_LONG
        ).show()
        return
    }
    Toast.makeText(context, tr("Probando la conexión...", "Testing the connection..."), Toast.LENGTH_SHORT).show()

    val mainHandler = Handler(Looper.getMainLooper())
    Thread {
        val parts = conversion.accounts.map { describeAccount(it, userAgent) }.toMutableList()
        if (conversion.ignored > 0) {
            parts.add(
                "⚠️ " + tr(
                    "${conversion.ignored} lista(s) incompleta(s) (falta el servidor, el usuario o la contraseña) no se probaron.",
                    "${conversion.ignored} incomplete list(s) (the server, username or password is missing) were not tested."
                )
            )
        }
        val report = parts.joinToString("\n\n")
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
        // Same User-Agent the plugin sends when loading the catalogues, so the test says what the real use will say.
        connection.setRequestProperty("User-Agent", userAgent.trim().ifEmpty { USER_AGENT })
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
