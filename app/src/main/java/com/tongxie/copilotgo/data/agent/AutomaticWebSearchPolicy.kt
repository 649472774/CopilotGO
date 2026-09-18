package com.tongxie.copilotgo.data.agent

import com.tongxie.copilotgo.data.chat.UiMessage

/** Local intent routing never sends a draft, attachment or history to a classifier service. */
object AutomaticWebSearchPolicy {
    fun requiresSearch(history: List<UiMessage>): Boolean {
        val questions = history.filter { it.role == "user" }
        return requiresSearch(
            questions.lastOrNull()?.content.orEmpty(),
            questions.dropLast(1).lastOrNull()?.content
        )
    }

    fun requiresSearch(text: String, previousUserText: String? = null): Boolean {
        val question = text.trim().take(32_768)
        if (question.isBlank() || offline.containsMatchIn(question) || transformation.containsMatchIn(question)) {
            return false
        }
        if (explicitSearch.containsMatchIn(question)) return true
        if (explanation.containsMatchIn(question) && !timeSensitive.containsMatchIn(question)) return false
        if (liveTopic.containsMatchIn(question) || latest.containsMatchIn(question)) return true
        return question.length <= 160 && followUp.containsMatchIn(question) &&
            previousUserText != null && requiresSearch(previousUserText)
    }

    private val offline = Regex(
        """(?:不要|不用|不必|无需|禁止).{0,8}(?:联网|上网|搜索)|离线回答|仅根据(?:我|上文|附件|提供)|\b(?:do not|don't|without)\s+(?:browse|browsing|search|searching|internet|web)\b""",
        RegexOption.IGNORE_CASE
    )
    private val transformation = Regex(
        """^(?:(?:请|帮我|麻烦|please)\s*)?(?:翻译|润色|改写|translate\b|rewrite\b|proofread\b)|^(?:(?:请|帮我|麻烦|please)\s*)?(?:写|编写|实现|设计|write|implement|design).{0,40}(?:代码|函数|程序|应用|算法|脚本|code\b|function\b|app\b|algorithm\b|script\b)""",
        RegexOption.IGNORE_CASE
    )
    private val explicitSearch = Regex(
        """联网|上网|网上查|搜一下|搜索一下|查一下|查一查|查查|帮我搜|请搜索|\b(?:search (?:the web|online|for)|look .{0,60} up|browse (?:the web|for)|find online)\b""",
        RegexOption.IGNORE_CASE
    )
    private val explanation = Regex(
        """什么是|是什么意思|如何计算|怎么计算|原理|科普|解释|为什么|为何|\b(?:explain|definition|how does|how do|why)\b""",
        RegexOption.IGNORE_CASE
    )
    private val timeSensitive = Regex(
        """今天|今日|明天|后天|昨天|今晚|现在|目前|当前|实时|最近|最新|这周|本周|今年|\b(?:today|tomorrow|yesterday|tonight|now|current|latest|recent|this week|this year)\b""",
        RegexOption.IGNORE_CASE
    )
    private val liveTopic = Regex(
        """天气|气温|下雨|降雨|空气质量|股价|股市|股票.{0,12}(?:价格|多少|涨|跌)|行情|汇率|金价|油价|新闻|头条|热搜|路况|航班.{0,12}(?:状态|延误)|(?:比特币|以太坊).{0,12}(?:价格|多少)|\b(?:weather|forecast|temperature|air quality|stock price|share price|stock quote|exchange rate|news|headlines|traffic|flight status)\b""",
        RegexOption.IGNORE_CASE
    )
    private val latest = Regex("""最新|实时|\b(?:latest|up.to.date|real.time)\b""", RegexOption.IGNORE_CASE)
    private val followUp = Regex(
        """^(?:那|那么|还有|明天|后天|昨天|今天呢|现在呢|and\b|what about\b|how about\b|tomorrow\b)""",
        RegexOption.IGNORE_CASE
    )
}
