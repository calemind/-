package com.example.antikami

/* =========================================================================
 *  アンチ神アプリ — 全コードをこの1ファイルにまとめています
 *
 *  仕組み:
 *    「Game」「Social」のようなグループを作り、そこに複数アプリを登録。
 *    グループ内のアプリの「今日の合計使用時間」が上限を超えたら、
 *    そのグループのアプリを開いた瞬間にホームへ戻して警告を出す。
 *
 *  含まれるもの:
 *    1. Store        … グループ設定の保存（端末内のみ・JSON）
 *    2. Usage        … UsageStatsManagerで今日の使用時間を集計
 *    3. GuardService … 前面アプリを監視してブロック（本体）
 *    4. MainActivity … グループ管理・権限設定の画面
 * ========================================================================= */

import android.app.AlertDialog
import android.app.AppOpsManager
import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID


/* ---------------------------------------------------------------------
 * 1. Store — グループ設定の保存（SharedPreferences + JSON）
 * --------------------------------------------------------------------- */
data class Group(
    val id: String,
    var name: String,
    var minutes: Int,
    var packages: MutableSet<String>
)

object Store {
    private const val PREF = "antikami"
    private const val KEY_GROUPS = "groups"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Group> {
        val raw = prefs(ctx).getString(KEY_GROUPS, "[]") ?: "[]"
        val out = mutableListOf<Group>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pkgs = mutableSetOf<String>()
                val pa = o.optJSONArray("packages") ?: JSONArray()
                for (j in 0 until pa.length()) pkgs.add(pa.getString(j))
                out.add(
                    Group(
                        o.optString("id", UUID.randomUUID().toString()),
                        o.optString("name", "グループ"),
                        o.optInt("minutes", 30),
                        pkgs
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun save(ctx: Context, groups: List<Group>) {
        val arr = JSONArray()
        for (g in groups) {
            val o = JSONObject()
            o.put("id", g.id)
            o.put("name", g.name)
            o.put("minutes", g.minutes)
            o.put("packages", JSONArray(g.packages.toList()))
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_GROUPS, arr.toString()).apply()
    }

    /** 指定パッケージが所属するグループを返す（なければnull） */
    fun groupOf(ctx: Context, pkg: String): Group? =
        load(ctx).firstOrNull { it.packages.contains(pkg) }
}


/* ---------------------------------------------------------------------
 * 2. Usage — 今日0時からの合計使用時間を計算
 *    queryEvents で「前面に来た/離れた」イベントを拾って積算するため、
 *    アプリごとの実際の画面表示時間に近い値が取れる。
 * --------------------------------------------------------------------- */
object Usage {

    fun todayStart(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** packages に含まれるアプリの、今日の合計前面時間（ミリ秒） */
    fun groupUsedMs(ctx: Context, packages: Set<String>): Long {
        if (packages.isEmpty()) return 0L
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0L

        val start = todayStart()
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(start, now)

        val openedAt = HashMap<String, Long>()
        var total = 0L
        val e = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            if (!packages.contains(pkg)) continue

            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    openedAt[pkg] = e.timeStamp

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val s = openedAt.remove(pkg)
                    if (s != null && e.timeStamp > s) total += e.timeStamp - s
                }
            }
        }
        // まだ閉じていない（今まさに開いている）分を加算
        for ((_, s) in openedAt) if (now > s) total += now - s

        return total
    }

    /** 使用状況へのアクセス権限が付与されているか */
    fun hasPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}


/* ---------------------------------------------------------------------
 * 3. GuardService — 前面アプリを監視し、上限超過ならホームへ戻す
 * --------------------------------------------------------------------- */
class GuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentPkg: String? = null
    private var overlay: View? = null
    private var warnedGroupId: String? = null

    /** アプリを開いている最中に上限へ到達する場合があるので、定期的にも確認する */
    private val ticker = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, 3_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        currentPkg = pkg
        warnedGroupId = null
        check()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        removeOverlay()
        super.onDestroy()
    }

    private fun check() {
        val pkg = currentPkg ?: return
        val g = Store.groupOf(this, pkg)
        if (g == null) {
            removeOverlay()
            return
        }
        val used = Usage.groupUsedMs(this, g.packages)
        val limit = g.minutes * 60_000L

        if (used >= limit) {
            showBlock(g, used)
            performGlobalAction(GLOBAL_ACTION_HOME)
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 300L)
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 800L)
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 1500L)
        } else {
            val leftMin = ((limit - used) / 60_000L).toInt()
            if (leftMin <= 5 && warnedGroupId != g.id) {
                warnedGroupId = g.id
                Toast.makeText(
                    this,
                    "「${g.name}」の残り時間は約 ${leftMin + 1} 分です",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** 画面最前面にブロック表示を出す（数秒後に自動で消える） */
    private fun showBlock(g: Group, usedMs: Long) {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) return

        val usedMin = usedMs / 60_000L

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#EE101418"))
            setPadding(64, 64, 64, 64)
        }
        root.addView(TextView(this).apply {
            text = "⛔ ${g.name}"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "\n今日の上限に達しました\n\n${usedMin} 分 / ${g.minutes} 分\n\n" +
                    "このグループのアプリは\n明日まで開けません"
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 17f
            gravity = Gravity.CENTER
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(root, lp)
            overlay = root
            handler.postDelayed({ removeOverlay() }, 4000L)
        } catch (_: Exception) {
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }
}


/* ---------------------------------------------------------------------
 * 4. MainActivity — グループの作成・編集と権限設定
 * --------------------------------------------------------------------- */
class MainActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private var groups: MutableList<Group> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 96)
        }
        setContentView(ScrollView(this).apply { addView(container) })
    }

    override fun onResume() {
        super.onResume()
        groups = Store.load(this)
        render()
    }

    private fun render() {
        container.removeAllViews()

        container.addView(TextView(this).apply {
            text = "アンチ神アプリ"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })
        container.addView(TextView(this).apply {
            text = "グループ単位で1日の合計使用時間を制限します\n"
            textSize = 13f
        })

        // --- 権限セクション ---
        container.addView(sectionTitle("必要な権限"))

        container.addView(Button(this).apply {
            text = if (Usage.hasPermission(this@MainActivity))
                "① 使用状況へのアクセス ✅" else "① 使用状況へのアクセスを許可"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        })
        container.addView(Button(this).apply {
            text = if (Settings.canDrawOverlays(this@MainActivity))
                "② 他アプリの上に重ねて表示 ✅" else "② 他アプリの上に重ねて表示を許可"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })
        container.addView(Button(this).apply {
            text = "③ ユーザー補助を有効にする"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        // --- グループ一覧 ---
        container.addView(sectionTitle("グループ"))

        if (groups.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "まだグループがありません。\n下のボタンから作成してください。\n"
                textSize = 14f
            })
        }

        for (g in groups) {
            val used = Usage.groupUsedMs(this, g.packages) / 60_000L
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(36, 36, 36, 36)
                setBackgroundColor(Color.parseColor("#22808080"))
            }
            card.addView(TextView(this).apply {
                text = g.name
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = "本日 ${used} 分 / 上限 ${g.minutes} 分　　登録 ${g.packages.size} 個"
                textSize = 14f
            })

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(this).apply {
                text = "アプリ選択"
                setOnClickListener { pickApps(g) }
            })
            row.addView(Button(this).apply {
                text = "時間"
                setOnClickListener { editMinutes(g) }
            })
            row.addView(Button(this).apply {
                text = "削除"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("「${g.name}」を削除しますか？")
                        .setPositiveButton("削除") { _, _ ->
                            groups.remove(g)
                            Store.save(this@MainActivity, groups)
                            render()
                        }
                        .setNegativeButton("やめる", null)
                        .show()
                }
            })
            card.addView(row)

            container.addView(card)
            container.addView(TextView(this).apply { text = " " })
        }

        container.addView(Button(this).apply {
            text = "＋ グループを追加"
            setOnClickListener { addGroup() }
        })

        container.addView(TextView(this).apply {
            text = "\n\n制限を解除するには、設定からユーザー補助を\nオフにするか、本アプリをアンインストールします。\n（意図的に手間がかかる設計です）"
            textSize = 12f
        })
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = "\n$t"
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun addGroup() {
        val input = EditText(this).apply { hint = "例: Game / Social" }
        AlertDialog.Builder(this)
            .setTitle("グループ名")
            .setView(input)
            .setPositiveButton("作成") { _, _ ->
                val name = input.text.toString().ifBlank { "新しいグループ" }
                groups.add(Group(UUID.randomUUID().toString(), name, 30, mutableSetOf()))
                Store.save(this, groups)
                render()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun editMinutes(g: Group) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(g.minutes.toString())
        }
        AlertDialog.Builder(this)
            .setTitle("${g.name} の1日の上限（分）")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                g.minutes = input.text.toString().toIntOrNull()?.coerceIn(1, 1440) ?: g.minutes
                Store.save(this, groups)
                render()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun pickApps(g: Group) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second }

        if (apps.isEmpty()) {
            Toast.makeText(this, "アプリ一覧を取得できませんでした", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = apps.map { it.second }.toTypedArray()
        val checked = apps.map { g.packages.contains(it.first) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("「${g.name}」に入れるアプリ")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val selected = mutableSetOf<String>()
                apps.forEachIndexed { i, p -> if (checked[i]) selected.add(p.first) }
                g.packages = selected
                Store.save(this, groups)
                render()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
