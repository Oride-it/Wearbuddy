package com.wear.wearbuddy.presentation

import androidx.compose.runtime.staticCompositionLocalOf

/** 界面文案（中文 / English）。 */
interface LocalizedStrings {
    val copyTextLabel: String
    val copiedText: String
    val previewHtml: String
    val appName: String
    // 三页
    val tabTasks: String
    val tabProjects: String
    val tabSettings: String
    // 任务页
    val inputHint: String
    val newChat: String
    val connectFirst: String
    val startChat: String
    val emptyChatHint: String
    val jumpToBottom: String
    val historyUnavailable: String
    val emptyTasks: String
    val notLoggedInHint: String
    val loginNow: String
    val statusThinking: String
    val statusExecuting: String
    val statusWaitingInput: String
    val statusDone: String
    val statusFailed: String
    val statusStopped: String
    val send: String
    val stop: String
    val retry: String
    val modelPickerTitle: String
    val modelCloudDefault: String
    val artifacts: String
    val artifactsTitle: String
    val artifactsEmpty: String
    val artifactTapToView: String
    val artifactTapToRetry: String
    val artifactSaved: String
    val askPrompt: String
    val thoughtLabel: String
    val expandThought: String
    val collapseThought: String
    // 云端产物 / 工作区
    val cloudArtifacts: String
    val browseWorkspace: String
    val workspaceTitle: String
    val workspaceEmpty: String
    val workspaceLoadFailed: String
    val workspaceParent: String
    val workspaceUpLevel: String
    // 输入页（发消息）
    val composeTitle: String
    val messageLabel: String
    val messageHint: String
    val chooseFile: String
    val voiceInput: String
    val imageLabel: String
    val paste: String
    val newline: String
    val listenFailed: String
    val micUnavailable: String
    // 项目页
    val addProjectTitle: String
    val addProjectButton: String
    val projectName: String
    val projectCommand: String
    val createProject: String
    val runProject: String
    val deleteProject: String
    val confirmDeleteProject: String
    val emptyProjects: String
    // 设置页
    val profileTitle: String
    val loginTitle: String
    val loginSubtitle: String
    val phonePlaceholder: String
    val codePlaceholder: String
    val getCode: String
    val submitLogin: String
    val logout: String
    val planTitle: String
    val planUnknown: String
    val pointsTitle: String
    val pointsLeft: String
    val todayRequests: String
    val signInToView: String
    val theme: String
    val language: String
    val fontSize: String
    val ttsSetting: String
    val hapticsSetting: String
    val storageSetting: String
    // 会话管理 / Agent / 签到
    val sessionsTitle: String
    val sessionsEmpty: String
    val sessionsHint: String
    val archiveSession: String
    val deleteSession: String
    val confirmDeleteSession: String
    val agentsTitle: String
    val agentsEmpty: String
    val agentEnabled: String
    val agentDisabled: String
    val checkinTitle: String
    val checkinTodayDone: String
    val checkinTodayTodo: String
    val checkinStreak: String
    val checkinDaily: String
    val checkinClaim: String
    val checkinInactive: String
    val days: String
    val themeBlue: String
    val themeGreen: String
    val themePurple: String
    val themeAmber: String
    val langZh: String
    val langEn: String
    val fontSmall: String
    val fontStandard: String
    val fontLarge: String
    val memoryBank: String
    val growthPlan: String
    val cloudTools: String
    val growthLevel: String
    val growthEnergy: String
    val growthProgress: String
    val growthNoTasks: String
    val growthLoadFailed: String
    val growthOpenWebsite: String
    val growthBrowserUnavailable: String
    val growthLocked: String
    val growthUnstarted: String
    val growthInProgress: String
    val growthCompleted: String
    val growthClaimed: String
    val notSupported: String
    // 关于
    val aboutApp: String
    val appIntroTitle: String
    val appIntro: String
    val appLicense: String
    val sourceRepository: String
    val developerList: String
    val developerRole: String
    // 存储
    val storageTitle: String
    val storageCache: String
    val storageData: String
    val clearCache: String
    val cacheCleared: String
    // 通用
    val back: String
    val cancel: String
    val loading: String
    val networkOffline: String
}

private object ZhStrings : LocalizedStrings {
    override val copyTextLabel = "复制文字"
    override val copiedText = "已复制"
    override val previewHtml = "网页预览"
    override val appName = "Wearbuddy"
    override val tabTasks = "会话"
    override val tabProjects = "项目"
    override val tabSettings = "我的"
    override val inputHint = "询问任何事…"
    override val emptyTasks = "还没有会话"
    override val newChat = "新对话"
    override val connectFirst = "先连接模型服务"
    override val startChat = "发送第一条消息"
    override val emptyChatHint = "点击上方新对话，开始第一段对话。"
    override val jumpToBottom = "回到底部"
    override val historyUnavailable = "会话暂未同步，正在重连"
    override val notLoggedInHint = "登录后即可下发云端任务"
    override val loginNow = "立即登录"
    override val statusThinking = "思考中"
    override val statusExecuting = "执行中"
    override val statusWaitingInput = "等待补充"
    override val statusDone = "已完成"
    override val statusFailed = "失败"
    override val statusStopped = "已中断"
    override val send = "发送"
    override val stop = "停止"
    override val retry = "重试"
    override val modelPickerTitle = "选择模型"
    override val modelCloudDefault = "云端默认"
    override val artifacts = "产物文件"
    override val artifactsTitle = "产物中心"
    override val artifactsEmpty = "还没有产物\n完成的任务会在这里出现"
    override val artifactTapToView = "点击查看或下载"
    override val artifactTapToRetry = "点按重试"
    override val artifactSaved = "已保存到手表 Download"
    override val askPrompt = "云端需要你补充信息"
    override val thoughtLabel = "思考过程"
    override val expandThought = "展开思考过程"
    override val collapseThought = "收起思考过程"
    override val cloudArtifacts = "云端产物"
    override val browseWorkspace = "浏览工作区"
    override val workspaceTitle = "工作区目录"
    override val workspaceEmpty = "空目录"
    override val workspaceLoadFailed = "目录读取失败"
    override val workspaceParent = "上一级"
    override val workspaceUpLevel = "已到顶层"
    override val composeTitle = "发消息"
    override val messageLabel = "消息"
    override val messageHint = "输入消息"
    override val chooseFile = "选择文件"
    override val voiceInput = "语音输入"
    override val imageLabel = "图片"
    override val paste = "粘贴"
    override val newline = "换行"
    override val listenFailed = "没听清，请重试"
    override val micUnavailable = "语音输入不可用"
    override val addProjectTitle = "新建项目"
    override val addProjectButton = "添加项目"
    override val projectName = "项目名称"
    override val projectCommand = "命令"
    override val createProject = "创建项目"
    override val runProject = "运行"
    override val deleteProject = "删除"
    override val confirmDeleteProject = "删除该项目？"
    override val emptyProjects = "还没有项目\n点上方「添加项目」创建"
    override val profileTitle = "个人资料"
    override val loginTitle = "登录 WorkBuddy"
    override val loginSubtitle = "手机号 + 短信验证码"
    override val phonePlaceholder = "手机号"
    override val codePlaceholder = "验证码"
    override val getCode = "获取验证码"
    override val submitLogin = "登录"
    override val logout = "退出登录"
    override val planTitle = "版本"
    override val planUnknown = "体验版"
    override val pointsTitle = "积分"
    override val pointsLeft = "剩余"
    override val todayRequests = "今日请求"
    override val signInToView = "登录后查看"
    override val theme = "主题"
    override val language = "语言"
    override val fontSize = "字体大小"
    override val ttsSetting = "朗读设置"
    override val hapticsSetting = "触感反馈"
    override val storageSetting = "存储空间"
    override val sessionsTitle = "会话管理"
    override val sessionsEmpty = "还没有云端会话"
    override val sessionsHint = "点选会话后可归档或删除"
    override val archiveSession = "归档会话"
    override val deleteSession = "删除会话"
    override val confirmDeleteSession = "删除后不可恢复，确定删除？"
    override val agentsTitle = "云端 Agent"
    override val agentsEmpty = "没有可用的云端 Agent"
    override val agentEnabled = "已启用"
    override val agentDisabled = "已停用"
    override val checkinTitle = "每日签到"
    override val checkinTodayDone = "今日已签到"
    override val checkinTodayTodo = "今日未签到"
    override val checkinStreak = "连续签到"
    override val checkinDaily = "每日积分"
    override val checkinClaim = "立即签到"
    override val checkinInactive = "当前没有签到活动"
    override val days = "天"
    override val themeBlue = "标准蓝"
    override val themeGreen = "青绿"
    override val themePurple = "紫"
    override val themeAmber = "琥珀"
    override val langZh = "中文"
    override val langEn = "English"
    override val fontSmall = "小"
    override val fontStandard = "标准"
    override val fontLarge = "大"
    override val memoryBank = "记忆库"
    override val growthPlan = "成长计划"
    override val cloudTools = "云端工具"
    override val growthLevel = "等级"
    override val growthEnergy = "能量"
    override val growthProgress = "已完成"
    override val growthNoTasks = "暂无成长任务"
    override val growthLoadFailed = "成长计划加载失败"
    override val growthOpenWebsite = "官网成长中心"
    override val growthBrowserUnavailable = "请在手机浏览器打开 WorkBuddy 成长中心"
    override val growthLocked = "未解锁"
    override val growthUnstarted = "未领取任务"
    override val growthInProgress = "进行中"
    override val growthCompleted = "已完成"
    override val growthClaimed = "奖励已领取"
    override val notSupported = "当前功能暂未支持"
    override val aboutApp = "关于 Wearbuddy"
    override val appIntroTitle = "应用介绍"
    override val appIntro =
        "Wearbuddy 是一个可独立运行在 Wear OS 手表上的 WorkBuddy 客户端。登录后，你可以在手表上发起云端任务、切换模型、查看进度并继续对话。\n\n" +
            "你也可以浏览工作区、预览或下载任务生成的文件，把常用指令保存为项目，下次一键运行。"
    override val appLicense = "本项目采用 MIT 开源许可证。"
    override val sourceRepository = "项目地址"
    override val developerList = "开发人员名单"
    override val developerRole = "开发者"
    override val storageTitle = "存储空间"
    override val storageCache = "缓存"
    override val storageData = "数据"
    override val clearCache = "清除缓存"
    override val cacheCleared = "缓存已清除"
    override val back = "返回"
    override val cancel = "取消"
    override val loading = "加载中…"
    override val networkOffline = "未连接云端"
}

private object EnStrings : LocalizedStrings {
    override val copyTextLabel = "Copy text"
    override val copiedText = "Copied"
    override val previewHtml = "Preview HTML"
    override val appName = "Wearbuddy"
    override val tabTasks = "Chats"
    override val tabProjects = "Projects"
    override val tabSettings = "Me"
    override val inputHint = "Ask anything…"
    override val emptyTasks = "No chats yet"
    override val newChat = "New chat"
    override val connectFirst = "Connect your account first"
    override val startChat = "Send your first message"
    override val emptyChatHint = "Tap New chat above to start your first conversation."
    override val jumpToBottom = "Jump to bottom"
    override val historyUnavailable = "Chat not synced. Reconnecting…"
    override val notLoggedInHint = "Sign in to dispatch cloud tasks"
    override val loginNow = "Sign in"
    override val statusThinking = "Thinking"
    override val statusExecuting = "Running"
    override val statusWaitingInput = "Waiting"
    override val statusDone = "Done"
    override val statusFailed = "Failed"
    override val statusStopped = "Stopped"
    override val send = "Send"
    override val stop = "Stop"
    override val retry = "Retry"
    override val modelPickerTitle = "Choose model"
    override val modelCloudDefault = "Cloud default"
    override val artifacts = "Artifacts"
    override val artifactsTitle = "Artifact center"
    override val artifactsEmpty = "No artifacts yet\nFiles from finished tasks appear here"
    override val artifactTapToView = "Tap to view or download"
    override val artifactTapToRetry = "Tap to retry"
    override val artifactSaved = "Saved in watch Download"
    override val askPrompt = "Cloud needs more info"
    override val thoughtLabel = "Thoughts"
    override val expandThought = "Expand thoughts"
    override val collapseThought = "Collapse thoughts"
    override val cloudArtifacts = "Cloud files"
    override val browseWorkspace = "Browse workspace"
    override val workspaceTitle = "Workspace"
    override val workspaceEmpty = "Empty folder"
    override val workspaceLoadFailed = "Couldn't read folder"
    override val workspaceParent = "Parent folder"
    override val workspaceUpLevel = "Top level"
    override val composeTitle = "Send message"
    override val messageLabel = "Message"
    override val messageHint = "Enter message"
    override val chooseFile = "Choose file"
    override val voiceInput = "Voice input"
    override val imageLabel = "Image"
    override val paste = "Paste"
    override val newline = "New line"
    override val listenFailed = "Didn't catch that, try again"
    override val micUnavailable = "Voice input unavailable"
    override val addProjectTitle = "New project"
    override val addProjectButton = "Add project"
    override val projectName = "Project name"
    override val projectCommand = "Command"
    override val createProject = "Create"
    override val runProject = "Run"
    override val deleteProject = "Delete"
    override val confirmDeleteProject = "Delete this project?"
    override val emptyProjects = "No projects yet\nTap Add above to create"
    override val profileTitle = "Profile"
    override val loginTitle = "Sign in WorkBuddy"
    override val loginSubtitle = "Phone + SMS code"
    override val phonePlaceholder = "Phone number"
    override val codePlaceholder = "SMS code"
    override val getCode = "Get code"
    override val submitLogin = "Sign in"
    override val logout = "Sign out"
    override val planTitle = "Plan"
    override val planUnknown = "Trial"
    override val pointsTitle = "Points"
    override val pointsLeft = "Left"
    override val todayRequests = "Today"
    override val signInToView = "Sign in to view"
    override val theme = "Theme"
    override val language = "Language"
    override val fontSize = "Font size"
    override val ttsSetting = "Speak results"
    override val hapticsSetting = "Haptics"
    override val storageSetting = "Storage"
    override val sessionsTitle = "Sessions"
    override val sessionsEmpty = "No cloud sessions yet"
    override val sessionsHint = "Tap a session to archive or delete"
    override val archiveSession = "Archive session"
    override val deleteSession = "Delete session"
    override val confirmDeleteSession = "This cannot be undone. Delete?"
    override val agentsTitle = "Cloud agents"
    override val agentsEmpty = "No cloud agents available"
    override val agentEnabled = "Enabled"
    override val agentDisabled = "Disabled"
    override val checkinTitle = "Daily check-in"
    override val checkinTodayDone = "Checked in today"
    override val checkinTodayTodo = "Not checked in today"
    override val checkinStreak = "Streak"
    override val checkinDaily = "Daily credits"
    override val checkinClaim = "Check in"
    override val checkinInactive = "No check-in activity"
    override val days = "days"
    override val themeBlue = "Blue"
    override val themeGreen = "Green"
    override val themePurple = "Purple"
    override val themeAmber = "Amber"
    override val langZh = "中文"
    override val langEn = "English"
    override val fontSmall = "Small"
    override val fontStandard = "Standard"
    override val fontLarge = "Large"
    override val memoryBank = "Memory bank"
    override val growthPlan = "Growth plan"
    override val cloudTools = "Cloud tools"
    override val growthLevel = "Level"
    override val growthEnergy = "Energy"
    override val growthProgress = "Completed"
    override val growthNoTasks = "No growth tasks yet"
    override val growthLoadFailed = "Couldn't load the growth plan"
    override val growthOpenWebsite = "Open Growth Center"
    override val growthBrowserUnavailable = "Open the WorkBuddy Growth Center in your phone browser"
    override val growthLocked = "Locked"
    override val growthUnstarted = "Not accepted"
    override val growthInProgress = "In progress"
    override val growthCompleted = "Completed"
    override val growthClaimed = "Reward claimed"
    override val notSupported = "Not available yet"
    override val aboutApp = "About Wearbuddy"
    override val appIntroTitle = "Introduction"
    override val appIntro =
        "Wearbuddy is a standalone WorkBuddy client for Wear OS. Sign in to start cloud tasks, switch models, follow progress, and keep the conversation going from your watch.\n\n" +
            "Browse the workspace, preview or download task files, and save frequent prompts as projects you can run with one tap."
    override val appLicense = "This project is released under the MIT License."
    override val sourceRepository = "Source repository"
    override val developerList = "Developers"
    override val developerRole = "Developer"
    override val storageTitle = "Storage"
    override val storageCache = "Cache"
    override val storageData = "Data"
    override val clearCache = "Clear cache"
    override val cacheCleared = "Cache cleared"
    override val back = "Back"
    override val cancel = "Cancel"
    override val loading = "Loading…"
    override val networkOffline = "Cloud offline"
}

val LocalStrings = staticCompositionLocalOf<LocalizedStrings> { ZhStrings }

fun stringsOf(language: String): LocalizedStrings =
    if (language == "en") EnStrings else ZhStrings
