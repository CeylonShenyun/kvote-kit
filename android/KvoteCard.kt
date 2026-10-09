package kvote

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * kvote 额度卡的 Jetpack Compose 版（从我们 app 里摘出来的，网页版在 ../web）。
 *
 * 数据：GET 一个返回 kvote 格式 JSON 的地址（kvote-kit 的 server.py 是 /api/usage），
 * {"updated": 秒, "windows": {"five_hour": {"utilization": 0~1, "resetsAt": 秒}, ...}}。
 * 用之前设好 Kvote.endpoint（要鉴权就再设 Kvote.authHeader，比如 "Basic xxx"），然后在任意地方放 KvoteCard()。
 *
 * 样子：透的深海蓝玻璃 + 几粒海雪；每个额度一行海（两层流动的浪），像素小帆船开到哪 = 用了多少，
 * 开过的那段浪亮、闪星光，没开到的淡；尽头一只红白浮标（到这就满了）。80% 以上开过的浪和百分比变暖光。
 * 依赖：Compose（foundation / material3）、OkHttp、org.json（安卓自带）、kotlinx.coroutines。
 */
object Kvote {
    /** 数据地址，比如 "http://192.168.1.5:18020/api/usage" */
    @Volatile var endpoint: String = "http://127.0.0.1:18020/api/usage"
    /** 要鉴权就填 Authorization 头的值，比如 "Basic " + base64("user:pass")；不用就留 null */
    @Volatile var authHeader: String? = null
    private const val PREFS = "kvote"
    private val client by lazy { OkHttpClient() }

    data class Window(val key: String, val utilization: Float, val resetsAt: Long)
    data class Snapshot(val updated: Long?, val windows: List<Window>)

    private val _state = MutableStateFlow<Snapshot?>(null)
    val state: StateFlow<Snapshot?> = _state.asStateFlow()
    @Volatile private var loaded = false

    /** 进程刚起来先用上次存下的，卡片不用等网络 */
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", null)
            ?.let { runCatching { parse(it) }.getOrNull() }
            ?.let { if (_state.value == null) _state.value = it }
    }

    suspend fun refresh(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val b = Request.Builder().url(endpoint)
            authHeader?.let { b.header("Authorization", it) }
            val raw = client.newCall(b.build()).execute().use { resp -> if (resp.isSuccessful) resp.body.string() else null }
                ?: return@runCatching
            val snap = parse(raw)
            // 服务器那份还空着（刚装好、还没跑过一轮）就别把手机上存着的那份冲掉
            if (snap.updated == null && _state.value != null) return@runCatching
            _state.value = snap
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", raw).apply()
        }
    }

    private fun parse(raw: String): Snapshot {
        val j = JSONObject(raw)
        val wins = j.optJSONObject("windows")
        val list = buildList {
            wins?.keys()?.forEach { k ->
                val w = wins.optJSONObject(k) ?: return@forEach
                add(Window(k, w.optDouble("utilization", 0.0).toFloat(), w.optLong("resetsAt", 0L)))
            }
        }
        return Snapshot(if (j.isNull("updated")) null else j.optLong("updated"), list)
    }

    private val ORDER = listOf("five_hour", "seven_day", "seven_day_overage_included")
    fun ordered(s: Snapshot?, nowMs: Long): List<Window> =
        (s?.windows ?: emptyList())
            .filter { it.resetsAt * 1000 > nowMs }
            .filter { it.key == "five_hour" || it.key.startsWith("seven_day") }
            .sortedWith(compareBy({ ORDER.indexOf(it.key).let { i -> if (i < 0) 99 else i } }, { it.key }))

    fun label(key: String): String = when (key) {
        "five_hour" -> "本轮会话"
        "seven_day" -> "每周 · 所有模型"
        // Claude Code 里写的是"按模型分的每周额度"，只有用到那个模型的那轮才带回来；我们测下来是 Fable，不对就改这里
        "seven_day_overage_included" -> "每周 · Fable"
        else -> "每周 · " + key.removePrefix("seven_day_").replace('_', ' ')
            .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    private val WEEK = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    fun resetText(resetsAt: Long, nowMs: Long): String {
        val ms = resetsAt * 1000 - nowMs
        if (ms <= 0) return "已重置"
        if (ms < 24 * 3600_000L) {
            val t = (ms / 60_000.0).roundToInt()
            val h = t / 60
            val m = t % 60
            return (if (h > 0) "$h 小时 " else "") + "$m 分后重置"
        }
        val c = Calendar.getInstance().apply { timeInMillis = resetsAt * 1000 }
        return "%s %02d:%02d 重置".format(WEEK[c.get(Calendar.DAY_OF_WEEK) - 1], c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    fun updatedText(updated: Long?, nowMs: Long): String {
        if (updated == null) return "还没聊过"
        val min = ((nowMs / 1000 - updated) / 60).coerceAtLeast(0)
        return when {
            min < 1 -> "刚刚更新"
            min < 60 -> "$min 分钟前更新"
            min < 24 * 60 -> "${min / 60} 小时前更新"
            else -> "${min / 1440} 天前更新"
        }
    }
}

// ── 颜色：深海蓝色卡 11141B / 1C273E / 2C4972 / 4A74A1 / 8FACCB ──
private val Ink = Color(0xFFE7EEF7)
private val Ink2 = Color(0xFFB4C6DB)
private val Bachelor = Color(0xFF8FACCB)
private val Warm = Color(0xFFF6D58E)
private val WarmDim = Color(0xFFC9A979)
private val TextShade = Shadow(Color(0x8C000000), Offset(0f, 2f), 6f)

@Composable
private fun rememberFrameMillis(): State<Long> = produceState(0L) {
    val start = withFrameMillis { it }
    while (true) withFrameMillis { value = it - start }
}

@Composable
fun KvoteCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        Kvote.ensureLoaded(ctx)
        Kvote.refresh(ctx)
    }
    val snap by Kvote.state.collectAsState()
    // 倒计时和"几分钟前更新"自己走，不用等下一次拉数据
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(20_000)
            value = System.currentTimeMillis()
        }
    }
    val frame = rememberFrameMillis()
    val wins = Kvote.ordered(snap, nowMs)
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .drawBehind { drawCardGlass() }
            .border(1.dp, Color(0x42FFFFFF), shape),
    ) {
        Canvas(Modifier.matchParentSize()) { drawSnow(frame.value) }
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.9.sp)) { append("额度") }
                        withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 13.sp, color = Bachelor)) { append("  Kvote") }
                    },
                    color = Ink,
                    style = TextStyle(shadow = TextShade),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    Kvote.updatedText(snap?.updated, nowMs),
                    color = Ink2.copy(alpha = .8f), fontSize = 10.5.sp,
                    style = TextStyle(shadow = TextShade),
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x24E7EEF7)))
            if (wins.isEmpty()) {
                Text(
                    "还没有用量数据，跟 Claude 说句话就有了",
                    color = Ink2.copy(alpha = .8f), fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                    style = TextStyle(shadow = TextShade),
                )
            }
            wins.forEachIndexed { i, w ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12E7EEF7)))
                KvoteRow(w, nowMs, frame)
            }
        }
    }
}

@Composable
private fun KvoteRow(w: Kvote.Window, nowMs: Long, frame: State<Long>) {
    val pct = (w.utilization * 100).roundToInt().coerceIn(0, 100)
    val hot = pct >= 80
    Column(Modifier.padding(top = 8.dp, bottom = 7.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Kvote.label(w.key),
                color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp, style = TextStyle(shadow = TextShade),
            )
            Spacer(Modifier.weight(1f))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = 17.sp, fontStyle = FontStyle.Italic, color = if (hot) Warm else Ink)) { append("$pct") }
                    withStyle(SpanStyle(fontSize = 11.sp, fontStyle = FontStyle.Italic, color = Ink2)) { append("%") }
                },
                fontFamily = FontFamily.Serif,
                style = TextStyle(shadow = TextShade),
            )
        }
        KvoteSea(
            p = w.utilization.coerceIn(0f, 1f),
            hot = hot,
            frame = frame,
            modifier = Modifier.fillMaxWidth().height(34.dp),
        )
        Text(
            Kvote.resetText(w.resetsAt, nowMs),
            color = if (hot) WarmDim else Ink2.copy(alpha = .82f), fontSize = 10.5.sp,
            modifier = Modifier.padding(top = 3.dp),
            style = TextStyle(shadow = TextShade),
        )
    }
}

// ───────────────────────── 卡片玻璃 ─────────────────────────

/** 透的蓝玻璃：底下一层淡淡的色卡蓝（上浅下深），顶上一片水面透下来的光，斜向白高光，上沿一道细亮线 */
private fun DrawScope.drawCardGlass() {
    drawRect(Brush.verticalGradient(0f to Color(0x6B2C4972), .62f to Color(0x801C273E), 1f to Color(0x8011141B)))
    drawRect(
        Brush.radialGradient(
            listOf(Color(0x388FACCB), Color(0x008FACCB)),
            center = Offset(size.width / 2, -size.height * .2f),
            radius = size.width * .75f,
        )
    )
    drawRect(
        Brush.linearGradient(
            0f to Color(0x1FFFFFFF), .4f to Color(0x05FFFFFF), .66f to Color.Transparent, 1f to Color(0x0FFFFFFF),
            start = Offset.Zero, end = Offset(size.width, size.height),
        )
    )
    val r = 20.dp.toPx()
    drawLine(Color(0x73FFFFFF), Offset(r * .6f, 1.dp.toPx()), Offset(size.width - r * .6f, 1.dp.toPx()), 1f)
}

/** 海雪：几粒很淡的光点往上漂 */
private fun DrawScope.drawSnow(t: Long) {
    val u = 1.dp.toPx()
    for (i in 0 until 7) {
        val dur = (7_000 + (i % 3) * 2_500).toFloat()
        val f = (((t + i * 1_700L) % dur.toLong()) / dur)
        val a = when {
            f < .15f -> f / .15f
            f > .85f -> (1f - f) / .15f
            else -> 1f
        } * .35f
        val x = size.width * (.08f + i * .13f) + 6 * u * f
        val y = size.height + 4 * u - 150 * u * f
        drawCircle(Color(0xFFCFE2F7).copy(alpha = a), radius = u, center = Offset(x, y))
    }
}

// ───────────────────────── 一行海 ─────────────────────────

// 新画的小帆船 22×18：木头船身、一圈奶白船舷、三个亮着暖灯的圆窗，船头船尾翘起来；
// 主帆白、前帆背光偏蓝灰，中间一道色卡蓝的帆条；桅杆顶一面小红旗，两帧换着飘
private val HULL = listOf(
    "..........MW..........", "..........MWW.........", ".........wMWWW........", "........wwMWWWW.......",
    ".......wwwMWWWWW......", "......wwwwMWWWWWW.....", ".....bbbbbMbbbbbbb....", "....wwwwwwMWWWWWWWW...",
    "...wwwwwwwMWWWWWWWWW..", "..wwwwwwwwMWWWWWWWWW..", "G.........M.........GG", "GGGGGGGGGGGGGGGGGGGGGG",
    "HHHHoHHHHoHHHHoHHHHHHH", ".hhhhhhhhhhhhhhhhhhhh.", "..HHHHHHHHHHHHHHHHHH..", "....KKKKKKKKKKKKKK....",
)
private val FLAG_A = listOf("..........MRRR........", "..........MRr.........")
private val FLAG_B = listOf("..........MRR.........", "..........MRRr........")
private val BOAT_PAL = mapOf(
    'M' to Color(0xFF5A3A28), 'R' to Color(0xFFD4483C), 'r' to Color(0xFFA8352C), 'W' to Color(0xFFF2F5F9),
    'w' to Color(0xFFC6D4E3), 'b' to Color(0xFF6C90B6), 'G' to Color(0xFFEADFCA), 'H' to Color(0xFF8A5A3C),
    'h' to Color(0xFF6E4630), 'K' to Color(0xFF3F2A1E), 'o' to Color(0xFFFFE9A8),
)
// 终点的红白浮标 5×8，顶上一盏灯
private val BUOY = listOf("..y..", ".kkk.", ".RRR.", ".WWW.", ".RRR.", ".WWW.", "kkkkk", ".kkk.")
private val BUOY_PAL = mapOf('y' to Color(0xFFFFE9A8), 'k' to Color(0xFF27324A), 'R' to Color(0xFFC9473E), 'W' to Color(0xFFE6ECF4))

private class Run(val x: Int, val y: Int, val w: Int, val color: Color)

private fun runs(rows: List<String>, pal: Map<Char, Color>, y0: Int = 0): List<Run> = buildList {
    rows.forEachIndexed { y, r ->
        var x = 0
        while (x < r.length) {
            val c = pal[r[x]]
            if (c == null) { x++; continue }
            val x0 = x
            while (x < r.length && r[x] == r[x0]) x++
            add(Run(x0, y0 + y, x - x0, c))
        }
    }
}

private val HULL_RUNS = runs(HULL, BOAT_PAL, 2)
private val FLAG_A_RUNS = runs(FLAG_A, BOAT_PAL)
private val FLAG_B_RUNS = runs(FLAG_B, BOAT_PAL)
private val BUOY_RUNS = runs(BUOY, BUOY_PAL)

/** 像素画按整数像素画，格子之间不留缝 */
private fun DrawScope.drawRuns(list: List<Run>, ox: Float, oy: Float, k: Float) {
    for (r in list) drawRect(r.color, Offset(ox + r.x * k, oy + r.y * k), Size(r.w * k, k))
}

// 船跟着浪起伏、顺着浪坡歪一点（一个周期 = 一个浪长 1.8 秒）：(下沉 dp, 歪的角度)
private val RIDE = arrayOf(
    1.4f to -5f, .4f to -3.5f, 0f to 0f, .4f to 3.5f, 1.4f to 5f, 2.4f to 3.5f, 2.8f to 0f, 2.4f to -3.5f, 1.4f to -5f,
)

private fun ride(t: Long): Pair<Float, Float> {
    val f = (t % 1_800L) / 1_800f * 8f
    val i = floor(f).toInt().coerceIn(0, 7)
    val k = f - i
    val (y0, r0) = RIDE[i]
    val (y1, r1) = RIDE[i + 1]
    return (y0 + (y1 - y0) * k) to (r0 + (r1 - r0) * k)
}

private class SeaPaths(val front: Path, val shine: Path, val dots: List<Offset>, val back: Path)

/** 两层浪的花纹：前浪一个浪长 26dp、后浪 38dp，多铺一个浪长好往左流 */
private fun seaPaths(width: Float, u: Float, top: Float): SeaPaths {
    val front = Path()
    val shine = Path()
    val dots = mutableListOf<Offset>()
    var x = -26 * u
    front.moveTo(x, top + 4.5f * u)
    while (x < width + 26 * u) {
        front.quadraticTo(x + 6.5f * u, top + .8f * u, x + 13 * u, top + 4.5f * u)
        front.quadraticTo(x + 19.5f * u, top + 8.2f * u, x + 26 * u, top + 4.5f * u)
        shine.moveTo(x + 3.6f * u, top + 3.1f * u)
        shine.quadraticTo(x + 6.5f * u, top + 1.9f * u, x + 9.4f * u, top + 3.1f * u)
        dots += Offset(x + 11.2f * u, top + 2.7f * u)
        x += 26 * u
    }
    val back = Path()
    var bx = -38 * u
    val by = top + 1 * u + 9.6f * u
    back.moveTo(bx, by)
    while (bx < width + 38 * u) {
        back.quadraticTo(bx + 9.5f * u, by - 2.6f * u, bx + 19 * u, by)
        back.quadraticTo(bx + 28.5f * u, by + 2.6f * u, bx + 38 * u, by)
        bx += 38 * u
    }
    return SeaPaths(front, shine, dots, back)
}

private val STARS = arrayOf(.09f to 300L, .24f to 1400L, .41f to 800L, .57f to 1900L, .72f to 100L, .88f to 1100L)
private val STAR_DUR = longArrayOf(2200, 2600, 2200, 1900, 2500, 2200)

@Composable
private fun KvoteSea(p: Float, hot: Boolean, frame: State<Long>, modifier: Modifier) {
    Box(
        modifier.drawWithCache {
            val u = 1.dp.toPx()
            val lift = 6 * u                    // 海往上探进标题那行 6dp
            val buoyW = 12 * u
            val trackW = size.width - buoyW
            val top = 27 * u - lift             // 浪带的上沿
            val paths = seaPaths(trackW, u, top)
            val k = (1.5f * u).roundToInt().coerceAtLeast(1).toFloat()
            val boatW = 22 * k
            val boatH = 18 * k
            val bk = (1.6f * u).roundToInt().coerceAtLeast(1).toFloat()
            val glow = if (hot) Color(0xFFF2C46D) else Color(0xFFA9C6E6)
            val line = if (hot) Color(0xFFF6DCAE) else Color(0xFFDBE8F6)
            onDrawBehind {
                val t = frame.value
                val x = (trackW - boatW) * p
                val travelled = (x + boatW * .5f).coerceIn(0f, trackW)
                val f1 = -((t % 1_800L) / 1_800f) * 26 * u
                val f2 = -((t % 3_000L) / 3_000f) * 38 * u

                fun waves(alpha: Float, glowC: Color, lineC: Color) {
                    translate(f2, 0f) { drawPath(paths.back, Bachelor.copy(alpha = .62f * alpha), style = Stroke(1.1f * u, cap = StrokeCap.Round)) }
                    translate(f1, 0f) {
                        drawPath(paths.front, glowC.copy(alpha = .2f * alpha), style = Stroke(4.5f * u, cap = StrokeCap.Round))
                        drawPath(paths.front, lineC.copy(alpha = alpha), style = Stroke(1.8f * u, cap = StrokeCap.Round))
                        drawPath(paths.shine, Color.White.copy(alpha = .9f * alpha), style = Stroke(.9f * u, cap = StrokeCap.Round))
                        for (d in paths.dots) drawCircle(Color.White.copy(alpha = .8f * alpha), .55f * u, d)
                    }
                }
                // 没开到的那段：整条淡淡的
                clipRect(0f, top, trackW, top + 13 * u) { waves(.3f, Color(0xFFA9C6E6), Color(0xFFDBE8F6)) }
                // 开过的那段：亮的，上面零星闪几颗小星光
                clipRect(0f, top, travelled, top + 13 * u) {
                    waves(1f, glow, line)
                    STARS.forEachIndexed { i, (pos, lag) ->
                        val sx = trackW * pos
                        if (sx > travelled) return@forEachIndexed
                        val dur = STAR_DUR[i]
                        val ph = ((t + lag) % dur) / dur.toFloat()
                        val e = if (ph < .45f) ph / .45f else (1f - ph) / .55f
                        val a = (sin(e * PI / 2).toFloat()) * .95f
                        val s = (.5f + .5f * e) * 2.5f * u
                        val c = Offset(sx, top + 2.5f * u)
                        drawLine(Color.White.copy(alpha = a), Offset(c.x - s, c.y), Offset(c.x + s, c.y), 1f)
                        drawLine(Color.White.copy(alpha = a), Offset(c.x, c.y - s), Offset(c.x, c.y + s), 1f)
                    }
                }
                // 浮标：一上一下，灯一闪一闪
                val bob = (sin(t / 2_600.0 * 2 * PI).toFloat() * .5f + .5f) * 1.5f * u
                val bx = size.width - 5 * bk
                val byTop = 20 * u - lift + bob
                val lamp = .35f + .65f * (sin(t / 2_400.0 * 2 * PI - PI / 2).toFloat() * .5f + .5f)
                drawCircle(
                    Brush.radialGradient(
                        0f to Color(0xCCFFE9A8), .5f to Color(0x33FFD678), 1f to Color(0x00FFD678),
                        center = Offset(bx + 2.5f * bk, byTop + .5f * bk), radius = 8 * u,
                    ),
                    radius = 8 * u, center = Offset(bx + 2.5f * bk, byTop + .5f * bk), alpha = lamp,
                )
                drawRuns(BUOY_RUNS, bx.roundToInt().toFloat(), byTop.roundToInt().toFloat(), bk)
                // 船：船底离这一行底 4dp，水线压在浪心上
                val (dy, rot) = ride(t)
                val boatTop = 40 * u - lift - 4 * u - boatH
                val pivot = Offset(x + 16 * u, 33 * u - lift)
                translate(0f, dy * u) {
                    rotate(rot, pivot) {
                        val ox = x.roundToInt().toFloat()
                        val oy = boatTop.roundToInt().toFloat()
                        drawRuns(if ((t / 350L) % 2L == 0L) FLAG_A_RUNS else FLAG_B_RUNS, ox, oy, k)
                        drawRuns(HULL_RUNS, ox, oy, k)
                    }
                }
            }
        }
    )
}
