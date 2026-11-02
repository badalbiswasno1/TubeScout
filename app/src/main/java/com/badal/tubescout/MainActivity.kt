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
        val report: String
    )

    private val stopWords = setOf(
        "the", "and", "for", "with", "you", "this", "that", "from", "how", "what",
        "your", "are", "was", "all", "can", "not", "but", "has", "have", "our",
        "full", "new", "official", "video", "videos"
    )

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun fmt(n: Long): String = String.format(Locale.US, "%,d", n)

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

    private fun suggest(q: String): List<String> {
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=" +
            URLEncoder.encode(q, "UTF-8")
        val arr = JSONArray(httpGet(url)).getJSONArray(1)
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) out.add(arr.getString(i))
        return out
    }

    private fun collectSuggestions(niche: String): List<String> {
        val suffixes = listOf("", " how", " why", " day in life", " problem", " bangla", " hindi")
        val seen = LinkedHashSet<String>()
        for (s in suffixes) {
            try {
                seen.addAll(suggest(niche + s))
            } catch (e: Exception) {
            }
        }
        return seen.toList()
    }

    private fun buildReport(niche: String, list: List<Vid>, sugg: List<String>): String {
        val views = list.map { it.views }.sorted()
        val avg = views.average().toLong()
        val median = views[views.size / 2]
        val best = views.last()
        val avgLen = list.map { it.title.length }.average().toInt()
        val withNumber = list.count { v -> v.title.any { it.isDigit() } }
        val withQuestion = list.count { it.title.contains("?") }

        val nicheWords = niche.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).toSet()
        val wordCounts = HashMap<String, Int>()
        for (v in list) {
            val words = v.title.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            for (w in words.toSet()) {
                if (w.length >= 3 && w !in stopWords && w !in nicheWords) {
                    wordCounts[w] = (wordCounts[w] ?: 0) + 1
                }
            }
        }
        val topWords = wordCounts.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .take(12)

        val tagCounts = HashMap<String, Int>()
        for (v in list) {
            for (t in v.tags) tagCounts[t] = (tagCounts[t] ?: 0) + 1
        }
        val topTags = tagCounts.entries.sortedByDescending { it.value }.take(15)

        val sb = StringBuilder()
        sb.append("REPORT\n")
        sb.append("Videos analyzed: ${list.size} (last 90 days)\n")
        sb.append("Views: avg ${fmt(avg)} | median ${fmt(median)} | best ${fmt(best)}\n")
        sb.append("Avg title length: $avgLen chars\n")
        sb.append("Titles with number: $withNumber/${list.size} | with question: $withQuestion/${list.size}\n")

        sb.append("\nPOPULAR TITLE WORDS\n")
        if (topWords.isEmpty()) sb.append("-\n")
        else sb.append(topWords.joinToString(", ") { "${it.key} (${it.value})" }).append("\n")

        sb.append("\nTOPIC IDEAS (what people search)\n")
        if (sugg.isEmpty()) sb.append("-\n")
        else sugg.take(20).forEachIndexed { i, s -> sb.append("${i + 1}. $s\n") }

        sb.append("\nKEYWORDS\n")
        sb.append(topTags.joinToString(", ") { it.key }).append("\n")

        sb.append("\nHASHTAGS\n")
        sb.append(topTags.take(10).joinToString(" ") { "#" + it.key.replace(" ", "") })
        return sb.toString()
    }

    private fun research(key: String, niche: String): Result {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.add(Calendar.DAY_OF_YEAR, -90)
        val fmtDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmtDate.timeZone = TimeZone.getTimeZone("UTC")
        val after = URLEncoder.encode(fmtDate.format(cal.time), "UTF-8")
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

        val sugg = collectSuggestions(niche)
        return Result(list.take(10), buildReport(niche, list, sugg))
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
            text = "${index + 1}. ${v.title}\n${fmt(v.views)} views\n${v.channel}"
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

        val nicheInput = EditText(this).apply {
            hint = "Niche"
            setText(prefs.getString("niche", "store incharge construction site vlog"))
        }

        val runButton = Button(this).apply {
            text = "Research + Report"
        }

        val status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, 0)
        }

        val reportView = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, dp(12))
        }

        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        runButton.setOnClickListener {
            val key = keyInput.text.toString().trim()
            val niche = nicheInput.text.toString().trim()
            if (key.isEmpty() || niche.isEmpty()) {
                status.text = "API key and niche required."
                return@setOnClickListener
            }
            prefs.edit().putString("api_key", key).putString("niche", niche).apply()
            status.text = "Searching..."
            reportView.text = ""
            listBox.removeAllViews()
            runButton.isEnabled = false
            Thread {
                try {
                    val res = research(key, niche)
                    runOnUiThread {
                        status.text = "Done"
                        reportView.text = res.report
                        res.top.forEachIndexed { i, v -> listBox.addView(videoRow(i, v)) }
                        runButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = "Error: ${e.message}"
                        runButton.isEnabled = true
                    }
                }
            }.start()
        }

        root.addView(title)
        root.addView(keyInput)
        root.addView(nicheInput)
        root.addView(runButton)
        root.addView(status)
        root.addView(reportView)
        root.addView(listBox)

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
