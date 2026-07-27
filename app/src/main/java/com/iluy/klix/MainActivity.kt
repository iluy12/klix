package com.iluy.klix

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * קליקס — אפליקציית בדיקה בלבד. שאלה יחידה: אילו KeyEvent מגיעים בפועל
 * מהכפתור הצדדי לאפליקציית צד-שלישי, ובאילו תבניות (בודדת/כפולה/ארוכה).
 *
 * עיקרון-בטיחות: אף פעם לא "בולעים" (consume) אירוע-מקש. תמיד מעבירים
 * הלאה ל-super, כדי שההתנהגות הרגילה של הכפתור (מסך, תפריט, SOS) תמשיך
 * לעבוד בדיוק כרגיל תוך כדי הבדיקה. זו רק "האזנה", לא התערבות.
 *
 * מגבלה ידועה: dispatchKeyEvent עובד רק כשהאפליקציה בחזית עם מסך דלוק —
 * זה עונה על "האם אפשר בכלל ליירט את הכפתור", לא על "מה קורה כשהמסך כבוי"
 * (זו שאלה נפרדת, לא ניתנת לבדיקה מ-APK רגיל).
 */
class MainActivity : AppCompatActivity() {

    private val logLines = mutableListOf<String>()
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var summaryView: TextView

    // למעקב אחר קצב לחיצות (זיהוי כפולה/משולשת לתצוגה חיה בלבד)
    private val recentDownTimestamps = mutableMapOf<Int, MutableList<Long>>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var eventCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        appendLine("קליקס מוכן. לחץ על הכפתור הצדדי כדי לבדוק.")
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 20)
        }

        summaryView = TextView(this).apply {
            text = "אירועים: 0"
            textSize = 13f
            setTextColor(getColorCompat(R.color.text_secondary))
        }
        root.addView(summaryView)

        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(getColorCompat(R.color.text_primary))
            setPadding(0, 12, 0, 12)
        }
        scrollView = ScrollView(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            layoutParams = lp
            addView(logView)
        }
        root.addView(scrollView)

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttonRow.addView(Button(this).apply {
            text = "העתק ללוח"
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
            setOnClickListener { copyLogToClipboard() }
        })
        buttonRow.addView(Button(this).apply {
            text = "נקה"
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginStart = 8
            layoutParams = lp
            setOnClickListener { clearLog() }
        })
        root.addView(buttonRow)

        return root
    }

    private fun getColorCompat(id: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, id)

    /**
     * נקודת-הכניסה המוקדמת ביותר שזמינה ל-Activity לכל אירוע-מקש. תמיד
     * מחזירים super.dispatchKeyEvent — לעולם לא consume — כדי לא להפריע
     * להתנהגות הרגילה של הכפתור בזמן הבדיקה.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        logKeyEvent(event)
        return super.dispatchKeyEvent(event)
    }

    private fun logKeyEvent(event: KeyEvent) {
        val now = System.currentTimeMillis()
        val keyName = KeyEvent.keyCodeToString(event.keyCode)
        val actionName = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> "ACTION_${event.action}"
        }

        var pattern = ""
        if (event.action == KeyEvent.ACTION_DOWN) {
            val timestamps = recentDownTimestamps.getOrPut(event.keyCode) { mutableListOf() }
            timestamps.add(now)
            // שומרים רק את 5 השניות האחרונות לצורך זיהוי-תבנית חי בתצוגה
            timestamps.removeAll { now - it > 5_000L }
            pattern = when {
                timestamps.size >= 3 -> " ← 🔵 נראה כמו 3+ לחיצות מהירות"
                timestamps.size == 2 -> " ← 🔵 נראה כמו לחיצה כפולה"
                else -> ""
            }
        }

        val repeatInfo = if (event.repeatCount > 0) " (repeatCount=${event.repeatCount}, כנראה long-press)" else ""

        eventCount++
        val line = "${fmt.format(Date(now))}  keyCode=${event.keyCode} ($keyName)  action=$actionName$repeatInfo$pattern"
        appendLine(line)
        persistLine(line)
    }

    private fun appendLine(line: String) {
        logLines.add(line)
        if (logLines.size > 500) logLines.removeAt(0)
        logView.text = logLines.joinToString("\n")
        summaryView.text = "אירועים: $eventCount"
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun persistLine(line: String) {
        try {
            val file = File(filesDir, "klix_events.log")
            FileWriter(file, true).use { it.write(line + "\n") }
        } catch (e: Exception) {
            // לוג-דיבאג בלבד, לא קריטי אם כתיבה בודדת נכשלת
        }
    }

    private fun copyLogToClipboard() {
        val full = logLines.joinToString("\n")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("klix_log", full))
        Toast.makeText(this, "הועתק ללוח (${logLines.size} שורות)", Toast.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        logLines.clear()
        eventCount = 0
        recentDownTimestamps.clear()
        logView.text = ""
        summaryView.text = "אירועים: 0"
        appendLine("נוקה. ממתין ללחיצות חדשות.")
    }
}
