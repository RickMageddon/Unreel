package com.unreel.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.open_app_info).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }

        bindSwitch(R.id.hide_tab, Prefs.HIDE_TAB)
        bindSwitch(R.id.block_viewer, Prefs.BLOCK_VIEWER)
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.status).setText(
            if (isServiceEnabled()) R.string.status_on else R.string.status_off
        )
    }

    private fun bindSwitch(id: Int, key: String) {
        val switch = findViewById<Switch>(id)
        switch.isChecked = Prefs.get(this, key)
        switch.setOnCheckedChangeListener { _, checked -> Prefs.set(this, key, checked) }
    }

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
