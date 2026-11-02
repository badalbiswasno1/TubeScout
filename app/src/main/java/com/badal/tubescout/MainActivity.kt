package com.badal.tubescout

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
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
        val id: String,
        val title: String,
        val channel: String,
        val views: Long,
        val tags: List<String>
    )

    private data class Result(
        val top: List<Vid>,
        val keywords: String,
        val hashtags: String
    )

    private var last: Result? = null

    private val claudeModel = "claude-sonnet-5-5"

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

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

    private fun research(key: String, niche: String): Result {
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
        if (items.length() == 0) throw RuntimeException("No videos found.")

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
                    v.getString("id"),
                    sn.getString("title"),
                    sn.getString("channelTitle"),
                    st.optString("viewCount", "0").toLong(),
                    tags
                )
            )
        }
        list.sortByDescending { it.views }

        val counts = HashMap<String, Int>()
        for (v in list) {
            for (t in v.tags) counts[t] = (counts[t] ?: 0) + 1
        }
        val top = counts.entries.sortedByDescending { it.value }.take(15)
        val keywords = top.joinToString(", ") { it.key }
        val hashtags = top.take(10).joinToString(" ") { "#" + it.key.replace(" ", "") }
        return Result(list.take(10), keywords, hashtags)
    }

    private fun buildPrompt(channel: String, niche: String, res: Result): String {
        val sb = StringBuilder()
        sb.append("You are a YouTube growth strategist.\n")
        sb.append("Channel: $channel\n")
        sb.append("Niche: $niche\n")
        sb.append("Creator: a construction site Store Incharge in India making unfiltered, real-life Bengali vlogs. ")
        sb.append("Face on camera, 5 to 8 minute videos plus Shorts. ")
        sb.append("Never suggest topics that expose company names, rates, or identifiable people.\n\n")
        sb.append("Trending videos in this niche (last 90 days):\n")
        res.top.forEachIndexed { i, v ->
            sb.append("${i + 1}. ${v.title} (${v.views} views)\n")
        }
        sb.append("\nFrequent tags: ${res.keywords}\n\n")
        sb.append("Task: suggest the 10 best video topics for this channel. ")
        sb.append("For each give: topic, a Bengali title, a thumbnail idea ")
        sb.append("(short text on thumbnail, main color, facial expression), ")
        sb.append("5 keywords, 5 hashtags. Write in Bengali script with English words where natural. ")
        sb.append("Keep it concise. Plain text only, no markdown symbols.")
        return sb.toString()
    }

    private fun callClaude(apiKey: String, prompt: String): String {
        val body = JSONObject()
            .put("model", claudeModel)
            .put("max_tokens", 3000)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                )
            )
            .toString()

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("HTTP $code: $text")

        val arr = JSONObject(text).getJSONArray("content")
        val out = StringBuilder()
        for (i in 0 until arr.length()) {
            val block = arr.getJSONObject(i)
            if (block.optString("type") == "text") out.append(block.getString("text"))
        }
        return out.toString()
    }

    private fun loadThumb(view: ImageView, videoId: String) {
        Thread {
            try {
                val url = URL("https://i.ytimg.com/vi/$videoId/mqdefault.jpg")
                val bmp = BitmapFactory.decodeStream(url.openStream())
                runOnUiThread { view.setImageBitmap(bmp) }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun videoRow(index: Int, v: Vid): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${v.id}"))
                )
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF333333.toInt())
        }
        row.addView(img, LinearLayout.LayoutParams(dp(150), dp(84)))
        val info = TextView(this).apply {
            textSize = 14f
            text = "${index + 1}. ${v.title}\n${v.views} views\n${v.channel}"
            setPadding(dp(10), 0, 0, 0)
        }
        row.addView(
            info,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        loadThumb(img, v.id)
        return row
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("tubescout", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
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

        val claudeKeyInput = EditText(this).apply {
            hint = "Claude API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("claude_key", ""))
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

        val aiButton = Button(this).apply {
            text = "AI: top 10 topics"
            isEnabled = false
        }

        val status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, 0)
        }

        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val extras = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }

        val aiOut = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, 0)
        }

        runButton.setOnClickListener {
            val key = keyInput.text.toString().trim()
            val niche = nicheInput.text.toString().trim()
            if (key.isEmpty() || niche.isEmpty()) {
                status.text = "API key and niche required."
                return@setOnClickListener
            }
            prefs.edit().putString("api_key", key).apply()
            status.text = "Searching..."
            listBox.removeAllViews()
            extras.text = ""
            aiOut.text = ""
            aiButton.isEnabled = false
            runButton.isEnabled = false
            Thread {
                try {
                    val res = research(key, niche)
                    last = res
                    runOnUiThread {
                        status.text = "Top videos (last 90 days)"
                        res.top.forEachIndexed { i, v -> listBox.addView(videoRow(i, v)) }
                        extras.text = "Keywords\n${res.keywords}\n\nHashtags\n${res.hashtags}"
                        runButton.isEnabled = true
                        aiButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = "Error: ${e.message}"
                        runButton.isEnabled = true
                    }
                }
            }.start()
        }

        aiButton.setOnClickListener {
            val res = last
            val ck = claudeKeyInput.text.toString().trim()
            if (res == null) {
                aiOut.text = "Run Research first."
                return@setOnClickListener
            }
            if (ck.isEmpty()) {
                aiOut.text = "Claude API key required."
                return@setOnClickListener
            }
            prefs.edit().putString("claude_key", ck).apply()
            aiOut.text = "Thinking..."
            aiButton.isEnabled = false
            val prompt = buildPrompt(
                channelInput.text.toString().trim(),
                nicheInput.text.toString().trim(),
                res
            )
            Thread {
                val out = try {
                    callClaude(ck, prompt)
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                runOnUiThread {
                    aiOut.text = out
                    aiButton.isEnabled = true
                }
            }.start()
        }

        root.addView(title)
        root.addView(keyInput)
        root.addView(claudeKeyInput)
        root.addView(channelInput)
        root.addView(nicheInput)
        root.addView(runButton)
        root.addView(aiButton)
        root.addView(status)
        root.addView(listBox)
        root.addView(extras)
        root.addView(aiOut)

        val scroll = ScrollView(this)
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(scroll)
    }
}
