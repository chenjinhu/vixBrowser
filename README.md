# vixBrowser

一个可以直接用 Android Studio 打开的独立 Java WebView 浏览器工程，应用名称为 `vixBrowser`，包名为 `com.vixmobi.browser`。

内置 HTTP 自动化接口，覆盖跳转 URL、后退、前进、刷新、清除缓存、执行 JS 和查询当前页面加载状态。
完整协议、curl 和 Python 示例见 [HTTP_API.md](HTTP_API.md)。

- 支持显式 Intent 的字符串参数 `url`。
- 支持自定义 `Referer`、UA 和网页 JS 读取的 `history.length`，可通过 HTTP、Intent 或底部「接口 → 浏览器设置」设置，配置重启后保留。
- history.length 通过页面开始脚本覆盖显示值，真实历史和前进后退行为保留。
- 支持 `ACTION_VIEW` + `Intent.data`，注册 HTTP / HTTPS 链接处理。
- Activity 使用 `singleTask`，再次启动时通过 `onNewIntent()` 打开新的 URL。
- 包含地址栏、后退、前进、刷新、加载进度和失败重试；系统返回键优先返回网页历史。
- 支持 JavaScript、DOM Storage、Cookie；旋转屏幕时保存、恢复网页历史。
- 支持 HTTP（包括局域网和开发服务器）及 HTTPS；拒绝本地文件、`content:` 和非网页协议，HTTPS 证书错误会取消加载。
- 仅 debug 构建开启 WebView 调试，可使用桌面 Chrome 的 `chrome://inspect`。

## 构建和安装

环境：JDK 17、Android SDK Platform 35、Build Tools 35.0.0。最低 Android 6.0（API 23）。
工具链：Android Gradle Plugin 8.9.0、Gradle 8.11.1，兼容性参见 [Android 官方说明](https://developer.android.com/build/releases/agp-8-9-0-release-notes)。

Android Studio 选择 **Open**，打开本目录后等待 Gradle 同步，再运行 `app`。
也可以配置 `ANDROID_HOME`，或在本目录的 `local.properties` 中设置 `sdk.dir=/你的/Android/sdk`，然后执行：

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 使用 `gradlew.bat`。首次构建需要联网下载 Gradle 和构建依赖。

Release 构建读取工程根目录的 `keystore.properties`，格式见 `keystore.properties.example`。`storeFile` 相对于工程根目录；本机生成的签名存放在 `signing/vixBrowser-release.p12`，密码在 `keystore.properties`，两者均已排除在源码包和 Git 之外。保留并单独备份这两个文件，后续版本沿用同一签名。

```bash
./gradlew testReleaseUnitTest lintRelease assembleRelease
```

配置签名后的产物为 `app/build/outputs/apk/release/app-release.apk`；未提供签名配置时生成 `app-release-unsigned.apk`，需签名后才能安装。Release 使用独立签名，无法直接覆盖 Debug 签名的安装包。Release 关闭应用调试和 WebView 调试，HTTP 接口仍可正常调用。

## HTTP 自动化调用

安装 APK 后，在本目录执行：

```bash
# 自动启动应用并配置 adb forward。
python3 tools/browserctl.py setup
python3 tools/browserctl.py navigate 'https://example.com' --wait
python3 tools/browserctl.py js 'document.title'
python3 tools/browserctl.py back
python3 tools/browserctl.py forward
python3 tools/browserctl.py refresh --wait
python3 tools/browserctl.py clear-cache
python3 tools/browserctl.py state
python3 tools/browserctl.py loading
python3 tools/browserctl.py referer 'https://source.example/'
python3 tools/browserctl.py navigate 'https://example.com' --referer 'https://source.example/' --wait
python3 tools/browserctl.py referer --clear
python3 tools/browserctl.py ua 'Mozilla/5.0 vixBrowser/1.4'
python3 tools/browserctl.py history-length 7
python3 tools/browserctl.py settings '{"ua":"vixBrowser/1.4","history_length":7}'
python3 tools/browserctl.py navigate 'https://example.com' --ua 'vixBrowser/1.4' --history-length 7 --wait
python3 tools/browserctl.py ua --clear
python3 tools/browserctl.py history-length --clear
```

多设备时，在子命令前增加 `--serial 设备序列号`。脚本仅依赖 Python 3.7+ 标准库。
电脑的 8765 端口被占用时，可在子命令前加 `--port 18765` 使用其他转发端口。

服务监听设备 `127.0.0.1:8765`，通过 ADB 转发到电脑，所有接口均无需认证。应用底部的「接口」按钮可查看地址和转发命令。debug 和 release APK 的调用方式相同，脚本不需要读取应用私有文件。

`GET /api/loading` 返回 `status`（`idle` / `loading` / `complete` / `error`）、`loading`、`progress`（0～100）、当前 URL、标题和 `page_error`。轮询 `status` 判断主页面加载结果；SPA 的异步业务状态仍可通过 JS 查询。完整响应见 [加载状态接口说明](HTTP_API.md#获取页面加载状态)。

应用进程被系统结束或被强制停止后，需要先重新启动应用，HTTP 服务才会恢复。`browserctl.py` 在默认 ADB 模式下会自动启动应用。

## 使用 ADB 启动

显式启动，使用 `url` 参数：

```bash
adb shell am start \
  -n com.vixmobi.browser/.MainActivity \
  --es url 'https://example.com'
```

使用 `ACTION_VIEW` 及 URI，并指定本应用处理：

```bash
adb shell am start \
  -a android.intent.action.VIEW \
  -d 'https://example.com' \
  -p com.vixmobi.browser
```

URL 含 `&` 等 shell 特殊字符时，须把设备端的命令作为整体引用，保留 URL 的内层引号：

```bash
adb shell "am start -n com.vixmobi.browser/.MainActivity --es url 'https://example.com/search?q=android&lang=zh'"
```

未指定包名的普通网页 Intent 会按照系统默认浏览器设置进行分发；如果要确保由本应用打开，请指定包名或组件。

自定义 Referer：

```bash
adb shell am start \
  -n com.vixmobi.browser/.MainActivity \
  --es url 'https://example.com' \
  --es referer 'https://source.example/'
```

`referer` 为完整 HTTP / HTTPS URL；不传时沿用已保存的配置，空字符串清除配置。自定义值用于地址栏、Intent、HTTP 打开网页和主动刷新；页面内资源、跳转、表单、前后历史及来源裁剪按 WebView 原生策略处理，详情见 [HTTP_API.md](HTTP_API.md#自定义-referer)。

自定义 UA 和 history.length：

```bash
adb shell am start \
  -n com.vixmobi.browser/.MainActivity \
  --es url 'https://example.com' \
  --es ua 'Mozilla/5.0 vixBrowser/1.4' \
  --ei history_length 7
```

`ua` 同时设置 HTTP User-Agent 和 `navigator.userAgent`；传空字符串恢复系统默认。`history_length` 为 0～2147483647 的整数，仅覆盖 JS 可见值；通过 `--es history_length ''` 清除配置后恢复真实值。两项也可以和 `referer` 一起传入，所有参数校验通过才保存。history.length 需要 WebView 支持文档开始脚本，接口会报告是否支持；不支持时设置返回明确错误，不使用延迟注入。

## 从其他 Android 应用启动

Java，显式 Intent：

```java
Intent intent = new Intent();
intent.setClassName(
        "com.vixmobi.browser",
        "com.vixmobi.browser.MainActivity");
intent.putExtra("url", "https://example.com/page?id=123");
// 从 Service、Application 等非 Activity Context 启动时需要此 flag。
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
context.startActivity(intent);
```

Kotlin，使用 `ACTION_VIEW`：

```kotlin
val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
    .setPackage("com.vixmobi.browser")
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
context.startActivity(intent)
```

调用方需要先安装本应用。若同时提供 `url` extra 和 `Intent.data`，优先使用 `url`；显式传入空值或无效值会提示错误，不回退到 data。没有 URL 时展示欢迎页；已有页面时不改变当前页面。

地址支持完整 HTTP / HTTPS URL；例如 `example.com` 会自动补全为 HTTPS。带端口的本地地址请传完整协议，例如 `http://192.168.1.10:8080/`。编码后的路径、查询参数和 fragment 会原样保留。域名使用 ASCII 或 Punycode 格式。

## 验证

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

单元测试覆盖 URL 参数的编码保留、协议归一化、开发服务器地址，以及无效 URL 和非网页协议的拒绝。安装后连续执行两次上述启动命令、传入不同网址，即可验证已有 Activity 接收新 Intent。

HTTP 协议单元测试覆盖无认证 GET / POST 请求、Origin 限制、UTF-8、请求长度和异常请求处理。
设置校验测试覆盖 Referer / UA 的换行注入、长度和格式，以及 history.length 的整数范围。手机联调检查服务器实际收到的 UA、首个页面脚本和 iframe 读取的 history.length，并验证真实前进后退、重启持久化和恢复默认；结束时恢复测试前的设置。
加载状态联调使用受控延迟页面验证加载中与完成切换，并检查空闲、重定向、错误和恢复，以及两个查询接口返回状态一致。
安装 debug APK 后还可以在测试设备上执行完整联调（使用临时网页测试所有接口及两种 Intent 入口）：

```bash
python3 tools/smoke_test.py --serial emulator-5580
```

当前工程提供单窗口网页浏览；文件上传、下载管理、相机/定位授权和外部 App 协议跳转未接入。

WebView 接入方式参考 [Android WebView 官方文档](https://developer.android.com/develop/ui/views/layout/webapps/webview)。
