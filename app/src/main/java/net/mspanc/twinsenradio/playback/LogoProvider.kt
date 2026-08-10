package net.mspanc.twinsenradio.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Station
import java.io.File

/**
 * Wystawia logotypy stacji pod **stalym** adresem `content://.../logo/<id>`.
 *
 * Po co to w ogole istnieje: wczesniej logo jechalo do Android Auto jako
 * `android.resource://net.mspanc.twinsenradio/2131165359`. W takim adresie
 * siedzi numeryczny identyfikator zasobu, a ten zmienia sie przy niemal kazdej
 * przebudowie aplikacji - wystarczy dolozyc jeden plik do res/drawable. Android
 * Auto pamieta pobrane grafiki pod adresem, wiec po aktualizacji ten sam numer
 * wskazywal juz inna stacje i HDU rysowalo logo z pamieci - stad "RMF FM" pod
 * logotypem Radia Nowy Swiat.
 *
 * Tutaj adres opisuje stacje, a nie zasob, wiec jest stabilny miedzy wersjami.
 * Numer zasobu doklejamy jako parametr `?v=`, zeby przy podmianie samej grafiki
 * (ten sam identyfikator stacji, inny plik) cache uniewaznil sie dokladnie raz.
 */
class LogoProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    /**
     * Grafike oddajemy z pliku w cache, a nie z potoku: czytelnicy po drugiej
     * stronie (Android Auto, Glide) potrafia pytac o rozmiar i przewijac, a
     * potok tego nie obsluguje.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val file = renderToCache(context, uri) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val context = context ?: return null
        val file = renderToCache(context, uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> file.name
                        OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                }.toTypedArray()
            )
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0

    /**
     * Zamienia zasob na plik PNG. Rysujemy raz - kolejne odczyty ida juz z dysku,
     * a nazwa pliku zawiera numer zasobu, wiec po przebudowie powstaje nowy plik.
     */
    private fun renderToCache(context: Context, uri: Uri): File? = runCatching {
        val resId = uri.getQueryParameter(PARAM_VERSION)?.toIntOrNull()
            ?: R.drawable.logo_placeholder
        val dir = File(context.cacheDir, "logo").apply { mkdirs() }
        val file = File(dir, "${uri.lastPathSegment}-$resId.png")
        if (file.exists() && file.length() > 0) return@runCatching file

        val drawable = ContextCompat.getDrawable(context, resId) ?: return@runCatching null
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: run {
            Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { bmp ->
                drawable.setBounds(0, 0, SIZE, SIZE)
                drawable.draw(Canvas(bmp))
            }
        }
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        file
    }.onFailure { Log.w(TAG, "nie udalo sie przygotowac logo dla $uri: ${it.message}") }
        .getOrNull()

    companion object {
        private const val TAG = "LogoProvider"
        private const val PARAM_VERSION = "v"
        private const val SIZE = 512

        /** Musi zgadzac sie z android:authorities w manifescie. */
        fun authority(context: Context): String = "${context.packageName}.logos"

        /**
         * @param resId numer zasobu z tej wersji aplikacji - trafia do adresu
         *   wylacznie po to, zeby uniewaznic cache po podmianie grafiki.
         */
        fun uriFor(context: Context, station: Station, resId: Int): Uri =
            build(context, "logo", station.id, resId)

        /**
         * Adres ikony przycisku w Android Auto. Powod jest ten sam co przy logo,
         * tylko objaw inny: przyciski dostawaly numer zasobu, a HDU pamieta
         * narysowana ikone pod tym numerem. Po przebudowie aplikacji numery
         * przesuwaja sie (wystarczy dolozyc jeden plik do res/drawable) i w
         * miejscu zapelnionej gwiazdki pojawialo sie to, co wczesniej mialo jej
         * numer - pusta gwiazdka albo wrecz kwadrat.
         */
        fun iconUri(context: Context, name: String, resId: Int): Uri =
            build(context, "icon", name, resId)

        private fun build(context: Context, kind: String, name: String, resId: Int): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(kind)
                .appendPath(name)
                .appendQueryParameter(PARAM_VERSION, resId.toString())
                .build()
    }
}
