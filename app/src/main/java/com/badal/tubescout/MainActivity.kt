package com.badal.tubescout

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private data class Vid(
        val title: String,
        val channel: String,
        val views: Long,
        val tags: List<String>
    )

    private fun httpGet(link: String): String {
        val conn = URL(link).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("HTTP $code: $body")
        return body
    }

    private fun research(key: String, niche: String): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.add(Calendar.DAY_OF_YEAR, -90)
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val after = URLEncoder.encode(fmt.format(cal.time), "UTF-8")
        val q = URLEncoder.encode(niche, "UTF-8")

        val searchUrl = "https://www.googleapis.com/youtube/v3/search" +
            "?part=snippet&type=video&order=viewCount&maxResults=25" +
            "&regionCode=IN&publishedAfter=$after&q=$q&key=$key"
        val items = JSONObject(httpGet(searchUrl)).getJSONArray("items")
        if (items.length() == 0) return "No videos found."

        val ids = ArrayList<String>()
        for (i in 0 until items.length()) {
            ids.add(items.getJSONObject(i).getJSONObject("id").getString("videoId"))
        }

        val videosUrl = "https://www.googleapis.com/youtube/v3/videos" +
            "?part=snippet,statistics&id=${ids.joinToString(",")}&key=$key"
        val videos = JSONObject(httpGet(videosUrl)).getJSONArray("items")

        val list = ArrayList<Vid>()
        for (i in 0 until videos.length()) {
            val v = videos.getJSONObject(i)
            val sn = v.getJSONObject("snippet")
            val st = v.getJSONObject("statistics")
            val tags = ArrayList<String>()
            val ta = sn.optJSONArray("tags")
            if (ta != null) {
                for (j in 0 until ta.length()) tags.add(ta.getString(j).lowercase())
            }
            list.add(
                Vid(
                    sn.getString("title"),
                    sn.getString("channelTitle"),
                    st.optString("viewCount", "0").toLong(),
                    tags
                )
            )
        }
        list.sortByDescending { it.views }

        val sb = StringBuilder()
        sb.append("Top videos (last 90 days)\n\n")
        list.take(10).forEachIndexed { i, v ->
            sb.append("${i + 1}. ${v.title}\n   ${v.views} views | ${v.channel}\n\n")
        }

        val counts = HashMap<String, Int>()
        for (v in list) {
            for (t in v.tags) counts[t] = (counts[t] ?: 0) + 1
        }
        val top = counts.entries.sortedByDescending { it.value }.take(15)
        sb.append("Keywords\n")
        sb.append(top.joinToString(", ") { it.key })
        sb.append("\n\nHashtags\n")
        sb.append(top.take(10).joinToString(" ") { "#" + it.key.replace(" ", "") })
        return sb.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("tubescout", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val title = TextView(this).apply {
            text = "TubeScout"
            textSize = 26f
        }

        val keyInput = EditText(this).apply {
            hint = "YouTube API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("api_key", ""))
        }

        val channelInput = EditText(this).apply {
            hint = "Channel name"
            setText("Unfiltered Badal")
        }

        val nicheInput = EditText(this).apply {
            hint = "Niche"
            setText("store incharge construction site vlog")
        }

        val runButton = Button(this).apply {
            text = "Research"
        }

        val output = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, 0)
        }

        runButton.setOnClickListener {
            val key = keyInput.text.toString().trim()
            val niche = nicheInput.text.toString().trim()
            if (key.isEmpty() || niche.isEmpty()) {
                output.text = "API key and niche required."
                return@setOnClickListener
            }
            prefs.edit().putString("api_key", key).apply()
            output.text = "Searching..."
            runButton.isEnabled = false
            Thread {
                val result = try {
                    research(key, niche)
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                runOnUiThread {
                    output.text = result
                    runButton.isEnabled = true
                }
            }.start()
        }

        root.addView(title)
        root.addView(keyInput)
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
