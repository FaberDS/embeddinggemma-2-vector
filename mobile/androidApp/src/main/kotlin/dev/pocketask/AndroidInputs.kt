package dev.pocketask

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

class AndroidInputs(private val application: Application) : PlatformInputs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activity: ComponentActivity? = null
    private lateinit var files: ActivityResultLauncher<Array<String>>
    private lateinit var photos: ActivityResultLauncher<PickVisualMediaRequest>
    private var pending: ImportResult? = null
    init { PDFBoxResourceLoader.init(application) }
    fun attach(owner: ComponentActivity) {
        activity = owner
        files = owner.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected -> import(selected, false) }
        photos = owner.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { selected -> import(selected, true) }
    }
    override fun pick(images: Boolean, callback: ImportResult) {
        pending = callback
        if (images) photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        else files.launch(arrayOf("application/pdf", "text/plain", "text/markdown", "text/x-markdown"))
    }
    private fun import(uris: List<Uri>, image: Boolean) {
        val callback = pending ?: return
        pending = null
        scope.launch {
            try {
                val staging = File(application.filesDir, "staging").apply { mkdirs() }
                uris.forEach { uri ->
                    val name = application.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "Attachment"
                    val target = File(staging, UUID.randomUUID().toString())
                    var type = application.contentResolver.getType(uri) ?: when (name.substringAfterLast('.').lowercase()) { "pdf" -> "application/pdf"; "md" -> "text/markdown"; else -> "text/plain" }
                    if (image) {
                        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(application.contentResolver, uri)) { decoder, info, _ ->
                            val factor = minOf(1.0, 1600.0 / max(info.size.width, info.size.height))
                            decoder.setTargetSize((info.size.width * factor).roundToInt().coerceAtLeast(1), (info.size.height * factor).roundToInt().coerceAtLeast(1))
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle(); type = "image/jpeg"
                    } else {
                        require(freeBytes() > 32_000_000) { "Not enough storage to import $name." }
                        checkNotNull(application.contentResolver.openInputStream(uri)).use { input -> target.outputStream().use { input.copyTo(it, 65536) } }
                        if (name.endsWith(".md", true) || type == "text/x-markdown") type = "text/markdown"
                    }
                    callback.item(name, target.absolutePath, type)
                }
                callback.success()
            } catch (e: Exception) { callback.failure(e.message ?: "The selection could not be imported.") }
        }
    }
    override fun pageCount(attachment: Attachment, callback: CountResult) {
        scope.launch {
            try {
                val count = if (attachment.type == "application/pdf") ParcelFileDescriptor.open(File(attachment.path), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { it.pageCount } } else 1
                callback.success(count)
            } catch (e: Exception) { callback.failure("${attachment.name}: ${e.message ?: "could not open this file"}") }
        }
    }
    override fun readPage(attachment: Attachment, page: Int, callback: PageResult) {
        scope.launch {
            try {
                val input = when {
                    attachment.isImage -> PageInput("", attachment.path)
                    attachment.type == "application/pdf" -> {
                        val text = PDDocument.load(File(attachment.path), MemoryUsageSetting.setupTempFileOnly()).use { document ->
                            require(!document.isEncrypted) { "Encrypted PDFs are not supported." }
                            PDFTextStripper().apply { startPage = page + 1; endPage = page + 1 }.getText(document)
                        }
                        val image = File(File(attachment.path).parentFile, "page-$page.jpg")
                        if (!image.exists()) ParcelFileDescriptor.open(File(attachment.path), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            PdfRenderer(descriptor).use { renderer -> renderer.openPage(page).use { pdfPage ->
                                val scale = 1400.0 / max(pdfPage.width, pdfPage.height)
                                val bitmap = Bitmap.createBitmap((pdfPage.width * scale).roundToInt().coerceAtLeast(1), (pdfPage.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE); pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
                            } }
                        }
                        PageInput(text, image.absolutePath)
                    }
                    else -> {
                        require(File(attachment.path).length() <= 20_000_000) { "Text files larger than 20 MB must be split before import." }
                        PageInput(File(attachment.path).readText(Charsets.UTF_8), null)
                    }
                }
                callback.success(input)
            } catch (e: Exception) { callback.failure("${attachment.name}: ${e.message ?: "page could not be read"}") }
        }
    }
    override fun open(path: String, page: Int) {
        val uri = FileProvider.getUriForFile(application, "${application.packageName}.files", File(path))
        val type = when (File(path).extension) { "pdf" -> "application/pdf"; "jpg" -> "image/jpeg"; else -> "text/plain" }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra("page", page)
        activity?.let { owner -> runCatching { owner.startActivity(intent) }.onFailure { android.widget.Toast.makeText(owner, "Install a viewer to open this source.", android.widget.Toast.LENGTH_SHORT).show() } }
    }
    override fun freeBytes() = StatFs(application.filesDir.absolutePath).availableBytes
    override fun canDownload(cellular: Boolean): Boolean {
        val manager = application.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && (cellular || !manager.isActiveNetworkMetered)
    }
    override fun now() = System.currentTimeMillis()
    fun close() { scope.cancel(); activity = null }
}
