package it.smartplugmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class FineCycleActivity : AppCompatActivity() {

    private val receiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (
                    intent?.action ==
                    MonitorService.ACTION_STOP_ALERT
                ) {
                    finish()
                }

                if (
                    intent?.action ==
                    MonitorService.ACTION_NEW_CYCLE
                ) {
                    finish()
                }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val layout =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    48,
                    48,
                    48,
                    48
                )

                setBackgroundColor(
                    Color.WHITE
                )
            }

        val title =
            TextView(this).apply {

                text =
                    "CICLO TERMINATO"

                textSize = 30f

                setTextColor(
                    Color.BLACK
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    0,
                    0,
                    0,
                    40
                )
            }

        val okButton =
            Button(this).apply {

                text =
                    "OK"

                textSize = 20f

                setOnClickListener {

                    stopAlert()

                    finish()
                }
            }

        layout.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        layout.addView(
            okButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()

        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(
                    MonitorService.ACTION_STOP_ALERT
                )
                addAction(
                    MonitorService.ACTION_NEW_CYCLE
                )
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }

        super.onPause()
    }

    private fun stopAlert() {

        startService(
            Intent(
                this,
                MonitorService::class.java
            ).apply {
                action =
                    MonitorService.ACTION_STOP_ALERT
            }
        )
    }
}
