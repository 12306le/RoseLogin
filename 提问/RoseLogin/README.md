# 玫瑰小镇 QQ 扫码登录测试 App

## 功能

- 点击「登录」→ 自动拉取 QQ 二维码并显示
- 点击「保存到相册」→ 保存二维码 PNG
- 切换到 QQ 扫一扫 → 从相册识别
- App 后台自动轮询，扫码成功后显示完整 Cookie

## 构建

用 Android Studio 打开 `RoseLogin` 目录，Sync Gradle，Run 即可。

最低 Android 版本: 7.0 (API 24)

## 项目结构

```
app/src/main/java/com/rose/login/
├── QQLogin.kt       ← QQ 扫码协议（纯 OkHttp，无 WebView）
└── MainActivity.kt  ← UI + 流程控制
```

## 协议流程

1. `fetchLoginSig()` → GET xlogin → 拿 pt_login_sig cookie
2. `fetchQrCode()` → GET ptqrshow → 拿 PNG + qrsig cookie
3. `pollStatus()` → GET ptqrlogin → 每2秒轮询，等待扫码
4. `followCheckSig()` → 跟随重定向 → 拿 p_uin/p_skey 等 cookie
5. 显示所有 cookie → 完成

## 注意

- 二维码有效期约 60 秒，过期后需重新点击登录
- 后台轮询在用户切到 QQ 时仍在运行
- Cookie 有效期较长（数天），不需要频繁重新登录
