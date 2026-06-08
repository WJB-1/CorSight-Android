# 行前预览接口联调文档

> 本文档用于指导后端开发与 Android 客户端「行前预览」功能进行对接。

---

## 一、后端需实现的接口

### 基本信息

| 项目 | 内容 |
|------|------|
| 请求方法 | `POST` |
| 请求路径 | `/api/trip/preview` |
| Content-Type | `application/json` |

### 请求参数（Request Body）

```json
{
  "origin": {
    "latitude": 39.9042,
    "longitude": 116.4074
  },
  "destination": {
    "latitude": 39.9156,
    "longitude": 116.4112
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `origin.latitude` | `double` | 用户当前位置纬度（WGS-84 或 GCJ-02） |
| `origin.longitude` | `double` | 用户当前位置经度 |
| `destination.latitude` | `double` | 目的地纬度 |
| `destination.longitude` | `double` | 目的地经度 |

### 响应格式（Response）

**成功示例（HTTP 200）：**

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "previewId": "preview_20250428_001",
    "summary": "全程约 1.2km，预计步行 15 分钟，途径 2 个路口",
    "riskPoints": [
      {
        "type": "construction",
        "description": "前方 300m 道路施工，建议绕行"
      }
    ]
  }
}
```

**失败示例（HTTP 非 200 或业务错误）：**

```json
{
  "code": 1001,
  "message": "参数错误：经纬度超出有效范围",
  "data": null
}
```

> **注意**：Android 端目前只判断 HTTP 状态码是否 `isSuccessful()`（即 200~299），响应体的 `code` 字段由业务层自行约定解析。

---

## 二、Android 端配置后端地址

打开 [`app/src/main/java/com/example/voicenavigation/network/TripPreviewService.java`](../app/src/main/java/com/example/voicenavigation/network/TripPreviewService.java)，修改第 24 行的 `DEFAULT_BASE_URL`：

```java
public static final String DEFAULT_BASE_URL = "https://your-backend-domain.com";
```

常用配置示例：

| 环境 | 地址示例 |
|------|----------|
| 生产环境 | `"https://api.example.com"` |
| 模拟器调试本机后端 | `"http://10.0.2.2:8080"` |
| 真机局域网调试 | `"http://192.168.1.x:8080"` |

也可以在运行时动态切换：

```java
tripPreviewService.setBaseUrl("https://api.example.com");
```

---

## 三、Android 端发起请求的代码示例

在 [`MainActivity.java`](../app/src/main/java/com/example/voicenavigation/MainActivity.java) 的 `sendTripPreview()` 方法中已集成好调用逻辑。若需要在其他地方手动调用：

```java
TripPreviewService service = new TripPreviewService("https://your-backend-domain.com");

service.sendPreviewRequest(
    39.9042,   // 起点纬度
    116.4074,  // 起点经度
    39.9156,   // 终点纬度
    116.4112,  // 终点经度
    new TripPreviewService.PreviewCallback() {
        @Override
        public void onSuccess(String response) {
            // 在主线程执行，可直接更新 UI
            Log.d("Preview", "后端返回: " + response);
            // TODO: 解析 JSON，展示预览内容
        }

        @Override
        public void onError(String error) {
            // 网络失败、超时、服务器 500 等都会进这里
            Log.e("Preview", "请求失败: " + error);
        }
    }
);
```

---

## 四、网络与安全配置

### 1. HTTPS 自签名证书

若后端使用自签名证书，OkHttp 默认会拒绝连接。需要在 [`TripPreviewService.java`](../app/src/main/java/com/example/voicenavigation/network/TripPreviewService.java) 的 `OkHttpClient` 初始化处添加信任所有证书的逻辑（**仅调试使用，生产环境请勿如此配置**）。

### 2. Android 9+ 明文传输（HTTP）

若后端地址是 `http://` 而非 `https://`，需要在 [`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) 的 `<application>` 标签中添加：

```xml
android:usesCleartextTraffic="true"
```

---

## 五、调试建议

| 场景 | 操作建议 |
|------|----------|
| 验证后端接口是否通 | 先用 Postman / curl 手动 POST 到 `/api/trip/preview`，确认返回 200 |
| 查看 Android 请求日志 | 在 Android Studio 的 Logcat 中过滤 `TripPreviewService`，可看到完整请求体和响应体 |
| 模拟器访问本机后端 | 使用 `http://10.0.2.2:端口号`，不要用 `127.0.0.1` |
| 真机访问局域网后端 | 确保手机和电脑在同一 WiFi，使用电脑局域网 IP |

---

## 六、相关文件索引

| 文件 | 说明 |
|------|------|
| [`TripPreviewService.java`](../app/src/main/java/com/example/voicenavigation/network/TripPreviewService.java) | 行前预览网络请求封装 |
| [`MainActivity.java`](../app/src/main/java/com/example/voicenavigation/MainActivity.java) | 按钮点击事件与调用入口 |
| [`activity_main.xml`](../app/src/main/res/layout/activity_main.xml) | 「行前预览」按钮布局 |
| [`strings.xml`](../app/src/main/res/values/strings.xml) | 相关提示文字资源 |
| [`build.gradle`](../app/build.gradle) | OkHttp 依赖声明 |

---

*文档版本：v1.0 | 生成日期：2026-04-28*
