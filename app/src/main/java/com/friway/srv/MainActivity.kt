package com.friway.srv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer
import com.friway.srv.databinding.ActivityMainBinding
import com.friway.srv.databinding.RootRequiredBannerBinding
import com.friway.srv.utils.FridaUtils
import com.friway.srv.viewmodel.FridaViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: FridaViewModel by viewModels()
    private var rootBannerAdded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initializeApp()
    }

    private fun initializeApp() {
        setupUI()
        setupObservers()
        setupAboutSection()
        viewModel.checkStatus()
        viewModel.loadAvailableReleases()
    }

    private fun setupAboutSection() {
        val aboutCardCollapsed = findViewById<View>(R.id.aboutCardView)
        aboutCardCollapsed.setOnClickListener {
            showExpandedAboutDialog()
        }
    }

    private fun showExpandedAboutDialog() {
        val dialog = AlertDialog.Builder(this, R.style.Theme_Fridalauncher_Dialog).create()

        val expandedView = layoutInflater.inflate(R.layout.about_footer_expanded, null)

        expandedView.findViewById<View>(R.id.backButton).setOnClickListener {
            dialog.dismiss()
        }

        expandedView.findViewById<View>(R.id.issuesLinkTextView).setOnClickListener {
            openUrl("https://github.com/thecybersandeep/Frida-Launcher/issues")
        }

        expandedView.findViewById<View>(R.id.linkedinLinkTextView).setOnClickListener {
            openUrl("https://www.linkedin.com/in/sandeepwawdane")
        }

        expandedView.findViewById<View>(R.id.twitterLinkTextView).setOnClickListener {
            openUrl("https://x.com/thecybersandeep")
        }

        dialog.setView(expandedView)

        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun openUrl(url: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        intent.data = android.net.Uri.parse(url)
        startActivity(intent)
    }

    private fun showRootRequiredBanner() {
        if (rootBannerAdded) return

        val bannerBinding = RootRequiredBannerBinding.inflate(layoutInflater)

        val container = findViewById<ViewGroup>(R.id.main)
        container.addView(bannerBinding.root, 1)

        AlertDialog.Builder(this)
            .setTitle("Root Access Required")
            .setMessage("This app requires root access to function properly. Without root access, you won't be able to install or run Frida server.\n\nPlease root your device or use a device with root access.")
            .setPositiveButton("OK", null)
            .show()

        rootBannerAdded = true
    }

    private fun setupUI() {
        binding.statusMessageTextView.movementMethod = ScrollingMovementMethod()
        binding.statusMessageTextView.setTextIsSelectable(true)

        val deviceArch = FridaUtils.getDeviceArchitecture()
        binding.deviceInfoTextView.text = "Device: ${Build.MODEL} (${deviceArch})"
        binding.architectureTextView.visibility = View.GONE

        viewModel.setSelectedArchitecture(deviceArch)

        binding.installButton.setOnClickListener {
            viewModel.downloadAndInstallFridaServer(this)
        }

        binding.startButton.setOnClickListener {
            if (viewModel.isServerRunning.value == true) {
                Toast.makeText(this, "Server is already running. Stop it first.", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.startFridaServer()
            }
        }

        binding.customRunButton.setOnClickListener {
            if (viewModel.isServerRunning.value == true) {
                Toast.makeText(this, "Server is already running. Stop it first.", Toast.LENGTH_SHORT).show()
            } else {
                showCustomFlagsDialog()
            }
        }

        binding.stopButton.setOnClickListener {
            if (viewModel.isServerRunning.value != true) {
                showServerNotRunningDialog()
            } else {
                viewModel.stopFridaServer()
            }
        }

        binding.uninstallButton.setOnClickListener {
            viewModel.uninstallFridaServer()
        }

        binding.refreshButton.setOnClickListener {
            viewModel.checkStatus()
            viewModel.loadAvailableReleases()
        }

        binding.copyLogsButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Frida Launcher Logs", binding.statusMessageTextView.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.clearLogsButton.setOnClickListener {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val clearMessage = "[$timestamp] Logs cleared"
            binding.statusMessageTextView.text = clearMessage
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        binding.statusMessageTextView.text = "[$timestamp] Frida Launcher initialized"
    }

    private fun showServerNotRunningDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Server Not Running")
            .setMessage("Frida server is not currently running.\n\nPlease start the server first before trying to stop it.")
            .setPositiveButton("Start Server") { _, _ ->
                viewModel.startFridaServer()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomFlagsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_flags, null)

        val editText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.flagsEditText)
        val helpButton = dialogView.findViewById<android.widget.ImageView>(R.id.helpButton)

        viewModel.lastCustomFlags.value?.let { lastFlags ->
            editText.setText(lastFlags)
            editText.setSelection(lastFlags.length)
        }

        helpButton.setOnClickListener {
            showFlagsHelpDialog()
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Run with Custom Flags")
            .setView(dialogView)
            .setPositiveButton("Start", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val customFlags = editText.text.toString().trim()
            viewModel.startFridaServerWithCustomFlags(customFlags)
            dialog.dismiss()
        }

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            dialog.dismiss()
        }
    }

    private fun showFlagsHelpDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_flags_help, null)
        val flagsContainer = dialogView.findViewById<LinearLayout>(R.id.flagsContainer)

        val flagItems = listOf(
            Pair("-l, --listen=ADDRESS", "Listen on ADDRESS (e.g., 0.0.0.0:27042)"),
            Pair("--certificate=CERT", "Enable TLS using CERTIFICATE"),
            Pair("--origin=ORIGIN", "Only accept requests with Origin header"),
            Pair("--token=TOKEN", "Require authentication using TOKEN"),
            Pair("--asset-root=ROOT", "Serve static files inside ROOT"),
            Pair("-d, --directory=DIR", "Store binaries in DIRECTORY"),
            Pair("-D, --daemonize", "Detach and become a daemon"),
            Pair("--policy-softener=TYPE", "Select policy softener"),
            Pair("-P, --disable-preload", "Disable preload optimization"),
            Pair("-C, --ignore-crashes", "Disable native crash reporter")
        )

        flagItems.forEach { (flag, description) ->
            val flagItemView = layoutInflater.inflate(R.layout.item_flag, flagsContainer, false)

            val flagNameText = flagItemView.findViewById<TextView>(R.id.flagNameText)
            val flagDescriptionText = flagItemView.findViewById<TextView>(R.id.flagDescriptionText)

            flagNameText.text = flag
            flagDescriptionText.text = description

            flagsContainer.addView(flagItemView)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Available Flags")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun setupObservers() {
        viewModel.isLoading.observe(this, Observer { isLoading ->
            binding.loadingOverlay.visibility = if (isLoading) View.VISIBLE else View.GONE

            binding.installButton.isEnabled = !isLoading
            binding.refreshButton.isEnabled = !isLoading
            binding.versionSpinner.isEnabled = !isLoading
            binding.copyLogsButton.isEnabled = !isLoading
            binding.clearLogsButton.isEnabled = !isLoading
            binding.uninstallButton.isEnabled = !isLoading

            if (!isLoading) {
                val isInstalled = viewModel.isServerInstalled.value == true
                val isRunning = viewModel.isServerRunning.value == true

                binding.startButton.isEnabled = isInstalled && !isRunning
                binding.customRunButton.isEnabled = isInstalled && !isRunning
                binding.stopButton.isEnabled = isInstalled
            } else {
                binding.startButton.isEnabled = false
                binding.customRunButton.isEnabled = false
                binding.stopButton.isEnabled = false
            }
        })

        viewModel.statusMessage.observe(this, Observer { message ->
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

            binding.statusMessageTextView.append("\n[$timestamp] $message")

            binding.statusMessageTextView.post {
                try {
                    val parent = binding.statusMessageTextView.parent
                    if (parent is ScrollView) {
                        parent.fullScroll(ScrollView.FOCUS_DOWN)
                    }
                } catch (e: Exception) { }
            }
        })

        viewModel.rootAccessStatus.observe(this, Observer { status ->
            when (status) {
                FridaViewModel.RootStatus.NOT_AVAILABLE -> {
                    showRootRequiredBanner()

                    binding.installButton.isEnabled = false
                    binding.startButton.isEnabled = false
                    binding.stopButton.isEnabled = false
                    binding.uninstallButton.isEnabled = false
                }
                FridaViewModel.RootStatus.AVAILABLE -> { }
                FridaViewModel.RootStatus.NON_ROOT_MODE -> { }
                else -> { }
            }
        })

        viewModel.isServerInstalled.observe(this, Observer { isInstalled ->
            val statusText = "Installed: "
            val statusValue = if (isInstalled) "Yes" else "No"

            val spannable = android.text.SpannableString(statusText + statusValue)
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(
                    ContextCompat.getColor(this, if (isInstalled) R.color.green_primary else R.color.red_primary)
                ),
                statusText.length, spannable.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                statusText.length, spannable.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            binding.installedStatusTextView.text = spannable

            binding.serverControlsLayout.visibility = if (isInstalled) View.VISIBLE else View.GONE

            binding.startButton.isEnabled = isInstalled && viewModel.isServerRunning.value != true
            binding.customRunButton.isEnabled = isInstalled && viewModel.isServerRunning.value != true
            binding.stopButton.isEnabled = isInstalled
            binding.uninstallButton.isEnabled = isInstalled
        })

        viewModel.isServerRunning.observe(this, Observer { isRunning ->
            val statusText = "Running: "
            val statusValue = if (isRunning) "Yes" else "No"

            val spannable = android.text.SpannableString(statusText + statusValue)
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(
                    ContextCompat.getColor(this, if (isRunning) R.color.green_primary else R.color.red_primary)
                ),
                statusText.length, spannable.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                statusText.length, spannable.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            binding.runningStatusTextView.text = spannable

            binding.startButton.isEnabled = !isRunning && viewModel.isServerInstalled.value == true
            binding.customRunButton.isEnabled = !isRunning && viewModel.isServerInstalled.value == true
        })

        viewModel.installedVersion.observe(this, Observer { version ->
            val statusText = "Version: "
            val versionText = version

            val spannable = android.text.SpannableString(statusText + versionText)

            if (versionText.isNotEmpty() && versionText != "Not installed") {
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(
                        ContextCompat.getColor(this, R.color.blue_primary)
                    ),
                    statusText.length, spannable.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    statusText.length, spannable.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            binding.versionTextView.text = spannable
        })

        viewModel.availableReleases.observe(this, Observer { releases ->
            if (releases.isNotEmpty()) {
                val versionItems = releases.map {
                    "Version: ${it.version}\nDate: ${it.releaseDate}"
                }.toMutableList()
                versionItems.add("⚙️ Custom Version...\nEnter any Frida version")

                val versionAdapter = ArrayAdapter(this, R.layout.spinner_item, versionItems.toTypedArray())
                versionAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
                binding.versionSpinner.adapter = versionAdapter

                binding.versionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (position == versionItems.size - 1) {
                            showCustomVersionDialog()
                        } else {
                            val selectedVersion = releases[position].version
                            viewModel.setSelectedVersion(selectedVersion)
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) { }
                }
            }
        })
    }

    private fun showCustomVersionDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_version, null)

        val editText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.versionEditText)
        val hintText = dialogView.findViewById<TextView>(R.id.versionHintText)

        hintText?.text = "Enter version (e.g., 16.7.19, 16.5.9)"

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Enter Custom Frida Version")
            .setView(dialogView)
            .setPositiveButton("Set Version", null)
            .setNegativeButton("Cancel") { d, _ ->
                binding.versionSpinner.setSelection(0)
                d.dismiss()
            }
            .create()

        dialog.show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val customVersion = editText.text.toString().trim()
            if (customVersion.isEmpty()) {
                Toast.makeText(this, "Please enter a version number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!FridaUtils.isValidVersionFormat(customVersion)) {
                Toast.makeText(this, "Invalid version format. Use format like: 16.5.9", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.setCustomVersion(customVersion)
            Toast.makeText(this, "Custom version set: $customVersion", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
    }
}
