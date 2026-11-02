package com.badal.tubescout

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val title = TextView(this).apply {
            text = "TubeScout"
            textSize = 26f
        }

        val channelInput = EditText(this).apply {
            hint = "Channel name"
            setText("Unfiltered Badal")
        }

        val nicheInput = EditText(this).apply {
            hint = "Niche"
            setText("Store Incharge life, construction site")
        }

        val runButton = Button(this).apply {
            text = "Research"
        }

        val output = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad, 0, 0)
        }

        runButton.setOnClickListener {
            val channel = channelInput.text.toString().trim()
            val niche = nicheInput.text.toString().trim()
            output.text = "Channel: $channel\nNiche: $niche\n\nResearch engine coming in Step 2."
        }

        root.addView(title)
        root.addView(channelInput)
        root.addView(nicheInput)
        root.addView(runButton)

        val scroll = ScrollView(this)
        scroll.addView(
            output,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(scroll)

        setContentView(root)
    }
}
