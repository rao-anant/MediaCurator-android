package com.anant.mediacurator

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anant.mediacurator.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-level settings, reachable from every screen's overflow (Help + Settings):
 * PDF content-search toggle, Export/Import of the hidden-months list, and Share diagnostics.
 * Works off [PreferencesManager] directly — no GalleryViewModel needed.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { PreferencesManager(this) }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importHiddenMonths(it) } }

    private val mediaLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            prefs.setPlaceSearchEnabled(true)
            binding.switchPlace.isChecked = true
            toast("Place search on — photos are located in the background as you browse")
        } else {
            binding.switchPlace.isChecked = false
            toast("Location access is needed for place search")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        binding.switchPdf.isChecked = prefs.isPdfContentSearchEnabled()
        binding.switchPdf.setOnClickListener { onPdfToggle(binding.switchPdf.isChecked) }

        binding.switchPlace.isChecked = prefs.isPlaceSearchEnabled()
        binding.switchPlace.setOnClickListener { onPlaceToggle(binding.switchPlace.isChecked) }

        binding.btnExport.setOnClickListener { exportHiddenMonths() }
        binding.btnImport.setOnClickListener { importLauncher.launch(arrayOf("application/json", "text/*")) }
        binding.cardDiagnostics.setOnClickListener { DiagnosticsShare.present(this) }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── PDF content search ──────────────────────────────────────────────────────

    private fun onPdfToggle(nowEnabled: Boolean) {
        if (!nowEnabled) {
            // Disabling — warn, and let Cancel revert the switch.
            AlertDialog.Builder(this)
                .setTitle("Disable PDF content search?")
                .setMessage(
                    "Background indexing will stop and PDF results will be matched by " +
                    "file name only.\n\nThe existing index files are kept — re-enabling " +
                    "will pick up where it left off."
                )
                .setPositiveButton("Disable") { _, _ ->
                    prefs.setPdfContentSearchEnabled(false)
                    toast("PDF content search disabled")
                }
                .setNegativeButton("Cancel") { _, _ -> binding.switchPdf.isChecked = true }
                .setOnCancelListener { binding.switchPdf.isChecked = true }
                .show()
        } else {
            prefs.setPdfContentSearchEnabled(true)
            toast("PDF content search enabled — indexing resumes in the app")
        }
    }

    // ── Place search ──────────────────────────────────────────────────────────────

    private fun onPlaceToggle(nowEnabled: Boolean) {
        if (nowEnabled) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_MEDIA_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                prefs.setPlaceSearchEnabled(true)
                toast("Place search on — photos are located in the background as you browse")
            } else {
                // Ask for photo-location access; the launcher enables (or reverts the switch).
                mediaLocationLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle("Turn off place search?")
                .setMessage(
                    "Search will no longer match photos by place, and the locations stored on this " +
                    "device will be cleared. Your photos are not affected."
                )
                .setPositiveButton("Turn off") { _, _ ->
                    prefs.setPlaceSearchEnabled(false)
                    Thread { PlaceStore.getInstance(applicationContext).clear() }.start()
                    toast("Place search off")
                }
                .setNegativeButton("Cancel") { _, _ -> binding.switchPlace.isChecked = true }
                .setOnCancelListener { binding.switchPlace.isChecked = true }
                .show()
        }
    }

    // ── Hidden-months list export / import ────────────────────────────────────────

    private fun exportHiddenMonths() {
        val months = prefs.getDoneMonths()
        if (months.isEmpty()) { toast("No hidden months to export"); return }
        lifecycleScope.launch {
            try {
                val stamp    = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
                val filename = "mediacurator_hidden_$stamp.json"
                val json     = buildExportJson(months)
                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, filename)
                            put(MediaStore.Downloads.MIME_TYPE, "application/json")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = contentResolver.insert(
                            MediaStore.Downloads.getContentUri("external"), values
                        ) ?: throw Exception("Could not create file in Downloads")
                        contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        File(dir, filename).writeText(json, Charsets.UTF_8)
                    }
                }
                toast("Exported ${months.size} hidden months → Downloads/$filename")
            } catch (e: Exception) {
                Log.e("Settings", "Export failed", e)
                toast("Export failed: ${e.message}")
            }
        }
    }

    private fun buildExportJson(months: Set<String>): String {
        val ts  = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val arr = months.sorted().joinToString(",\n    ") { "\"$it\"" }
        return "{\n  \"version\": 1,\n  \"exportedAt\": \"$ts\",\n  \"hiddenMonths\": [\n    $arr\n  ]\n}"
    }

    private fun importHiddenMonths(uri: Uri) {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: run { toast("Could not read file"); return@launch }

                val arr      = org.json.JSONObject(json).getJSONArray("hiddenMonths")
                val incoming = (0 until arr.length()).map { arr.getString(it) }.toSet()
                val existing = prefs.getDoneMonths()
                val newCount = (incoming - existing).size
                prefs.setDoneMonths(existing + incoming)
                // No MediaCache.invalidate(): the raw media list is unchanged — doneMonths is
                // applied at grouping time, so Home/gallery re-group from prefs on their next load.

                toast("Import done — $newCount new months added (${existing.size + newCount} total hidden)")
            } catch (e: Exception) {
                Log.e("Settings", "Import failed", e)
                toast("Import failed: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
