package dev.shinsou.kmp.ui.i18n

// R4-6 (docs/architecture/0006): the Simplified Chinese (zh-CN) strings, one file per language.

internal val SimplifiedSyncTranslations = TraditionalSyncTranslations.mapValues { (_, value) ->
    value.toSimplifiedChinese()
}

internal val SimplifiedLongTranslations = mapOf(
    "Downloads, Local source files, extension packages, cookies, passwords and API keys are not synchronized." to
        "下载内容、本机来源文件、扩展安装包、Cookie、密码和 API 密钥不同步。",
    "Cover: {0}" to "封面：{0}",
    "Shinsou X is locked" to "Shinsou X 已锁定",
    "Authenticate to continue." to "请完成身份验证后继续。",
    "Unlock" to "解锁",
    "Title not found" to "找不到作品",
    "This title may have been removed." to "此作品可能已被移除。",
    "Set categories" to "设置分类",
    "Library" to "书库",
    "titles" to "部作品",
    "title(s)" to "部作品",
    "Try a different search or clear the active filters." to "请尝试其他搜索词，或清除当前筛选条件。",
    "Browse sources and add a title to start reading." to "浏览来源并添加作品，即可开始阅读。",
    "Refresh library" to "刷新书库",
    "Change layout" to "更改布局",
    "Continue {0}" to "继续阅读 {0}",
    "Clear search" to "清除搜索",
    "Loading" to "加载中",
    "Cancel" to "取消",
    "Close" to "关闭",
    "Search" to "搜索",
    "Save" to "保存",
    "Delete" to "删除",
    "Remove" to "移除",
    "Back" to "返回",
    "Language" to "语言",
    "System" to "系统默认",
    "English" to "English",
    "繁體中文" to "繁体中文",
    "简体中文" to "简体中文",
    "Changes apply immediately" to "更改会立即应用",
    "Starting screen" to "启动页面",
    "Confirm before closing" to "关闭前确认",
    "Ask before closing the desktop application" to "关闭桌面应用程序前先询问",
    "Date format" to "日期格式",
    "Theme" to "主题",
    "Compact grid" to "紧凑网格",
    "Comfortable grid" to "舒适网格",
    "List" to "列表",
    "Cover only grid" to "仅封面网格",
    "Pager ltr" to "翻页（从左到右）",
    "Pager rtl" to "翻页（从右到左）",
    "Pager vertical" to "垂直翻页",
    "Continuous vertical" to "连续垂直",
    "Free" to "自由",
    "Portrait" to "竖屏",
    "Landscape" to "横屏",
    "Sensor portrait" to "传感器竖屏",
    "Sensor landscape" to "传感器横屏",
    "AMOLED black" to "AMOLED 黑色",
    "Use pure black surfaces in dark mode" to "在深色模式使用纯黑色表面",
    "Relative timestamps" to "相对时间",
    "Show “2h ago” instead of a clock time" to "显示“2 小时前”而非时钟时间",
    "All" to "全部",
    "Check again" to "再次检查",
    "Sync now" to "立即同步",
    "Enabled languages" to "启用的语言",
    "App lock" to "应用锁",
    "Secure screen" to "安全屏幕",
    "Manage categories" to "管理分类",
    "New category" to "新建分类",
    "Rename category" to "重命名分类",
    "Delete {0}?" to "删除“{0}”？",
    "Titles in this category will be moved to the default category." to "此分类中的作品会移至默认分类。",
    "Category name" to "分类名称",
    "Tracking" to "追踪",
    "Log out" to "退出登录",
    "Search manga" to "搜索漫画",
    "Complete login" to "完成登录",
    "Status" to "状态",
    "Progress" to "进度",
    "Update" to "更新",
    "Reading mode" to "阅读模式",
    "Scroll down" to "向下滚动",
    "Turn left" to "向左翻页",
    "Turn right" to "向右翻页",
    "Novel typography" to "小说排版",
    "Font size · {0}" to "字号 · {0}",
    "Line height · {0}%" to "行高 · {0}%",
    "Reading width · {0}" to "阅读栏宽 · {0}",
    "Brightness" to "亮度",
    "Color filter" to "色彩滤镜",
    "Open in browser" to "在浏览器中打开",
    "Return to chapter" to "返回章节",
    "Retry this page" to "重试此页",
    "No chapters" to "没有章节",
    "Show all" to "显示全部",
    "Recent" to "最近",
    "Upcoming" to "即将到来",
    "Clear history" to "清除历史",
    "No expected updates" to "没有预期更新",
    "Download queue is empty" to "下载队列为空",
    "Back up now" to "立即备份",
    "Delete automatic backup?" to "删除自动备份？",
    "Privacy policy" to "隐私政策",
    "No cookies saved" to "没有已保存的 Cookie",
    "Add cookie" to "添加 Cookie",
    "Clear cookies" to "清除 Cookie",
    "Repositories" to "存储库",
    "Add repository" to "添加存储库",
    "No extensions" to "没有扩展",
    "Migrate" to "迁移",
    "Load more" to "加载更多",
    "Reset" to "重置",
    "Apply" to "应用",
    "None" to "无",
    "Ascending" to "升序",
    "Descending" to "降序",
    "Website order" to "网站顺序",
    "Reverse website order" to "反向网站顺序",
    "Device authentication is unavailable on this platform." to "此平台无法使用设备验证。",
    "Secure-screen protection is unavailable on this platform." to "此平台无法使用安全屏幕保护。",
    "Set up a device passcode, PIN, password, or biometric authentication to use app lock." to "请设置设备密码、PIN、密码或生物识别验证，才能使用应用锁。",
    "App lock is unavailable on Desktop because macOS authentication is not implemented." to "桌面版无法使用应用锁，因为尚未实现 macOS 验证。",
    "Secure screen is unavailable on Desktop because macOS does not expose this protection here." to "桌面版无法使用安全屏幕，因为 macOS 未在此提供这项保护。",
    "Checking iCloud Drive availability…" to "正在检查 iCloud Drive 可用性…",
    "A single Shinsou X snapshot will be stored in the app's iCloud Drive container." to "单个 Shinsou X 快照会存储在应用的 iCloud Drive 容器中。",
    "The remote snapshot service is unavailable." to "远程快照服务不可用。",
).toMutableMap().apply {
    // Fill any newly added long-tail key from the Traditional table while
    // retaining the explicit Simplified wording above.
    TraditionalLongTranslations.forEach { (key, value) ->
        if (key !in this) this[key] = value.toSimplifiedChinese()
    }
}

internal fun String.toSimplifiedChinese(): String {
    val source = this
    val converted = buildString(source.length) {
        // Keep the table compact while still covering every long-tail key.
        // The replacement set contains the characters used by the UI;
        // explicit Simplified entries above remain available for wording
        // differences.
        val replacements = mapOf(
        // Common compatibility characters which occur in the long-tail
        // Traditional copy.  Keep this table explicit and deterministic so
        // it works on every KMP target without pulling in a platform
        // conversion library.
        '並' to '并', '併' to '并', '裡' to '里', '裏' to '里', '綫' to '线',
        '臺' to '台', '爲' to '为', '衆' to '众', '啓' to '启', '佈' to '布',
        '妳' to '你', '們' to '们', '實' to '实', '門' to '门', '閒' to '闲',
        '點' to '点', '國' to '国', '會' to '会', '萬' to '万', '說' to '说',
        '過' to '过', '經' to '经', '廣' to '广', '東' to '东', '製' to '制',
        '價' to '价', '貳' to '贰', '參' to '参', '邊' to '边', '讓' to '让',
        '見' to '见', '貝' to '贝', '聲' to '声', '聽' to '听', '話' to '话',
        '順' to '顺',
        '記' to '记', '難' to '难', '雜' to '杂', '靈' to '灵', '歡' to '欢',
        '淨' to '净', '側' to '侧', '傳' to '传', '優' to '优',
        '剛' to '刚', '務' to '务', '區' to '区', '協' to '协', '問' to '问',
        '嗎' to '吗', '圍' to '围', '帳' to '帐', '導' to '导', '徑' to '径',
        '掛' to '挂', '換' to '换', '擊' to '击', '擱' to '搁', '擷' to '撷',
        '棄' to '弃', '欄' to '栏', '測' to '测', '準' to '准', '範' to '范',
        '籤' to '签', '級' to '级', '給' to '给', '綁' to '绑', '縮' to '缩',
        '缺' to '缺', '處' to '处', '衝' to '冲', '規' to '规', '視' to '视',
        '訂' to '订', '訊' to '讯', '評' to '评', '詞' to '词', '該' to '该',
        '詳' to '详', '識' to '识', '護' to '护', '貼' to '贴', '輯' to '辑',
        '載' to '载', '轉' to '转', '錄' to '录', '鑰' to '钥', '閒' to '闲',
        '書' to '书', '庫' to '库', '歷' to '历', '瀏' to '浏', '覽' to '览',
        '擴' to '扩', '套' to '套', '遷' to '迁', '統' to '统', '計' to '计',
        '設' to '设', '備' to '备', '與' to '与', '還' to '还', '關' to '关',
        '於' to '于', '尋' to '寻', '詢' to '询', '篩' to '筛', '選' to '选',
        '儲' to '储', '刪' to '删', '除' to '除', '移' to '移', '試' to '试',
        '項' to '项', '標' to '标', '讀' to '读', '類' to '类', '繼' to '继',
        '續' to '续', '閱' to '阅', '覽' to '览', '章' to '章', '節' to '节',
        '設' to '设', '預' to '预', '設' to '设', '啟' to '启', '動' to '动',
        '畫' to '画', '面' to '面', '應' to '应', '用' to '用', '程' to '程',
        '式' to '式', '詢' to '询', '確' to '确', '認' to '认', '時' to '时',
        '間' to '间', '純' to '纯', '黑' to '黑', '顯' to '显', '示' to '示',
        '為' to '为', '預' to '预', '設' to '设', '網' to '网', '域' to '域',
        '檢' to '检', '查' to '查', '啟' to '启', '用' to '用', '語' to '语',
        '變' to '变', '更' to '更', '即' to '即', '時' to '时', '無' to '无',
        '備' to '备', '份' to '份', '狀' to '状', '態' to '态', '與' to '与',
        '復' to '复', '原' to '原', '載' to '载', '入' to '入', '匯' to '汇',
        '產' to '产', '業' to '业', '網' to '网', '頁' to '页', '錯' to '错',
        '誤' to '误', '開' to '开', '閉' to '闭', '返' to '返', '回' to '回',
        '進' to '进', '行' to '行', '題' to '题', '組' to '组', '隱' to '隐',
        '藏' to '藏', '從' to '从', '這' to '这', '個' to '个', '條' to '条',
        '掃' to '扫', '描' to '描', '譯' to '译', '顏' to '颜', '色' to '色',
        '濾' to '滤', '鏡' to '镜', '亮' to '亮', '度' to '度', '灰' to '灰',
        '階' to '阶', '反' to '反', '轉' to '转', '音' to '音', '量' to '量',
        '鍵' to '键', '垂' to '垂', '直' to '直', '條' to '条', '漫' to '漫',
        '現' to '现', '場' to '场', '總' to '总', '數' to '数', '據' to '据',
        '標' to '标', '題' to '题', '專' to '专', '案' to '案', '聯' to '联',
        '絡' to '络', '隨' to '随', '機' to '机', '種' to '种', '庫' to '库',
        '擇' to '择', '選' to '选', '讀' to '读', '條' to '条', '與' to '与',
        '來' to '来', '請' to '请', '沒' to '没', '稱' to '称', '蹤' to '踪',
        '裝' to '装', '碼' to '码', '鎖' to '锁', '連' to '连', '資' to '资',
        '權' to '权', '結' to '结', '執' to '执', '驗' to '验', '證' to '证',
        '觀' to '观', '體' to '体', '簡' to '简', '橫' to '横', '僅' to '仅',
        '將' to '将', '遞' to '递', '暫' to '暂', '淺' to '浅', '緊' to '紧',
        '湊' to '凑', '單' to '单', '對' to '对', '鐘' to '钟', '減' to '减',
        '檔' to '档', '隊' to '队', '離' to '离', '線' to '线', '圖' to '图',
        '損' to '损', '壞' to '坏', '攜' to '携', '發' to '发', '較' to '较',
        '許' to '许', '擋' to '挡', '議' to '议', '調' to '调', '熱' to '热',
        '註' to '注', '複' to '复', '編' to '编', '號' to '号', '螢' to '萤',
        '後' to '后', '嘗' to '尝',
        )
        for (character in source) append(replacements[character] ?: character)
    }
    // A few terms are conventionally translated as words rather than as
    // independent characters (for example 程式 → 程序 and 佇列 → 队列).
    return converted
        // Character conversion cannot express terminology changes.  Apply
        // the few UI-wide terms after conversion so long sentences read as
        // native Simplified Chinese instead of a character-by-character mix.
        .replace("应用程式", "应用程序")
        .replace("扩充套件", "扩展")
        .replace("擴充套件", "扩展")
        .replace("程式", "程序")
        .replace("設定", "设置")
        .replace("设定", "设置")
        .replace("登入", "登录")
        .replace("帳號", "账号")
        .replace("網路", "网络")
        .replace("网路", "网络")
        .replace("網域", "域名")
        .replace("网域", "域名")
        .replace("頁面", "页面")
        .replace("裝置", "设备")
        .replace("装置", "设备")
        .replace("螢幕", "屏幕")
        .replace("萤幕", "屏幕")
        .replace("檔案", "文件")
        .replace("档案", "文件")
        .replace("資料", "资料")
        .replace("介面", "界面")
        .replace("外掛", "插件")
        .replace("外挂", "插件")
        .replace("權杖", "令牌")
        .replace("权杖", "令牌")
        .replace("匯入", "导入")
        .replace("汇入", "导入")
        .replace("匯出", "导出")
        .replace("汇出", "导出")
        .replace("帳號", "账号")
        .replace("帐号", "账号")
        .replace("佇列", "队列")
}

internal val SimplifiedReaderTranslations = mapOf(
    "Import local content" to "导入本机内容",
    "Images, CBZ, ZIP, TXT or EPUB · stored on this device" to "图片、CBZ、ZIP、TXT 或 EPUB · 存储在本机",
    "TXT and EPUB bodies stay on this device unless you explicitly add them to encrypted sync." to
        "除非你明确加入加密同步，TXT 与 EPUB 正文只会保留在此设备上。",
    "Encrypted TXT/EPUB body sync" to "加密 TXT／EPUB 正文同步",
    "Uploads run only in the background and only when the current rights grant permits SYNC_BLOB." to
        "只有当前权限授予 SYNC_BLOB 时才会在后台上传。",
    "Choose files" to "选择文件",
    "Encrypted chapter body sync" to "加密章节正文同步",
    "Off by default. When enabled, only bodies allowed by the current SYNC_BLOB grant are queued for encrypted background upload." to
        "默认关闭。启用后，只有当前 SYNC_BLOB 授权允许的正文才会排入加密后台上传。",
    "This content is no longer available under the current rights grant." to "当前的内容权限已不允许使用此内容。",
    "Full-text search is unavailable for this content." to "此内容目前无法使用全文搜索。",
    "Search text" to "搜索文字",
    "Speech stopped because it is unavailable or no longer permitted." to "语音朗读无法使用或已不再获得允许，因此已停止。",
    "Speech unavailable" to "语音朗读无法使用",
    "Speaking…" to "正在朗读…",
    "Speak from paragraph" to "从本段开始朗读",
    "Stop" to "停止",
    "Search this book" to "搜索此书",
    "Search is no longer permitted." to "当前的内容权限已不允许搜索。",
    "Find" to "查找",
    "Add note" to "添加笔记",
    "Reader paragraph" to "阅读器段落",
    "Copy is unavailable or not permitted." to "无法复制，或当前的内容权限不允许复制。",
    "Copy" to "复制",
    "{0} note" to "{0} 条笔记",
    "{0} notes" to "{0} 条笔记",
    "Add paragraph note" to "添加段落笔记",
    "Note" to "笔记",
    "The note could not be saved or is no longer permitted." to "无法保存笔记，或当前的内容权限已不允许添加笔记。",
    "The image navigation graph is unavailable." to "图片导航数据无法使用。",
    "Page {0}" to "第 {0} 页",
    "The image resource could not be opened." to "无法打开图片资源。",
    "The EPUB navigation graph is unavailable." to "EPUB 导航数据无法使用。",
    "The EPUB resources could not be opened." to "无法打开 EPUB 资源。",
    "Previous" to "上一节",
    "Next" to "下一节",
    "EPUB rendering is unavailable until this platform supplies its secure browser renderer." to
        "此平台尚未提供安全的浏览器渲染器，因此无法显示 EPUB。",
    "This image representation has not been materialized into reader pages." to
        "此图片内容尚未转换为可供阅读器显示的页面。",
    "Content saved for offline reading." to "内容已保存，可离线阅读。",
    "In queue" to "队列中",
    "Allow" to "允许",
    "Battery optimization may pause downloads while Shinsou X is in the background." to "电池优化可能会在 Shinsou X 位于后台时暂停下载。",
    "Failed to download" to "失败",
    "Retry failed" to "重试失败项目",
    "Remove failed" to "移除失败项目",
    "Remove waiting" to "移除等待中项目",
    "More actions" to "更多操作",
    "{0} chapters · {1} waiting · {2} failed · {3} done" to "{0} 章 · {1} 等待中 · {2} 失败 · {3} 已完成",
    "Remove {0} items from the queue?" to "要从队列移除 {0} 个项目吗？",
    "Downloaded chapters stay on this device." to "已下载的章节仍会保留在此设备上。",
    "Downloading {0} / {1}" to "正在下载 {0} / {1}",
    "Save image" to "保存图片",
    "Image saved" to "图片已保存",
    "Could not save the image" to "无法保存图片",
    "Review plugin permissions" to "检查插件权限",
    "Plugin permissions" to "插件权限",
    "Runs isolated from the app and your other plugins." to "与 App 及其他插件隔离运行。",
    "Connects to" to "会连接到",
    "Downloads images and text from" to "从以下网站下载图片与文字",
    "Always allowed: run the plugin and connect to the sites above." to "始终允许：运行插件并连接到上列网站。",
    "Sensitive permissions" to "敏感权限",
    "Each one is off until you turn it on." to "每一项都需要你开启才会生效。",
    "Lets the plugin ask the app to show this screen." to "让插件请求 App 显示这个界面。",
    "Download from every site under {0}" to "从 {0} 下的所有网站下载",
    "Its images come from {0}, which this plugin's permissions do not allow yet. Switch it on under Browse > Extensions > the plugin > Permissions." to "这一页的图片来自 {0}，此插件的权限尚未允许。请到“浏览 > 扩展 > 该插件 > 权限”开启。",
    "Images and text only, without cookies or your account." to "仅限图片与文字，不带 Cookie 或你的账号。",
    "These permissions apply only to this exact version; you can withdraw them later in the plugin's permissions." to "这些权限只适用于这个确切版本；之后可在插件的「权限」中撤回。",
    "Save permissions" to "保存权限",
    "Developer mode" to "开发者模式",
    "To add this development server, turn on developer mode in Package security and add its address there (this device is always allowed)." to "要添加这个开发服务器，请先在「插件安全」开启开发者模式并添加它的地址（本机始终允许）。",
    "{0} (any port from 1024)" to "{0}（任何 1024 以上的端口）",
    "Now signed by its publisher: confirm the publisher to keep updating." to "已改为由发布者签名：请确认发布者以继续更新。",
    "Confirm publisher" to "确认发布者",
    "Load unsigned plugins from a development server on this device or on the addresses below. Only for testing your own plugins." to "从本机或下方地址的开发服务器加载未签名插件。仅供测试你自己开发的插件。",
    "Development server addresses" to "开发服务器地址",
    "Private network addresses, one per line (for example 192.168.1.20)" to "局域网地址，每行一个（例如 192.168.1.20）",
    "Save addresses" to "保存地址",
    "In development" to "开发中",
    "Unsigned development build: runs only while developer mode is on." to "未签名的开发版本：只在开发者模式开启时运行。",
    "Publisher" to "发布者",
    "No publisher key: this plugin cannot run" to "没有发布者密钥：这个插件无法运行",
    "Publisher fingerprint matched" to "发布者指纹已比对",
    "Trusted on first use (not verified)" to "首次使用时信任（未验证）",
    "Waiting for a fingerprint match" to "等待指纹比对",
    "Publisher key revoked" to "发布者密钥已撤销",
    "Publisher key replaced" to "发布者密钥已更换",
    "Unknown publisher" to "未知的发布者",
    "No sites" to "没有网站",
    "…and {0} more ({1} in total)" to "…还有 {0} 个（共 {1} 个）",
    "Your account name and password will be sent to: {0}" to "你的账号名称与密码会发送到：{0}",
    "Keeps login cookies for its sites and sends them with its requests." to "为它的网站保存登录 Cookie，并随请求发送。",
    "Lets the plugin read those cookies, so it could copy your login." to "让插件读取这些 Cookie，因此它能复制你的登录状态。",
    "Loads its images in a private browser without your cookies, from: {0}" to "在不带你 Cookie 的私密浏览器中加载它的图片，来源：{0}",
    "Lets the plugin ask you to log in." to "让插件请求你登录。",
    "Lets the plugin change your favorites on the site." to "让插件修改你在该网站的收藏。",
    "Lets the plugin open a browser page to pass the site's checks." to "让插件打开浏览器页面以通过网站验证。",
    "{0} was updated and kept the permissions you granted before." to "{0} 已更新，并沿用你先前授予的权限。",
    "Loading permissions…" to "正在加载权限…",
    "Permissions" to "权限",
    "Keep downloading with silent audio" to "在后台以静音音频持续下载",
    "Plays silent audio while downloading in the background, for when iOS gives no background time (such as in LiveContainer). Uses more battery." to "在后台下载时播放静音音频，用于 iOS 不提供后台时间的情况（例如在 LiveContainer 中）。会更耗电。",
    "Downloading {0} titles" to "{0} 部作品下载中",
    "{0} more titles" to "还有 {0} 部作品",
    "Open Shinsou X" to "打开 Shinsou X",
    "Shinsou X could not confirm that no other copy is running, so it did not start. {0}" to "Shinsou X 无法确认没有其他正在运行的副本，因此没有启动。{0}",
    "Shinsou X is already running but is not responding. Close it in Task Manager, then open Shinsou X again." to "Shinsou X 已在运行但没有响应。请在任务管理器中结束它，然后重新打开 Shinsou X。",
    "Downloads continue in the background. Shinsou X quits when they finish." to "下载会在后台继续进行，完成后 Shinsou X 会自动退出。",
    "{0} active · {1} queued · {2} done" to "{0} 个进行中 · {1} 个排队 · {2} 个已完成",
    "The selected chapters are already downloaded or queued." to "所选章节都已下载或在队列中。",
    "{0} chapters added to the download queue." to "已将 {0} 个章节加入下载队列。",
    "Downloading" to "下载中",
    "Extension content" to "扩展内容",
    "Unable to load chapters" to "无法加载章节",
    "No chapters" to "没有章节",
    "This extension did not return any readable units." to "此扩展未返回任何可阅读单元。",
    "Choose a format when this chapter provides more than one." to "如果此章节提供多种格式，请选择一种。",
    "Save offline" to "保存以供离线阅读",
    "Load more" to "加载更多",
    "Choose content format" to "选择内容格式",
    "The operation could not be completed." to "无法完成操作。",
    "Format: {0}" to "格式：{0}",
    "Page {0} / {1}" to "第 {0} / {1} 页",
    "Last read: {0}" to "上次读到：{0}",
)

internal val SimplifiedPortabilityTranslations = TraditionalPortabilityTranslations.mapValues { (_, value) ->
    value.toSimplifiedChinese()
} + mapOf(
    "The sync workspace was left, but the device-local restore rolled back. Retry the same verified archive." to
        "已离开同步工作区，但设备本机还原已回滚。请使用同一份已验证的归档文件重试。",
)

internal val SimplifiedReviewedExtensionTranslations =
    TraditionalReviewedExtensionTranslations.mapValues { (_, value) -> value.toSimplifiedChinese() } + mapOf(
        "Review host permissions" to "审核主机权限",
        "Host event and runtime permissions remain blocked until you approve this exact version and digest." to
            "在你批准这个确切版本和摘要前，主机事件与运行时权限会持续被阻止。",
        "Approve" to "批准",
        "Preparing installation…" to "准备安装中…",
        "Installing…" to "安装中…",
        "Updating…" to "更新中…",
        "Removing…" to "移除中…",
        "Applying permissions…" to "应用权限中…",
        "Updating trust…" to "更新信任状态中…",
        "This extension changed. Review its permissions again." to "此扩展已更改，请重新审核其权限。",
        "This plugin can't run: it isn't from a publisher you trust, or this device has no plugin sandbox." to "无法运行此插件：它不是来自你信任的发布者，或此设备没有插件沙箱。",
    )

internal val SimplifiedChineseStrings = TraditionalChineseStrings.copy(
    library = "书库", updates = "更新", history = "历史", browse = "浏览", more = "更多",
    sources = "来源", extensions = "扩展", migration = "迁移", downloads = "下载", statistics = "统计",
    settings = "设置", backup = "备份与恢复", about = "关于", search = "搜索", searchLibrary = "搜索书库",
    refresh = "刷新", filter = "筛选", sort = "排序", all = "全部", done = "完成", cancel = "取消",
    save = "保存", close = "关闭", delete = "删除", remove = "移除", retry = "重试", share = "分享",
    selectAll = "全选", selected = "项已选择", markRead = "标为已读", markUnread = "标为未读",
    moveToCategory = "移至分类", continueReading = "继续阅读", favorite = "加入书库", unfavorite = "已在书库", myLibrary = "我的收藏库",
    download = "下载", install = "安装", uninstall = "卸载", enable = "启用", disable = "停用",
    chapters = "章节", readerSettings = "阅读器设置", previousChapter = "上一章", nextChapter = "下一章",
    page = "页", clearFilters = "清除筛选", noMatches = "没有匹配项", libraryEmpty = "书库为空",
    readerModeLeftToRight = "翻页（从左到右）", readerModeRightToLeft = "翻页（从右到左）",
    readerModeVertical = "垂直翻页", readerModeWebtoon = "条漫", readerModeContinuousVertical = "连续垂直",
    pageTurnAnimation = "翻页动画", pageTurnAnimationDescription = "切换页面时显示动画",
    noUpdates = "没有最近更新", noHistory = "没有阅读历史", createBackup = "创建备份", restoreBackup = "恢复备份",
    // Explicit Simplified wording wins over the character-converted Traditional fallback.
    translations = SimplifiedSyncTranslations + SimplifiedLongTranslations + SimplifiedSourceFailureTranslations + LibraryContentTypeTranslations.getValue("zh-CN") + SimplifiedReaderTranslations +
        SimplifiedPortabilityTranslations + SimplifiedReviewedExtensionTranslations + SyncV3SimplifiedTranslations + MangaTranslationSimplified + AppUpdateSimplified,
)
