package com.unreel.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    /** Setup steps in display order: card, title view, title text. */
    private val steps = listOf(
        Triple(R.id.step_try, R.id.step_try_title, R.string.step_try_title),
        Triple(R.id.step_allow, R.id.step_allow_title, R.string.step_allow_title),
        Triple(R.id.step_enable, R.id.step_enable_title, R.string.step_enable_title),
        Triple(R.id.step_battery, R.id.step_battery_title, R.string.step_battery_title),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.open_accessibility_try).setOnClickListener { openServiceSettings() }
        findViewById<View>(R.id.open_accessibility_enable).setOnClickListener { openServiceSettings() }
        findViewById<View>(R.id.open_app_info).setOnClickListener {
            start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<View>(R.id.allow_battery).setOnClickListener {
            start(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }

        bindSwitch(R.id.hide_tab, Prefs.HIDE_TAB)
        bindSwitch(R.id.block_viewer, Prefs.BLOCK_VIEWER)
        bindSwitch(R.id.block_tiktok, Prefs.BLOCK_TIKTOK)
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
    }

    private fun refreshSetup() {
        val enabled = isServiceEnabled()
        val batteryOk = isIgnoringBatteryOptimizations()
        // Restricted settings only exist on Android 13+, and are clearly handled once the service is on.
        val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !enabled

        findViewById<TextView>(R.id.status).setText(if (enabled) R.string.status_on else R.string.status_off)
        findViewById<TextView>(R.id.setup_header).setText(
            if (enabled && batteryOk) R.string.setup_done_header else R.string.setup_header
        )

        show(R.id.step_try, restricted)
        show(R.id.step_allow, restricted)
        show(R.id.step_enable, !enabled)
        show(R.id.step_battery_body, !batteryOk)
        show(R.id.allow_battery, !batteryOk)

        var number = 0
        for ((card, title, text) in steps) {
            if (findViewById<View>(card).visibility != View.VISIBLE) continue
            number++
            findViewById<TextView>(title).text =
                if (card == R.id.step_battery && batteryOk) getString(R.string.step_done, getString(text))
                else "$number. ${getString(text)}"
        }
    }

    private fun openServiceSettings() {
        start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** Starts the first intent some app on this phone can handle. */
    private fun start(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next one.
            } catch (e: SecurityException) {
                // Try the next one.
            }
        }
    }

    private fun show(id: Int, visible: Boolean) {
        findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun bindSwitch(id: Int, key: String) {
        val switch = findViewById<Switch>(id)
        switch.isChecked = Prefs.get(this, key)
        switch.setOnCheckedChangeListener { _, checked -> Prefs.set(this, key, checked) }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(this, ReelsBlockerService::class.java)
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == component
        }
    }
}
