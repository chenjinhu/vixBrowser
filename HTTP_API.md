# vixBrowser HTTP 自动化接口

服务地址：设备上的 `http://127.0.0.1:8765`。启动浏览器后，电脑执行 `adb forward tcp:8765 tcp:8765`，即可访问电脑的同一地址。多台设备可以使用不同的电脑端口，设备端仍为 8765。

所有接口均无需认证，转发后可直接发送 HTTP 请求。应用底部「接口」按钮可查看地址和转发命令。

debug 和 release APK 使用相同的调用方式。接口不接受带 `Origin` 的浏览器跨域请求，不开放 CORS。

## 接口列表

| 方法 | 路径 | JSON 请求体 | 结果 |
| --- | --- | --- | --- |
| GET | `/api/state` | 无 | 网页状态和当前浏览器配置 |
| GET | `/api/loading` | 无 | 当前页面加载状态、进度、URL、标题及错误 |
| POST | `/api/navigate` | `{"url":"https://example.com","referer":"https://source.example/","ua":"vixBrowser/1.4","history_length":7}` | 开始跳转，返回 HTTP 202；URL 以外的字段可省略 |
| GET / POST | `/api/settings` | POST 示例：`{"ua":"vixBrowser/1.4","history_length":7}` | 读取所有设置或原子更新指定字段 |
| GET / POST | `/api/ua` | POST：`{"ua":"vixBrowser/1.4"}` | 读取或设置 UA；`null` / 空字符串恢复默认 |
| GET / POST | `/api/history-length` | POST：`{"history_length":7}` | 读取或设置 JS history.length；`null` 恢复真实值 |
| GET | `/api/referer` | 无 | 读取已保存的自定义 Referer |
| POST | `/api/referer` | `{"referer":"https://source.example/"}` | 保存 Referer；传 `null` 或空字符串恢复默认 |
| POST | `/api/back` | `{}` | 后退一页，返回 HTTP 202 |
| POST | `/api/forward` | `{}` | 前进一页，返回 HTTP 202 |
| POST | `/api/refresh` | `{}` | 刷新当前网页，返回 HTTP 202 |
| POST | `/api/cache/clear` | `{}` | 清理 WebView 内存及磁盘资源缓存 |
| POST | `/api/js/execute` | `{"script":"document.title"}` | 执行 JS，返回实际结果 |

POST 请求使用 `Content-Type: application/json`、UTF-8 编码及 `Content-Length`，请求体最大 64 KiB。curl、Python requests 和附带的脚本会自动设置长度。

跳转、前进、后退、刷新返回 202 表示已发起操作。轮询 `/api/loading`，直到 `status` 为 `complete`，再执行依赖页面完成加载的脚本；`error` 表示加载失败，可查看 `page_error`。也可以沿用 `/api/state` 的 `loading` 和 `page_error` 字段。

清除缓存独立于 Cookie、DOM Storage 和网页历史；现有登录状态保留。JS 在当前主页面执行，可读写 DOM、调用页面函数，返回字符串、数值、布尔值、对象、数组或 `null`；`undefined` 转为 `null`。不等待 Promise，Promise、循环引用、BigInt 和抛出的异常会返回 `js_error`。需要异步操作时，可先由 JS 启动操作并写入页面变量，再轮询读取该变量。

## curl 示例

```bash
# 多设备时给每条 adb 命令加 -s SERIAL。
adb shell am start -W -n com.vixmobi.browser/.MainActivity
adb forward tcp:8765 tcp:8765

curl --noproxy '*' -sS http://127.0.0.1:8765/api/navigate \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com"}'

curl --noproxy '*' -sS http://127.0.0.1:8765/api/state

curl --noproxy '*' -sS http://127.0.0.1:8765/api/js/execute \
  -H 'Content-Type: application/json' \
  -d '{"script":"({title: document.title, url: location.href})"}'

curl --noproxy '*' -sS http://127.0.0.1:8765/api/back \
  -H 'Content-Type: application/json' -d '{}'
curl --noproxy '*' -sS http://127.0.0.1:8765/api/forward \
  -H 'Content-Type: application/json' -d '{}'
curl --noproxy '*' -sS http://127.0.0.1:8765/api/refresh \
  -H 'Content-Type: application/json' -d '{}'
curl --noproxy '*' -sS http://127.0.0.1:8765/api/cache/clear \
  -H 'Content-Type: application/json' -d '{}'
```

JS 成功响应示例：

```json
{"ok":true,"result":{"title":"Example Domain","url":"https://example.com/"}}
```

状态响应示例：

```json
{
  "ok": true,
  "state": {
    "url": "https://example.com/",
    "title": "Example Domain",
    "loading": false,
    "can_go_back": false,
    "can_go_forward": false,
    "page_error": null,
    "referer": null,
    "ua": null,
    "history_length": null,
    "history_length_supported": true
  }
}
```

状态还包含 `effective_ua`，即 WebView 当前实际使用的 UA 字符串；`ua` 为保存的自定义值，`null` 表示系统默认。`history_length` 是设置的显示值，`null` 表示真实历史值。

## 获取页面加载状态

```bash
curl --noproxy '*' -sS http://127.0.0.1:8765/api/loading
python3 tools/browserctl.py --port 18765 loading
```

成功加载后的响应示例：

```json
{
  "ok": true,
  "status": "complete",
  "loading": false,
  "progress": 100,
  "url": "https://example.com/",
  "title": "Example Domain",
  "page_error": null
}
```

| `status` | 含义 |
| --- | --- |
| `idle` | 尚未打开网页，`loading: false`、`progress: 0`、`url: null` |
| `loading` | 主页面正在加载，`loading: true` |
| `complete` | 主页面已完成加载，`loading: false`、`progress: 100` |
| `error` | 主页面加载失败，`loading: false`，`page_error` 包含错误说明 |

`progress` 是 WebView 报告的 0～100 整数进度，不能单独作为成功标志；失败时也可能达到 100。`ok: true` 表示查询成功，页面是否成功由 `status` 判断。URL 随重定向更新，标题在加载过程中可能仍是上一页标题。接口只支持 GET，无需认证。

完成状态依据主框架的 [onPageFinished 回调](https://developer.android.com/reference/android/webkit/WebViewClient#onPageFinished(android.webkit.WebView,java.lang.String))，进度来自 [onProgressChanged](https://developer.android.com/reference/android/webkit/WebChromeClient#onProgressChanged(android.webkit.WebView,int))。这不保证下一帧已绘制完成，也不等待 SPA 异步数据、后续 fetch/XHR 或页面的业务就绪条件；这些条件可使用 JS 接口继续检查。

`/api/state` 及导航操作返回的 `state` 同时增加 `load_status` 和 `progress` 字段，分别与这里的 `status`、`progress` 一致。Activity 尚未就绪时返回 `503 browser_not_ready`。

## Python 命令行与复用

```bash
python3 tools/browserctl.py --serial emulator-5580 navigate 'https://example.com' --wait
python3 tools/browserctl.py --serial emulator-5580 loading
python3 tools/browserctl.py --serial emulator-5580 js 'document.querySelector("h1").textContent'
python3 tools/browserctl.py --serial emulator-5580 js --file automation.js
python3 tools/browserctl.py --serial emulator-5580 referer 'https://source.example/'
python3 tools/browserctl.py --serial emulator-5580 navigate 'https://example.com' --referer 'https://source.example/' --wait
python3 tools/browserctl.py --serial emulator-5580 referer
python3 tools/browserctl.py --serial emulator-5580 referer --clear
python3 tools/browserctl.py --serial emulator-5580 ua 'vixBrowser/1.4'
python3 tools/browserctl.py --serial emulator-5580 history-length 7
python3 tools/browserctl.py --serial emulator-5580 settings '{"ua":"vixBrowser/1.4","history_length":7}'
python3 tools/browserctl.py --serial emulator-5580 navigate 'https://example.com' --ua 'vixBrowser/1.4' --history-length 7 --wait
python3 tools/browserctl.py --serial emulator-5580 ua --clear
python3 tools/browserctl.py --serial emulator-5580 history-length --clear
```

默认模式会自动启动应用并配置 ADB 转发。已有转发时也可以通过 `--base-url` 直接访问 HTTP 接口，这种模式不执行 ADB 命令：

```bash
python3 tools/browserctl.py --base-url http://127.0.0.1:8765 state
```

调用成功时退出码为 0，失败时为 1，标准输出为 JSON。`--wait` 等待网页加载，默认 30 秒，可通过子命令前的 `--timeout 60` 调整。

也可以在自己的 Python 脚本中直接复用：

```python
import sys
sys.path.insert(0, "tools")
from browserctl import BrowserClient

browser = BrowserClient("http://127.0.0.1:8765")
browser.request("/api/navigate", {"url": "https://example.com"})
browser.wait_for_page(30)
result = browser.request("/api/js/execute", {"script": "document.title"})
print(result["result"])
```

## 自定义 Referer

HTTP 头使用标准拼写 `Referer`，接口字段和 Intent extra 为 `referer`。

```bash
curl --noproxy '*' -sS http://127.0.0.1:8765/api/referer \
  -H 'Content-Type: application/json' \
  -d '{"referer":"https://source.example/path?from=automation"}'

curl --noproxy '*' -sS http://127.0.0.1:8765/api/navigate \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com","referer":"https://source.example/"}'

curl --noproxy '*' -sS http://127.0.0.1:8765/api/referer \
  -H 'Content-Type: application/json' \
  -d '{"referer":null}'
```

配置保存到应用私有设置，进程重启、屏幕旋转及覆盖安装后仍然保留。`GET /api/referer` 返回 `{"ok":true,"referer":"..."}`，默认值为 `null`；`GET /api/state` 也包含 `state.referer`。设置本身不重新加载当前页面，在下次打开或主动刷新时应用。

`/api/navigate` 的 `referer` 可选：省略时沿用已保存的配置，提供时会先校验所有参数，再保存配置并打开页面；无效参数不会修改配置。Intent 的 `--es referer` 具有相同的保存和清除语义。手机上也可点击「接口 → 浏览器设置」修改 Referer、UA 和 history.length。

值必须是完整的 HTTP / HTTPS URL，最长 4096 字符；不接受用户名密码、控制字符或换行。fragment 自动移除，Unicode 路径和查询参数转成百分号编码。空字符串或 JSON `null` 清除自定义值，恢复 WebView 默认来源行为。

实现使用原生 [WebView.loadUrl(url, headers)](https://developer.android.com/reference/android/webkit/WebView#loadUrl(java.lang.String,%20java.util.Map))，为应用主动发起的主页面 GET 请求提供 Referer。自定义或更新 Referer 后的主动刷新会重新发起 GET；未配置自定义值且没有待应用的配置变更时，刷新保留 WebView 原生行为。

网页里的图片、JS、iframe、fetch/XHR、表单提交、链接跳转、重定向和前后历史仍由 WebView 处理。跨来源、HTTPS 降级及网站 Referrer-Policy 可能裁剪或省略 Referer；此配置不是对所有请求头的强制覆盖。Chromium 对传入 Referer 的处理参见 [AwContents 源码](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/android_webview/java/src/org/chromium/android_webview/AwContents.java)。

## 自定义 UA 和 history.length

可以单独设置，也可以在一条请求中同时更新：

```bash
curl --noproxy '*' -sS http://127.0.0.1:8765/api/settings \
  -H 'Content-Type: application/json' \
  -d '{"ua":"Mozilla/5.0 vixBrowser/1.4","history_length":7}'

curl --noproxy '*' -sS http://127.0.0.1:8765/api/ua \
  -H 'Content-Type: application/json' \
  -d '{"ua":"Mozilla/5.0 vixBrowser/1.4"}'

curl --noproxy '*' -sS http://127.0.0.1:8765/api/history-length \
  -H 'Content-Type: application/json' \
  -d '{"history_length":7}'

# 恢复系统 UA 和真实 history.length，保留现有 Referer。
curl --noproxy '*' -sS http://127.0.0.1:8765/api/settings \
  -H 'Content-Type: application/json' \
  -d '{"ua":null,"history_length":null}'
```

`GET /api/settings` 返回：

```json
{
  "ok": true,
  "settings": {
    "referer": null,
    "ua": "vixBrowser/1.4",
    "effective_ua": "vixBrowser/1.4",
    "history_length": 7,
    "history_length_supported": true
  }
}
```

`POST /api/settings` 接受 `referer`、`ua`、`history_length` 的任意非空字段组合。未提供的字段保持原值；先校验所有提供的参数，再一起保存。`/api/navigate` 也接受这三个可选字段，URL 或任意设置无效时不修改配置。配置在进程重启和覆盖安装后保留。

UA 最长 4096 字符，仅接受可打印 ASCII，前后空格会去掉；换行和控制字符会被拒绝。通过原生 [WebSettings.setUserAgentString](https://developer.android.com/reference/android/webkit/WebSettings#setUserAgentString(java.lang.String)) 设置，对 HTTP User-Agent、子资源请求和 `navigator.userAgent` 生效。修改 UA 可能让正在加载的页面重新加载，UA-CH / `navigator.userAgentData` 由 WebView 处理。

`history_length` 为 0～2147483647 的 JSON 整数值；字符串、负数、非整数数值及布尔值被拒绝。它只覆盖 JS 读取的 `history.length`，不添加或删除真实历史记录，`history.pushState`、前进、后退继续按真实历史工作。传 `null` 会恢复真实值。

history 覆盖使用 [WebViewCompat.addDocumentStartJavaScript](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,java.lang.String,java.util.Set))，在新文档的网页脚本之前运行，也应用到新建 iframe。修改会更新当前主页面的属性；已有 iframe 可刷新后应用新值。网页自身后续对该 JS 属性的重新定义仍可能影响读数。

`history_length_supported` 表示 WebView 是否支持文档开始脚本。旧 WebView 不支持时，设置非空 history_length 返回 `409 history_override_unavailable`，配置不变；清除配置仍允许执行。此时需要更新 Android System WebView。

Intent 示例：

```bash
adb shell am start -n com.vixmobi.browser/.MainActivity \
  --es url 'https://example.com' \
  --es ua 'Mozilla/5.0 vixBrowser/1.4' \
  --ei history_length 7
```

`--es ua ''` 和 `--es history_length ''` 分别恢复默认 UA 和真实 history.length。界面入口为「接口 → 浏览器设置」，留空恢复对应项，点击「全部恢复默认」同时清除 Referer、UA 和 history.length 配置。

## 错误与生命周期

错误响应统一包含 `ok: false` 和 `error`，部分带 `message`。

| HTTP 状态码 | 含义 |
| --- | --- |
| 400 | 参数错误；设置分别返回 `invalid_referer` / `invalid_ua` / `invalid_history_length`，JS 抛错为 `js_error` |
| 403 | 请求带浏览器 Origin |
| 404 / 405 | 接口不存在 / HTTP 方法不正确 |
| 409 | 没有网页/相应历史，或 WebView 不支持 history 文档开始覆盖 |
| 411 / 413 / 415 / 431 | 缺少长度 / 请求体过大 / 非 JSON / 请求头过大 |
| 503 | 浏览器 Activity 尚未就绪或已关闭 |
| 504 | WebView 操作在 8 秒内未返回 |

服务随应用进程启动，Activity 重建时由新的页面实例继续处理。应用关闭页面时返回 503；进程被结束后连接会断开，需要重新启动应用。HTTP 接口不申请后台常驻服务。请求超时后不要盲目重试有副作用的 JS，应先查询页面状态。
