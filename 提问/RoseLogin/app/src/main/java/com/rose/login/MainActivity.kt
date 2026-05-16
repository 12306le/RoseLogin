package com.rose.login

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var btnLogin: Button
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvCookie: TextView
    private lateinit var ivQr: ImageView
    private lateinit var svLog: ScrollView

    private var qrBitmap: Bitmap? = null
    private var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnLogin = findViewById(R.id.btn_login)
        btnSave = findViewById(R.id.btn_save)
        tvStatus = findViewById(R.id.tv_status)
        tvLog = findViewById(R.id.tv_log)
        tvCookie = findViewById(R.id.tv_cookie)
        ivQr = findViewById(R.id.iv_qr)
        svLog = findViewById(R.id.sv_log)
        tvLog.movementMethod = ScrollingMovementMethod()

        btnLogin.setOnClickListener { startLogin() }
        btnSave.setOnClickListener { saveQrToGallery() }
    }

    private fun startLogin() {
        loginJob?.cancel()
        tvLog.text = ""
        tvCookie.text = ""
        ivQr.setImageDrawable(null)
        qrBitmap = null
        btnSave.isEnabled = false
        btnLogin.isEnabled = false

        loginJob = lifecycleScope.launch {
            try {
                runFlow()
            } catch (e: Exception) {
                log("出错: ${e.message}")
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) { btnLogin.isEnabled = true }
            }
        }
    }

    private suspend fun runFlow() = withContext(Dispatchers.IO) {
        val qq = QQLogin { line -> log(line) }

        setStatus("正在加载...")
        val loginSig = qq.fetchLoginSig()

        setStatus("拉取二维码...")
        val (bitmap, qrsig) = qq.fetchQrCode()
        qrBitmap = bitmap
        withContext(Dispatchers.Main) {
            ivQr.setImageBitmap(bitmap)
            btnSave.isEnabled = true
        }

        setStatus("请用手机QQ扫码（点上方按钮可保存到相册）")
        log("[3] 开始轮询扫码状态...")

        var i = 0
        while (true) {
            i++
            val r = qq.pollStatus(qrsig, loginSig)
            when (r.code) {
                "0" -> {
                    log("    登录成功: ${r.msg} (${r.nick})")
                    setStatus("登录成功！正在获取Cookie...")
                    qq.followCheckSig(r.url)

                    val keys = qq.getKeyCookies()
                    val all = qq.getAllCookies()

                    log("[5] === 关键 Cookie ===")
                    keys.forEach { (k, v) -> log("    $k = ${v.take(40)}") }

                    withContext(Dispatchers.Main) {
                        tvCookie.text = all
                    }
                    setStatus("完成！Cookie 已显示在下方")
                    return@withContext
                }
                "65" -> { setStatus("二维码已失效"); return@withContext }
                "67" -> { setStatus("[$i] 已扫码，等待手机确认..."); log("    [$i] code=67 已扫码") }
                "66" -> { setStatus("[$i] 等待扫码..."); if (i % 5 == 1) log("    [$i] code=66 等待扫码") }
                "68" -> { setStatus("用户取消登录"); return@withContext }
                else -> log("    [$i] code=${r.code} msg=${r.msg}")
            }
            delay(2000)
        }
    }

    private fun saveQrToGallery() {
        val bm = qrBitmap ?: return
        try {
            val name = "rose_qr_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } else null

            if (uri != null) {
                contentResolver.openOutputStream(uri).use { os: OutputStream? ->
                    os?.let { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                Toast.makeText(this, "已保存到相册: $name", Toast.LENGTH_SHORT).show()
                log("    二维码已保存到相册")
            } else {
                Toast.makeText(this, "保存失败（仅支持 Android 10+）", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
            log("    保存失败: ${e.message}")
        }
    }

    private fun log(text: String) {
        runOnUiThread {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            tvLog.append("[$ts] $text\n")
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private suspend fun setStatus(text: String) = withContext(Dispatchers.Main) {
        tvStatus.text = text
    }
}
