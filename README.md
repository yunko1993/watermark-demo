# watermark-demo

基于 **Java 8 + Spring Boot + MinIO** 的 PDF / Word 文件水印示例，用于验证“上传原件、下载时添加水印、保持原格式”的服务端处理流程。

支持 PDF、DOCX、DOC，以及通过内容校验的 DOC 兼容 WPS 文件。水印内容为 `添加水印-yyyy-MM-dd`，日期在每次请求时按北京时间生成。前端位于 `src/main/resources/static`，无需 Node.js 或前端构建。

## 功能与支持范围

| 上传格式 | 下载格式 | 实现组件 | 水印形式 | 浏览器预览 |
| --- | --- | --- | --- | --- |
| `.pdf` | `.pdf` | Apache PDFBox | 各页斜向平铺文字 | 支持，依赖浏览器 PDF 阅读器 |
| `.docx` | `.docx` | Apache POI XWPF | 页眉浮动文字，3 列 × 4 行 | 下载后用 Word / WPS 查看 |
| `.doc` | `.doc` | Aspose.Words / Free Spire.Doc 可选 | 页眉 WordArt，3 列 × 4 行 | 下载后用 Word / WPS 查看 |
| `.wps` | `.wps` | Aspose.Words / Free Spire.Doc 可选 | 页眉 WordArt，3 列 × 4 行 | 下载后用 WPS 查看 |

- **保持输入格式**：下载流程不转换为 PDF，也不通过修改后缀伪装格式。
- **保留 MinIO 原件**：只在下载时处理副本，不将水印结果覆盖回存储桶。
- **文件校验**：单文件最大 20 MB；校验扩展名及实际内容，拒绝空文件和无法解析的文件。
- **独立运行**：无需数据库、登录系统、Microsoft Office、LibreOffice 或服务端 WPS 进程。

> `.wps` 是扩展名，不能单凭扩展名判断内部格式。当前实现校验 OLE2 容器及 `WordDocument` 数据流，已验证的 WPS 样本属于 Word 97–2003 二进制兼容格式。尚不承诺所有历史 WPS 私有格式均可处理。

## 快速启动

### 1. 环境准备

- JDK 8 或更高版本；项目按 Java 8 编译，本地运行验证使用 JDK 17。
- Maven 3.6 或更高版本。
- 可访问的 MinIO 服务，或本地 MinIO 可执行文件。
- 首次构建需要访问 Maven 仓库，包括 e-iceblue 官方仓库。

```bash
git clone https://github.com/yunko1993/watermark-demo.git
cd watermark-demo
```

### 2. 启动 MinIO

已有 MinIO 可直接跳到下一步，配置其地址、账号和桶名即可。

**Windows / PowerShell**：

```powershell
# 将路径替换为本机 MinIO 可执行文件的位置
powershell -ExecutionPolicy Bypass -File .\start-minio.ps1 -MinioExe 'C:\tools\minio.exe'
```

脚本默认程序路径为 `C:\develop\minio.exe`，数据保存到项目内 `.local/minio-data`。也可使用 `launch-minio.ps1` 在后台启动；它默认使用上述程序路径，并打开 MinIO 管理控制台。

**Linux / 银河麒麟**（已准备与 CPU 架构匹配的 MinIO 程序）：

```bash
mkdir -p .local/minio-data
export MINIO_ROOT_USER=minioadmin
export MINIO_ROOT_PASSWORD=minioadmin
minio server .local/minio-data \
  --address 127.0.0.1:9000 \
  --console-address 127.0.0.1:9001
```

默认 MinIO API 为 `http://127.0.0.1:9000`，控制台为 `http://127.0.0.1:9001`。`minioadmin / minioadmin` 仅为本地演示默认账号。

### 3. 启动应用

在另一个终端进入项目目录：

```bash
mvn spring-boot:run
```

Windows 也可以运行 `powershell -ExecutionPolicy Bypass -File .\start.ps1`，或在 IDE 中启动 `WatermarkApplication.main`。

打开 **[http://127.0.0.1:8088](http://127.0.0.1:8088)**：

1. 选择 PDF / DOCX / DOC / WPS 原件，点击“上传到 MinIO”。首次上传会自动创建配置的存储桶。
2. PDF 可以点击“预览水印版”；DOC/WPS 分别点击“Aspose 下载”或“Spire 下载”进行对比。
3. 用 Word / WPS 的打印布局打开下载文件，检查平铺水印与原文排版。
4. 点击“对比原件”：PDF 在页面预览，Word 文件下载原件。

同一份 MinIO 原件可以多次下载，日期会重新生成。若上传的文件本身已经带水印，当前实现会保留原有水印并追加新水印；验证时建议使用未加水印的原件。

### 4. 打包运行

```bash
mvn clean package
java -jar target/watermark-demo-0.0.1-SNAPSHOT.jar
```

## 配置

默认配置见 [`application.properties`](src/main/resources/application.properties)。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `SERVER_PORT` | `8088` | 应用端口 |
| `MINIO_ENDPOINT` | `http://127.0.0.1:9000` | MinIO API 地址 |
| `MINIO_ACCESS_KEY` | `minioadmin` | 应用访问账号 |
| `MINIO_SECRET_KEY` | `minioadmin` | 应用访问密码 |
| `MINIO_BUCKET` | `watermark-demo` | 原件存储桶 |

也可在项目根目录创建 `application-local.properties`，该文件已被 Git 忽略：

```properties
minio.endpoint=http://127.0.0.1:9000
minio.access-key=your-access-key
minio.secret-key=your-secret-key
minio.bucket=watermark-demo
```

应用默认仅监听 `127.0.0.1`。需要局域网访问时，可使用启动参数 `--server.address=0.0.0.0`；当前 Demo 没有登录鉴权或对象访问授权，应在接入业务系统后由业务侧补齐。

### Maven 镜像注意事项

Free Spire.Doc 依赖来自 `pom.xml` 中的官方仓库：

```text
https://repo.e-iceblue.com/nexus/content/groups/public/
```

Aspose 对比实现使用本地 `lib/aspose-words-24.01-jdk17-jie.jar`，该文件被 `.gitignore` 排除，不随仓库提交。部署或迁移项目前需要自行提供对应 JAR，并确认其来源和授权。

如果本机 `settings.xml` 使用 `<mirrorOf>*</mirrorOf>`，可能导致该依赖被转发到没有收录它的镜像。可让镜像排除仓库 ID `e-iceblue`：

```xml
<mirrorOf>*,!e-iceblue</mirrorOf>
```

企业网络也可以将官方依赖同步到内部 Maven 仓库。

## 水印实现与颜色调整

| 类型 | 代码入口 | 当前样式 |
| --- | --- | --- |
| PDF | [`PdfWatermarkUtil`](src/main/java/com/example/watermark/util/PdfWatermarkUtil.java) | 深灰色，10% 不透明度，30pt，45° 平铺 |
| DOCX | [`WordWatermarkUtil`](src/main/java/com/example/watermark/util/WordWatermarkUtil.java) | VML `#808080`，12% 不透明度，45°，3 × 4 |
| DOC / WPS（Aspose） | [`AsposeLegacyWordWatermarkUtil`](src/main/java/com/example/watermark/util/AsposeLegacyWordWatermarkUtil.java) | 实色浅灰 `#F0F0F0`，45°，3 × 4 |
| DOC / WPS（Spire） | [`LegacyWordWatermarkUtil`](src/main/java/com/example/watermark/util/LegacyWordWatermarkUtil.java) | 实色浅灰 `#F0F0F0`，45°，3 × 4 |

DOC/WPS 的颜色由 `WATERMARK_COLOR` 常量控制：

```java
private static final Color WATERMARK_COLOR = new Color(240, 240, 240);
```

RGB 三个值相等时表示灰度，数值越大越接近白色，例如 `230` 比 `240` 更深。修改后重新编译、重启，再下载水印版即可。填充与轮廓使用同一颜色，避免出现深色描边。

DOCX 使用相对页面的绝对坐标放置形状；同时设置 `center` 与 margin 偏移会导致部分 WPS 版本把多个形状叠在页面中心。代码按节处理默认、首页和偶数页页眉，共享页眉只追加一次。当前 Word 网格为固定坐标，特殊纸张、横向页面和复杂版式仍需要额外验证。

## 接口说明

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/status` | 当前日期水印、MinIO 连通状态及存储桶状态 |
| `POST` | `/api/files` | multipart 字段 `file`，上传原件并返回 `objectName` |
| `GET` | `/api/files/download?objectName=...` | 下载保持原格式的水印副本 |
| `GET` | `/api/files/download/aspose?objectName=...` | DOC/WPS 使用 Aspose 添加水印 |
| `GET` | `/api/files/download/spire?objectName=...` | DOC/WPS 使用 Free Spire.Doc 添加水印 |
| `GET` | 同上加 `original=true` | 获取原件 |
| `GET` | 同上加 `preview=true` | PDF 内联预览；Word 仍按附件下载 |

上传示例（Windows PowerShell 请将 `curl` 写为 `curl.exe`）：

```bash
curl -F 'file=@sample.doc' http://127.0.0.1:8088/api/files
```

返回示例：

```json
{
  "objectName": "<uuid>/sample.doc",
  "watermark": "添加水印-2026-09-21"
}
```

使用返回的完整对象名下载：

```bash
curl -G http://127.0.0.1:8088/api/files/download \
  --data-urlencode 'objectName=<uuid>/sample.doc' \
  -o watermarked-sample.doc
```

上传、下载异常以 JSON 的 `message` 字段返回。20 MB 限制不等于处理过程只占用 20 MB 内存；文档解析和输出会占用额外资源。

## 验证结果与部署边界

- 自动化测试覆盖 PDF 中文水印及旋转/裁剪页、DOCX 页眉继承与平铺坐标、DOC/WPS 内容校验和读写、MinIO 下载不回写原件。
- 本地 WPS 12.1 已验证 DOCX 平铺；提供的 DOC 与 DOC 兼容 WPS 样本已完成水印生成、打开及人工查看。
- Java 服务端使用文档库直接处理，无须安装 Office/WPS；本地办公软件只用于查看结果。
- **银河麒麟 V3 尚未实机验证**。交付前需确认目标机 CPU 架构、JDK、字体及办公软件版本，并用实际业务文件验证页数、表格、图片、页眉页脚和换行。
- Word 水印属于可编辑文档中的形状，可以被删除，不能作为不可删除的防篡改机制。
- 宏文档、模板、其他办公格式不在当前支持范围；不要仅修改扩展名后上传。

运行测试不需要启动 MinIO：

```bash
mvn test
```

更多实现说明见 [`docs/word-watermark-feasibility.md`](docs/word-watermark-feasibility.md)。

## 依赖与第三方许可

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| Spring Boot | 2.7.18 | HTTP 接口、静态页面、配置 |
| Apache PDFBox | 2.0.30 | PDF 水印 |
| Apache POI | 5.4.1 | DOCX 水印及 OLE2 校验 |
| Free Spire.Doc | 14.3.1 | DOC/WPS 读写与 WordArt |
| Aspose.Words | 24.1，本地 JAR | DOC/WPS 对比处理与 WordArt |
| MinIO Java SDK | 8.2.2 | 原件存取 |

**Free Spire.Doc 是受限免费、非开源组件**。官方说明免费版每个文档最多处理 **500 个段落、25 张表格**；超限或正式交付需要评估相应商业版本及授权。仓库不包含该 SDK 的 JAR，构建时通过官方 Maven 仓库获取。参见 [官方免费版说明](https://www.e-iceblue.com/Introduce/free-doc-for-java.html)。

PDF 使用仓库内文泉驿正黑字体；其版权、GPL v2 及字体嵌入例外说明见 [`src/main/resources/fonts`](src/main/resources/fonts)。依赖及字体各自遵循原作者的许可。

本仓库仅包含演示代码、文档及所需字体，不包含业务测试原件、MinIO 数据、构建产物或本机配置。
