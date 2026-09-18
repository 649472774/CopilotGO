package com.tongxie.copilotgo.data.agent

import com.tongxie.copilotgo.data.chat.UiMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticWebSearchPolicyTest {
    @Test
    fun currentWeatherQuotesNewsAndExplicitSearchUseTheWeb() {
        listOf(
            "今天北京天气如何", "今天微软股价多少", "北京明天会下雨吗", "微软股价",
            "现在美元人民币汇率是多少", "今天有什么新闻", "Kotlin 最新版本是多少",
            "What is Microsoft's stock price today?", "Beijing weather tomorrow",
            "latest Android release", "Search the web for Kotlin release notes",
            "帮我查一下微软的市值", "微软股价今天为什么跌"
        ).forEach { assertTrue(it, AutomaticWebSearchPolicy.requiresSearch(it)) }
    }

    @Test
    fun ordinaryQuestionsTransformationsAndExplicitOfflineRequestsDoNotSearch() {
        listOf(
            "你好", "1 + 1 等于多少", "今天是星期几", "什么是天气预报",
            "解释股价为什么会波动", "不要联网，解释今天北京天气如何这句话的语法",
            "翻译成英文：今天北京天气如何", "帮我写一个查询股价的 Kotlin 函数",
            "Translate: what is the weather today?", "Explain how a weather forecast works",
            "Write a weather app in Kotlin", "Explain exchange rate calculations",
            "Without searching, explain the news article I provided"
        ).forEach { assertFalse(it, AutomaticWebSearchPolicy.requiresSearch(it)) }
    }

    @Test
    fun shortFollowUpUsesOnlyThePreviousUserQuestion() {
        assertTrue(AutomaticWebSearchPolicy.requiresSearch("那上海呢", "今天北京天气如何"))
        assertTrue(AutomaticWebSearchPolicy.requiresSearch("明天呢", "今天北京天气如何"))
        assertTrue(AutomaticWebSearchPolicy.requiresSearch("What about Apple?", "Microsoft stock price"))
        assertFalse(AutomaticWebSearchPolicy.requiresSearch("那上海呢", "讲一个关于城市的故事"))
        assertFalse(AutomaticWebSearchPolicy.requiresSearch("那上海呢"))
        assertFalse(AutomaticWebSearchPolicy.requiresSearch(listOf(
            UiMessage("u1", "user", "讲个故事"),
            UiMessage("a1", "assistant", "今天北京天气如何"),
            UiMessage("u2", "user", "明天呢")
        )))
    }

    @Test
    fun legacySessionsDefaultToAutomaticRoutingButNeverGrantExecutionConsent() {
        val settings = Json.decodeFromString<AgentSessionSettings>("{}")
        assertTrue(settings.automaticWebSearch)
        assertFalse(settings.enabled)
        assertFalse(settings.autoApprovePublicWebReads)
    }
}
