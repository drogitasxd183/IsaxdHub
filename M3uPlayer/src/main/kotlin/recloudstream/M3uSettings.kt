package recloudstream

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Tiny translation helper: Spanish if the phone is in Spanish, English otherwise. */
internal fun tr(es: String, en: String): String {
    return if (Locale.getDefault().language == "es") es else en
}

/** Keeps the list of playlists typed by the user. */
class M3uStore(private val prefs: SharedPreferences) {

    /** The raw text of the settings box. */
    fun rawText(): String = prefs.getString(KEY_PLAYLISTS, "").orEmpty()

    fun save(text: String) {
        prefs.edit().putString(KEY_PLAYLISTS, text).apply()
    }

    fun sources(): List<PlaylistSource> = M3uParser.parseSources(rawText())

    private companion object {
        const val KEY_PLAYLISTS = "playlists"
    }
}

private val HELP_ES = """
Una lista por línea:
• https://servidor/lista.m3u
• Mi lista | https://servidor/lista.m3u8
• /sdcard/Download/lista.m3u (si la app tiene acceso al archivo)

Si tu proveedor lo pide, agrega cabeceras al final: lista.m3u|User-Agent=VLC

Las líneas que empiezan con # se ignoran. Al guardar, vuelve a abrir este proveedor para recargar los canales.
""".trimIndent()

private val HELP_EN = """
One playlist per line:
• https://server/list.m3u
• My list | https://server/list.m3u8
• /sdcard/Download/list.m3u (if the app can access the file)

If your provider needs it, add headers at the end: list.m3u|User-Agent=VLC

Lines starting with # are ignored. After saving, reopen this provider to reload the channels.
""".trimIndent()

/** Opened from the gear icon next to the provider name (or Settings → Extensions → M3U Player). */
internal fun showM3uSettings(context: Context, store: M3uStore, onSaved: () -> Unit) {
    val density = context.resources.displayMetrics.density
    fun px(dp: Int): Int = (dp * density).toInt()

    val help = TextView(context).apply {
        text = tr(HELP_ES, HELP_EN)
        textSize = 13f
    }

    val input = EditText(context).apply {
        setText(store.rawText())
        hint = "https://example.com/list.m3u"
        inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 6
        maxLines = 14
        gravity = Gravity.TOP or Gravity.START
    }

    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(12), px(20), 0)
        addView(help)
        addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(12) }
        )
    }

    AlertDialog.Builder(context)
        .setTitle("M3U Player")
        .setView(ScrollView(context).apply { addView(layout) })
        .setPositiveButton(tr("Guardar", "Save")) { _, _ ->
            val text = input.text.toString()
            store.save(text)
            onSaved()
            val count = M3uParser.parseSources(text).size
            Toast.makeText(
                context,
                tr(
                    "$count lista(s) guardada(s). Vuelve a abrir el proveedor para recargar.",
                    "$count playlist(s) saved. Reopen the provider to reload."
                ),
                Toast.LENGTH_LONG
            ).show()
        }
        .setNegativeButton(tr("Cancelar", "Cancel"), null)
        .show()
}
