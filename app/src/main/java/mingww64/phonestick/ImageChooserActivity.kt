package mingww64.phonestick

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.content.DialogInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import androidx.transition.TransitionManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mingww64.phonestick.databinding.ActivityImageChooserBinding
import mingww64.phonestick.databinding.DialogCreateImageBinding
import java.io.File

class ImageChooserActivity : AppCompatActivity() {

    private companion object {
        const val CHANNEL_ID = "image_creation_channel"
        const val NOTIFICATION_ID = 1001
    }

    private lateinit var binding: ActivityImageChooserBinding
    private lateinit var adapter: ImageFilesAdapter
    private val imageFiles = mutableListOf<File>()
    private var isSpeedDialOpen = false
    private var currentlySelectedPath: String = ""

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore if not persistable
            }
            handleImportedUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageChooserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentlySelectedPath = intent.getStringExtra("selected_path") ?: ""

        binding.toolbar.setNavigationOnClickListener { finishWithResult() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFormatSelectionMode) {
                    exitFormatSelectionMode()
                } else {
                    finishWithResult()
                }
            }
        })

        setupNotificationChannel()
        setupSpeedDial()
        setupRecyclerView()
        loadImages()
    }

    private fun setupSpeedDial() {
        binding.fabMain.setOnClickListener {
            toggleSpeedDial()
        }

        binding.fabOverlay.setOnClickListener {
            if (isSpeedDialOpen) toggleSpeedDial()
        }

        binding.fabImportImage.setOnClickListener {
            toggleSpeedDial()
            val mimeTypes = arrayOf(
                "application/octet-stream",
                "application/x-iso9660-image",
                "application/x-raw-disk-image",
                "application/x-cd-image",
                "application/vnd.iso",
                "application/x-vhd",
                "application/x-qemu-disk"
            )
            importFileLauncher.launch(mimeTypes)
        }

        binding.fabCreateImage.setOnClickListener {
            toggleSpeedDial()
            showCreateImageDialog()
        }

        binding.fabFormatImage.setOnClickListener {
            toggleSpeedDial()
            showMultiFormatDialog()
        }
    }

    private fun toggleSpeedDial() {
        isSpeedDialOpen = !isSpeedDialOpen
        if (isSpeedDialOpen) {
            // Animate '+' icon rotating 135 degrees into an 'x' icon
            binding.fabMain.animate().rotation(135f).setDuration(250).start()

            binding.fabOverlay.visibility = View.VISIBLE
            binding.fabOverlay.alpha = 0f
            binding.fabOverlay.animate().alpha(1f).setDuration(200).start()

            binding.layoutImportImage.visibility = View.VISIBLE
            binding.layoutImportImage.alpha = 0f
            binding.layoutImportImage.translationY = 40f
            binding.layoutImportImage.scaleX = 0.8f
            binding.layoutImportImage.scaleY = 0.8f
            binding.layoutImportImage.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .start()

            binding.layoutCreateImage.visibility = View.VISIBLE
            binding.layoutCreateImage.alpha = 0f
            binding.layoutCreateImage.translationY = 40f
            binding.layoutCreateImage.scaleX = 0.8f
            binding.layoutCreateImage.scaleY = 0.8f
            binding.layoutCreateImage.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(250)
                .start()

            binding.layoutFormatImage.visibility = View.VISIBLE
            binding.layoutFormatImage.alpha = 0f
            binding.layoutFormatImage.translationY = 40f
            binding.layoutFormatImage.scaleX = 0.8f
            binding.layoutFormatImage.scaleY = 0.8f
            binding.layoutFormatImage.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .start()

        } else {
            // Animate 'x' icon rotating back 135 degrees into a '+' icon
            binding.fabMain.animate().rotation(0f).setDuration(200).start()

            binding.fabOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                binding.fabOverlay.visibility = View.GONE
            }.start()

            binding.layoutImportImage.animate()
                .alpha(0f)
                .translationY(40f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(150)
                .withEndAction {
                    binding.layoutImportImage.visibility = View.GONE
                }
                .start()

            binding.layoutCreateImage.animate()
                .alpha(0f)
                .translationY(40f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(150)
                .withEndAction {
                    binding.layoutCreateImage.visibility = View.GONE
                }
                .start()

            binding.layoutFormatImage.animate()
                .alpha(0f)
                .translationY(40f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(150)
                .withEndAction {
                    binding.layoutFormatImage.visibility = View.GONE
                }
                .start()
        }
    }

    private fun setupRecyclerView() {
        adapter = ImageFilesAdapter(
            files = imageFiles,
            selectedPath = currentlySelectedPath,
            onFileSelected = { file ->
                selectAndReturnFile(file.absolutePath)
            },
            onFileMount = { file ->
                selectAndReturnFile(file.absolutePath, autoMount = true)
            },
            onFileDeleted = { file ->
                handleDeleteFile(file)
            },
            onFileRenamed = { oldFile, newFile ->
                if (currentlySelectedPath == oldFile.absolutePath) {
                    currentlySelectedPath = newFile.absolutePath
                }
                loadImages()
            }
        )
        adapter.onFileLongClick = { file ->
            enterFormatSelectionMode(initialSelectedFile = file)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun handleDeleteFile(file: File) {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val status = UsbGadgetController.getMountStatus()
            val isMountedImage = status.isMounted && (status.currentFile == file.absolutePath || currentlySelectedPath == file.absolutePath)
            
            if (isMountedImage) {
                UsbGadgetController.unmountImage(this@ImageChooserActivity)
            }
            
            removeExternalImagePath(file.absolutePath)
            if (file.exists()) {
                file.delete()
            }

            withContext(Dispatchers.Main) {
                setLoading(false)
                if (currentlySelectedPath == file.absolutePath) {
                    currentlySelectedPath = ""
                }
                loadImages()
                val msg = if (isMountedImage) "Unmounted and removed ${file.name}" else "Removed ${file.name}"
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadImages() {
        lifecycleScope.launch(Dispatchers.IO) {
            val internalFiles = filesDir.listFiles { file ->
                file.isFile && isValidImageExtension(file.extension)
            }?.toList() ?: emptyList()

            val externalPaths = getExternalImagePaths()
            val validExternalPaths = mutableListOf<String>()

            if (externalPaths.isNotEmpty()) {
                val script = externalPaths.map { path ->
                    val escaped = path.replace("'", "'\\''")
                    "if [ -f '$escaped' ] && [ -s '$escaped' ]; then echo \"VALID:$path\"; fi"
                }.toTypedArray()

                val res = com.topjohnwu.superuser.Shell.cmd(*script).exec()
                if (res.isSuccess) {
                    for (line in res.out) {
                        if (line.startsWith("VALID:")) {
                            validExternalPaths.add(line.substring(6))
                        }
                    }
                }
            }

            val externalFiles = validExternalPaths.map { File(it) }.filter { isValidImageExtension(it.extension) }
            val combined = (internalFiles + externalFiles).distinctBy { it.absolutePath }

            withContext(Dispatchers.Main) {
                imageFiles.clear()
                imageFiles.addAll(combined)
                if (::adapter.isInitialized) {
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun handleImportedUri(uri: Uri) {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val resolvedPath = UriPathResolver.getRealPathFromUri(this@ImageChooserActivity, uri)

            var exists = false
            var isFile = false
            var length = 0L

            if (resolvedPath.isNotEmpty()) {
                val escapedPath = resolvedPath.replace("'", "'\\''")
                val res = com.topjohnwu.superuser.Shell.cmd(
                    "if [ -e '$escapedPath' ]; then echo EXISTS; fi",
                    "if [ -f '$escapedPath' ]; then echo IS_FILE; fi",
                    "stat -c %s '$escapedPath' 2>/dev/null || echo 0"
                ).exec()

                val out = res.out
                exists = out.contains("EXISTS")
                isFile = out.contains("IS_FILE")
                length = out.lastOrNull()?.toLongOrNull() ?: 0L
                android.util.Log.d("ImageChooserActivity", "Shell check for $escapedPath: EXISTS=$exists, IS_FILE=$isFile, length=$length, out=${out}, err=${res.err}")
            }

            val ext = File(resolvedPath).extension
            val isValidExt = isValidImageExtension(ext)
            val isValid = exists && isFile && length > 0 && isValidExt

            withContext(Dispatchers.Main) {
                setLoading(false)
                if (isValid && resolvedPath.isNotEmpty()) {
                    addExternalImagePath(resolvedPath)
                    loadImages()
                    selectAndReturnFile(resolvedPath)
                } else {
                    val errorMsg = when {
                        !exists -> "Import failed: File does not exist"
                        !isFile -> "Import failed: Selected path is not a regular file"
                        length <= 0 -> "Import failed: Selected file is 0 bytes empty"
                        !isValidExt -> "Import failed: File extension '.$ext' is not a supported disk image format (.img, .iso, .bin, .raw, .vhd, .qcow2)"
                        else -> "Import failed: Invalid disk image file"
                    }
                    MaterialAlertDialogBuilder(this@ImageChooserActivity)
                        .setTitle("Invalid Image File")
                        .setMessage(errorMsg)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun isValidImageExtension(ext: String): Boolean {
        return ext.equals("img", ignoreCase = true) ||
                ext.equals("iso", ignoreCase = true) ||
                ext.equals("bin", ignoreCase = true) ||
                ext.equals("raw", ignoreCase = true) ||
                ext.equals("vhd", ignoreCase = true) ||
                ext.equals("qcow2", ignoreCase = true)
    }

    private fun getExternalImagePaths(): Set<String> {
        val prefs = getSharedPreferences("phonestick", Context.MODE_PRIVATE)
        return prefs.getStringSet("external_images", emptySet()) ?: emptySet()
    }

    private fun addExternalImagePath(path: String) {
        val prefs = getSharedPreferences("phonestick", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("external_images", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(path)
        prefs.edit().putStringSet("external_images", current).apply()
    }

    private fun removeExternalImagePath(path: String) {
        val prefs = getSharedPreferences("phonestick", Context.MODE_PRIVATE)
        val current = prefs.getStringSet("external_images", emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(path)
        prefs.edit().putStringSet("external_images", current).apply()
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission granted or denied
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Image Creation"
            val descriptionText = "Progress notifications for image creation"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showImageCreationNotification(
        fileName: String,
        percent: Int,
        writtenMb: Long,
        totalMb: Long,
        isDone: Boolean = false,
        isError: Boolean = false,
        errorMsg: String = ""
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sd_storage_vector)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isError) {
            builder.setContentTitle("Failed to create image: $fileName")
                .setContentText(errorMsg)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOngoing(false)
                .setProgress(0, 0, false)
        } else if (isDone) {
            builder.setContentTitle("Image Created Successfully")
                .setContentText("$fileName ($totalMb MB) created.")
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOngoing(false)
                .setProgress(0, 0, false)
        } else {
            val contentText = if (writtenMb > 0 || totalMb > 0) "$writtenMb MB / $totalMb MB" else "Creating image..."
            builder.setContentTitle("Creating Image: $fileName")
                .setContentText(contentText)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(100, percent, false)
                .setOngoing(true)
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun showCreateImageDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val dialogBinding = DialogCreateImageBinding.inflate(layoutInflater)

        // Handle shortcut chip selection
        dialogBinding.chipGroupSize.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val sizeMb = when (checkedIds.first()) {
                    R.id.chip512m -> 512
                    R.id.chip1g -> 1024
                    R.id.chip2g -> 2048
                    R.id.chip4g -> 4096
                    R.id.chip8g -> 8192
                    else -> 1024
                }
                if (dialogBinding.etImageSizeMb.text.toString() != sizeMb.toString()) {
                    dialogBinding.etImageSizeMb.setText(sizeMb.toString())
                }
            }
        }

        // Unselect chip shortcut button when custom specified size box is focused
        dialogBinding.etImageSizeMb.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                dialogBinding.chipGroupSize.clearCheck()
            }
        }

        // Unselect chip shortcut button when typing custom specified size
        dialogBinding.etImageSizeMb.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (dialogBinding.etImageSizeMb.hasFocus() && dialogBinding.chipGroupSize.checkedChipId != View.NO_ID) {
                    dialogBinding.chipGroupSize.clearCheck()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.create_image_confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()

        // Override positive button click handler to prevent auto-dismissal while creation is running
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
            val name = dialogBinding.etImageName.text.toString().trim()
            val sizeStr = dialogBinding.etImageSizeMb.text.toString().trim()
            val sizeMb = sizeStr.toLongOrNull() ?: 1024L

            if (name.isEmpty()) {
                dialogBinding.etImageName.error = "Name cannot be empty"
                return@setOnClickListener
            }

            // Extract selected filesystem format
            val fsFormat = when (dialogBinding.chipGroupFs.checkedChipId) {
                R.id.chipFsExt4 -> "EXT4"
                R.id.chipFsExfat -> "EXFAT"
                R.id.chipFsNone -> "NONE"
                else -> "FAT32"
            }
            val useFastAllocation = dialogBinding.switchFastAllocation.isChecked

            // Disable controls during creation
            dialogBinding.etImageName.isEnabled = false
            dialogBinding.etImageSizeMb.isEnabled = false
            dialogBinding.chipGroupSize.isEnabled = false
            dialogBinding.chipGroupFs.isEnabled = false
            dialogBinding.switchFastAllocation.isEnabled = false
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = false
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.isEnabled = false
            dialog.setCancelable(false)

            dialogBinding.layoutProgress.visibility = View.VISIBLE
            dialogBinding.progressIndicatorDialog.isIndeterminate = false
            dialogBinding.progressIndicatorDialog.progress = 0
            dialogBinding.tvProgressStatus.text = "Initializing image creation..."

            val cleanName = if (name.endsWith(".img", ignoreCase = true) || name.endsWith(".iso", ignoreCase = true)) {
                name
            } else {
                "$name.img"
            }

            showImageCreationNotification(cleanName, 0, 0, sizeMb)

            ImageCreator.createBlankImage(
                targetDirectory = filesDir,
                fileName = name,
                sizeInMB = sizeMb,
                filesystemFormat = fsFormat,
                useFastAllocation = useFastAllocation,
                onProgressStatus = { status, percent, writtenMb, totalMb ->
                    dialogBinding.progressIndicatorDialog.progress = percent
                    dialogBinding.tvProgressStatus.text = status
                    showImageCreationNotification(cleanName, percent, writtenMb, totalMb)
                },
                onComplete = { success, msg, file ->
                    dialog.dismiss()
                    if (success && file != null) {
                        showImageCreationNotification(cleanName, 100, sizeMb, sizeMb, isDone = true)
                        currentlySelectedPath = file.absolutePath
                        loadImages()
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                    } else {
                        showImageCreationNotification(cleanName, 0, 0, sizeMb, isError = true, errorMsg = msg)
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Image Creation Error")
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            )
        }
    }

    private var isFormatSelectionMode = false

    private fun showMultiFormatDialog() {
        if (imageFiles.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("No Image Files")
                .setMessage("There are no image files available to format.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        enterFormatSelectionMode()
    }

    private fun enterFormatSelectionMode(initialSelectedFile: File? = null) {
        TransitionManager.beginDelayedTransition(binding.root)
        isFormatSelectionMode = true
        adapter.isSelectionMode = true
        if (initialSelectedFile != null) {
            adapter.checkedFiles.add(initialSelectedFile)
            adapter.notifyDataSetChanged()
        }
        binding.speedDialContainer.visibility = View.GONE

        val initialCount = adapter.checkedFiles.size
        binding.toolbar.title = if (initialCount > 0) "Selected $initialCount Image(s)" else "Select Image(s)"
        binding.toolbar.setNavigationIcon(R.drawable.ic_close_vector)
        binding.toolbar.setNavigationOnClickListener {
            exitFormatSelectionMode()
        }

        binding.toolbar.menu.clear()
        binding.toolbar.inflateMenu(R.menu.menu_format)
        binding.toolbar.setOnMenuItemClickListener { item ->
            val selected = adapter.checkedFiles.toList()
            when (item.itemId) {
                R.id.action_confirm_format -> {
                    if (selected.isEmpty()) {
                        Snackbar.make(binding.root, "Select at least 1 image to format", Snackbar.LENGTH_SHORT).show()
                    } else {
                        showFormatSelectionDialog(selected)
                    }
                    true
                }
                R.id.action_confirm_delete -> {
                    if (selected.isEmpty()) {
                        Snackbar.make(binding.root, "Select at least 1 image to delete", Snackbar.LENGTH_SHORT).show()
                    } else {
                        confirmBatchDelete(selected)
                    }
                    true
                }
                else -> false
            }
        }

        adapter.onSelectionCountChanged = { count ->
            binding.toolbar.title = if (count > 0) "Selected $count Image(s)" else "Select Image(s)"
        }
    }

    private fun confirmBatchDelete(selectedFiles: List<File>) {
        val count = selectedFiles.size
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete $count Image(s)")
            .setMessage("Are you sure you want to delete $count selected image file(s)? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                executeBatchDelete(selectedFiles)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun executeBatchDelete(selectedFiles: List<File>) {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val mountStatus = UsbGadgetController.getMountStatus()
            val mountedPath = if (mountStatus.isMounted) mountStatus.currentFile else ""

            for (file in selectedFiles) {
                if (file.absolutePath == mountedPath || file.absolutePath == currentlySelectedPath) {
                    UsbGadgetController.unmountImage(this@ImageChooserActivity)
                    if (currentlySelectedPath == file.absolutePath) {
                        currentlySelectedPath = ""
                    }
                }
                removeExternalImagePath(file.absolutePath)
                if (file.exists()) {
                    file.delete()
                }
            }

            withContext(Dispatchers.Main) {
                setLoading(false)
                exitFormatSelectionMode()
                loadImages()
                Snackbar.make(binding.root, "Deleted ${selectedFiles.size} image file(s)", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun exitFormatSelectionMode() {
        TransitionManager.beginDelayedTransition(binding.root)
        isFormatSelectionMode = false
        adapter.isSelectionMode = false
        binding.speedDialContainer.visibility = View.VISIBLE

        binding.toolbar.title = getString(R.string.title_activity_image_chooser)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back_vector)
        binding.toolbar.setNavigationOnClickListener { finishWithResult() }
        binding.toolbar.menu.clear()
    }

    private fun showFormatSelectionDialog(selectedFiles: List<File>) {
        val formats = arrayOf("FAT32", "ext4", "exFAT")
        var selectedFormatIndex = 0

        MaterialAlertDialogBuilder(this)
            .setTitle("Choose Filesystem Format")
            .setSingleChoiceItems(formats, 0) { _, which ->
                selectedFormatIndex = which
            }
            .setPositiveButton("Format (${selectedFiles.size} file(s))") { _, _ ->
                val format = formats[selectedFormatIndex]
                executeBatchFormatting(selectedFiles, format)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun executeBatchFormatting(selectedFiles: List<File>, format: String) {
        setLoading(true)
        val errors = mutableListOf<String>()
        var progressSnackbar: Snackbar? = null

        lifecycleScope.launch(Dispatchers.IO) {
            for ((index, file) in selectedFiles.withIndex()) {
                withContext(Dispatchers.Main) {
                    progressSnackbar?.dismiss()
                    progressSnackbar = Snackbar.make(
                        binding.root,
                        "Formatting ${index + 1}/${selectedFiles.size}: ${file.name}...",
                        Snackbar.LENGTH_INDEFINITE
                    )
                    progressSnackbar?.show()
                }

                val (success, message) = suspendFormatImage(file, format)
                if (!success) {
                    errors.add("${file.name}: $message")
                }
            }

            withContext(Dispatchers.Main) {
                progressSnackbar?.dismiss()
                setLoading(false)
                exitFormatSelectionMode()
                loadImages()

                if (errors.isEmpty()) {
                    MaterialAlertDialogBuilder(this@ImageChooserActivity)
                        .setTitle("Formatting Complete")
                        .setMessage("Successfully formatted ${selectedFiles.size} file(s) as $format.")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    MaterialAlertDialogBuilder(this@ImageChooserActivity)
                        .setTitle("Format CLI Errors")
                        .setMessage("Completed with errors:\n\n" + errors.joinToString("\n\n"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private suspend fun suspendFormatImage(file: File, format: String): Pair<Boolean, String> =
        suspendCancellableCoroutine { continuation ->
            ImageCreator.formatExistingImage(file, format, onProgressStatus = {}) { success, msg ->
                if (continuation.isActive) {
                    continuation.resume(Pair(success, msg))
                }
            }
        }

    private fun setLoading(isLoading: Boolean) {
        binding.progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun selectAndReturnFile(path: String, autoMount: Boolean = false) {
        val result = Intent().apply {
            putExtra("path", path)
            putExtra("auto_mount", autoMount)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun finishWithResult() {
        val result = Intent().apply {
            putExtra("path", currentlySelectedPath)
            putExtra("auto_mount", false)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

}
