package com.rose.login

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * QQ 扫码登录协议（玫瑰小镇）
 * 与 qq_qrlogin.py 完全等价
 */
class QQLogin(private val log: (String) -> Unit) {

    companion object {
        const val APPID = "716027609"
        const val DAID = "383"
        const val PT_3RD_AID = "102072120"
        const val S_URL = "https://graph.qq.com/oauth2.0/login_jump"
        const val REDIRECT_URI = "https://meigui.qq.com/other/qcloginproxy.html"
        const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private val cookieJar = SimpleCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .build()

    data class PollResult(val code: String, val url: String, val msg: String, val nick: String)

    fun fetchLoginSig(): String {
        log("[1] 获取 pt_login_sig...")
        val url = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin" +
            "?appid=$APPID&daid=$DAID&style=33" +
            "&hide_title_bar=1&hide_border=1&target=self" +
            "&s_url=${enc(S_URL)}&pt_3rd_aid=$PT_3RD_AID"
        client.newCall(req(url)).execute().use { it.body?.close() }
        val sig = cookieJar.find("pt_login_sig")
            ?: error("未获取到 pt_login_sig")
        log("    pt_login_sig=${sig.take(20)}...")
        return sig
    }

    fun fetchQrCode(): Pair<Bitmap, String> {
        log("[2] 拉取二维码...")
        val url = "https://xui.ptlogin2.qq.com/ssl/ptqrshow" +
            "?appid=$APPID&e=2&l=M&s=3&d=72&v=4&t=${Math.random()}" +
            "&daid=$DAID&pt_3rd_aid=$PT_3RD_AID&u1=${enc(S_URL)}"
        val resp = client.newCall(req(url)).execute()
        val bytes = resp.body!!.bytes()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("二维码图片解码失败")
        val qrsig = cookieJar.find("qrsig") ?: error("未获取到 qrsig")
        log("    qrsig=${qrsig.take(20)}...")
        log("    二维码大小: ${bitmap.width}x${bitmap.height}")
        return Pair(bitmap, qrsig)
    }

    fun pollStatus(qrsig: String, loginSig: String): PollResult {
        val ptqrtoken = hash33(qrsig)
        val action = "0-0-${System.currentTimeMillis()}"
        val url = "https://xui.ptlogin2.qq.com/ssl/ptqrlogin" +
            "?u1=${enc(S_URL)}&ptqrtoken=$ptqrtoken" +
            "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052" +
            "&action=$action&js_ver=26030415&js_type=1" +
            "&login_sig=${enc(loginSig)}&pt_uistyle=40" +
            "&aid=$APPID&daid=$DAID&pt_3rd_aid=$PT_3RD_AID"
        val text = client.newCall(req(url)).execute().body!!.string()
        return parsePtuiCB(text)
    }

    fun followCheckSig(checkSigUrl: String) {
        log("[4] 跟随 check_sig 拿登录态...")
        var cur = checkSigUrl
        repeat(5) { hop ->
            val resp = client.newCall(req(cur)).execute().also { it.body?.close() }
            val loc = resp.header("Location")
            log("    hop$hop: ${resp.code} -> ${loc?.take(80) ?: "(end)"}")
            if (loc == null) return
            cur = if (loc.startsWith("http")) loc else cur.toHttpUrl().resolve(loc)!!.toString()
        }
    }

    fun getAllCookies(): String {
        return cookieJar.allCookies()
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun getKeyCookies(): Map<String, String> {
        val all = cookieJar.allCookies()
        val keys = listOf("p_uin", "p_skey", "pt4_token", "pt_oauth_token",
            "uin", "skey", "openid", "token")
        return keys.mapNotNull { k ->
            all.firstOrNull { it.name == k }?.let { k to it.value }
        }.toMap()
    }

    private fun req(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", UA)
        .build()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun hash33(s: String): Long {
        var e = 0L
        for (c in s) {
            e = (e + (e shl 5) + c.code) and 0xFFFFFFFFL
        }
        return e and 0x7FFFFFFFL
    }

    private fun parsePtuiCB(text: String): PollResult {
        val r = Regex("""ptuiCB\('([^']*)','([^']*)','([^']*)','([^']*)','([^']*)'(?:,'([^']*)')?""")
        val m = r.find(text) ?: return PollResult("error", "", text.take(60), "")
        return PollResult(
            code = m.groupValues[1],
            url = m.groupValues[3],
            msg = m.groupValues[5],
            nick = m.groupValues.getOrNull(6) ?: ""
        )
    }
}

private fun String.toHttpUrl() = HttpUrl.Builder().apply {
    val u = java.net.URL(this@toHttpUrl)
    scheme(u.protocol).host(u.host)
    if (u.port > 0) port(u.port)
    encodedPath(u.path)
    if (u.query != null) encodedQuery(u.query)
}.build()

class SimpleCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            store.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return store.filter { it.matches(url) }
    }

    @Synchronized
    fun find(name: String): String? = store.firstOrNull { it.name == name }?.value

    @Synchronized
    fun allCookies(): List<Cookie> = store.toList()
}
