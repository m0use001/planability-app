package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebSearch 质量兜底判定锁（2026-11-04 两次真机案例）：
 * ①「不背单词 会员」→ 单字百科退化；②「背单词软件 永久会员 排行榜」→ 曼恩卡车（零词元重叠）。
 * 兜底触发条件是 isGoodEnough = 有结果 && 不退化 && 相关；正常结果不得误触发（否则每次搜索
 * 都会多打一次百度/ensearch 请求）。
 */
class WebSearchDegenerateTest {

    private fun hit(t: String, u: String) = WebSearch.Hit(t, u, "")

    private val bingBad = listOf(
        hit("不（汉语汉字）_百度百科", "https://baike.baidu.com/item/%E4%B8%8D/64725"),
        hit("不简单！“不”到底是读“bú”还是bù", "https://www.toutiao.com/article/7410305081104482855/"),
        hit("不背单词官网", "https://www.bbdc.cn/")
    )

    @Test
    fun `单字百度百科首条 = 退化`() {
        assertTrue(WebSearch.isDegenerate(bingBad))
    }

    @Test
    fun `前两条完全相同 = 退化`() {
        val hits = listOf(
            hit("同一标题", "https://a.example/1"),
            hit("同一标题", "https://a.example/1"),
            hit("其它", "https://example.com/")
        )
        assertTrue(WebSearch.isDegenerate(hits))
    }

    @Test
    fun `多字百科词条不是单字退化`() {
        val hits = listOf(
            hit("不背单词 - 百度百科", "https://baike.baidu.com/item/%E4%B8%8D%E8%83%8C%E5%8D%95%E8%AF%8D"),
            hit("背单词方法", "https://example.com/")
        )
        assertFalse(WebSearch.isDegenerate(hits))
    }

    @Test
    fun `曼恩卡车跑偏 = 不相关`() {
        val runaway = listOf(
            hit("MAN TGS_底盘_曼恩商用车中国", "https://www.manchina.com.cn/product/812.htm"),
            hit("【图】曼 (MAN) TGS系列 540马力", "https://example.com/2")
        )
        assertFalse(WebSearch.isRelevant(runaway, "背单词软件 永久会员 排行榜"))
        assertFalse(WebSearch.isGoodEnough(runaway, "背单词软件 永久会员 排行榜"))
    }

    @Test
    fun `正常中文结果相关`() {
        val good = listOf(
            hit("全国大学英语四、六级考试 (CET)", "https://cet.neea.edu.cn/"),
            hit("全国大学英语四、六级考试报名网", "http://cet-kw.neea.edu.cn/")
        )
        assertTrue(WebSearch.isRelevant(good, "四级听力精听方法"))
        assertTrue(WebSearch.isGoodEnough(good, "四级听力精听方法"))
    }

    @Test
    fun `英文 query 词元匹配`() {
        val hits = listOf(hit("Kotlin coroutines guide", "https://example.com/kt"))
        assertTrue(WebSearch.isRelevant(hits, "kotlin coroutines"))
        assertFalse(WebSearch.isRelevant(hits, "swift actors"))
    }

    @Test
    fun `纯符号 query 不拦`() {
        val hits = listOf(hit("任意标题", "https://example.com/"))
        assertTrue(WebSearch.isRelevant(hits, "???"))
    }

    @Test
    fun `结果太少也触发兜底`() {
        assertFalse(WebSearch.isGoodEnough(listOf(hit("四级听力", "https://a/")), "四级听力精听方法"))
    }

    @Test
    fun `站点卡结构信号_全首页判退化`() {
        // 2026-11-08 真机：星轨3 三条 query 返回的全是站点根页/官网首页
        assertTrue(WebSearch.looksLikeSiteCards(bingFirstWordOnly))
        assertTrue(WebSearch.looksLikeSiteCards(listOf(
            hit("小说,番茄小说网_好看的小说尽在番茄小说官网", "https://fanqienovel.com/"),
            hit("书库_番茄小说官网", "https://fanqienovel.com/library"),
            hit("番茄免费小说 - Windows官方下载", "https://apps.microsoft.com/detail/xp8cjhgdb5gw37")
        )))
    }

    @Test
    fun `站点卡结构信号_内容页不判退化`() {
        val contentPages = listOf(
            hit("星轨3剧情顺序(主线精简版) - 哔哩哔哩", "https://www.bilibili.com/opus/1134919157798666246"),
            hit("【星轨3】《后星轨书》剧情全流程", "https://www.bilibili.com/video/BV1wpxq6VEX5/"),
            hit("星轨三剧情先后顺序推荐(回顾用)", "https://example.com/bh3-order")
        )
        assertFalse(WebSearch.looksLikeSiteCards(contentPages))
        // 少于 3 条不判：样本太小时比例没意义
        assertFalse(WebSearch.looksLikeSiteCards(bingFirstWordOnly.take(2)))
    }

    @Test
    fun `根页判定`() {
        assertTrue(WebSearch.isRootPage("https://example.com/"))
        assertTrue(WebSearch.isRootPage("https://example.com/zh-cn/"))
        assertFalse(WebSearch.isRootPage("https://www.bilibili.com/opus/1134919157798666246"))
    }

    @Test
    fun `360 结果解析用 data-mdurl 剔除推广位`() {
        val html = "<h3 class=\"res-title \" ><a  href=\"https://www.so.com/link?m=AD\" target=\"_blank\">广告推广内容</a></h3>" +
            "<h3 class=\"res-title \" ><a  href=\"https://www.so.com/link?m=x\" data-mdurl=\"https://xiazai.zol.com.cn/jiqiao/1.html\" target=\"_blank\">2024年十款免费<em>背单词软件</em>下载量<em>排行榜</em></a></h3>"
        val hits = WebSearch.parse360(html, 5)
        assertTrue(hits.size == 1)
        assertTrue(hits.first().title.contains("背单词软件"))
        assertTrue(hits.first().url == "https://xiazai.zol.com.cn/jiqiao/1.html")
    }

    // ───────── 2026-11-08 第三次报障：只匹配首词的站点卡被旧判据放行 ─────────
    // 真机取自小说项目会话 的两条 query（逐字相同的 6 条结果），标题按 Bing 实返抄录。

    private val bingFirstWordOnly = listOf(
        hit("云·云海幻境 - ExampleGames", "https://example.com/cloud/"),
        hit("《星轨：云海幻境》官方网站-4.6全新版本「月升之前，与兽共舞」现已推出", "https://example.com/"),
        hit("崩（汉语文字）_百度百科", "https://baike.baidu.com/item/%E5%B4%A9/2383377"),
        hit("《星轨3》官方网站 - 示例游戏商科幻冒险动作游戏", "https://example.com/m/"),
        hit("《星轨：云海幻境》官方网站——启程，奔赴云海之约", "https://example.com/"),
        hit("首页 | ExampleGames", "https://example.com/zh-cn/")
    )

    @Test
    fun `只命中首词的站点卡 = 不相关（星轨3 剧情）`() {
        val q = "星轨3 主线剧情 章节 完整顺序"
        // 旧判据会判 true：云海幻境标题里有「星」「轨」两个字
        assertFalse(WebSearch.isRelevant(bingFirstWordOnly, q))
        assertFalse(WebSearch.isGoodEnough(bingFirstWordOnly, q))
    }

    @Test
    fun `只命中首词的站点卡 = 不相关（番茄炒蛋）`() {
        val junk = listOf(
            hit("小说,番茄小说网_好看的小说尽在番茄小说官网", "https://fanqienovel.com/"),
            hit("番茄（茄科茄属植物）_百度百科", "https://baike.baidu.com/item/%E7%95%AA%E8%8C%84/69104")
        )
        assertFalse(WebSearch.isRelevant(junk, "番茄炒蛋 家常做法 步骤"))
    }

    @Test
    fun `多词 query 命中共两个词才算相关`() {
        val good = listOf(hit("星轨3主线剧情顺序整理（含后星轨书）- 示例社区", "https://example.com/bh3/article/1"))
        assertTrue(WebSearch.isRelevant(good, "星轨3 主线剧情 章节 完整顺序"))
        // 360 兜底实返（2026-11-08 实测前两条）——同一 query 换源后立刻可用
        val so360 = listOf(
            hit("星轨3剧情顺序(主线精简版) - 哔哩哔哩", "https://www.bilibili.com/opus/1134919157798666246"),
            hit("星轨三剧情先后顺序推荐(回顾用)", "https://example.com/bh3-order")
        )
        assertTrue(WebSearch.isGoodEnough(so360, "星轨3 主线剧情 章节 完整顺序"))
    }

    @Test
    fun `多词 query 必须命中首词以外的词`() {
        // 真机复现：`星轨3 后星轨书 剧情 解析` 的 Bing 结果里只有官网卡，
        // 「星轨3」与「后星轨书」共享「星轨」二字——只数命中词数会被蒙过
        val onlyBrand = listOf(
            hit("《星轨3》官方网站 - 示例游戏商科幻冒险动作游戏", "https://example.com/m/"),
            hit("《星轨：云海幻境》官方网站——启程，奔赴云海之约", "https://example.com/")
        )
        assertFalse(WebSearch.isRelevant(onlyBrand, "星轨3 后星轨书 剧情 解析"))
    }

    @Test
    fun `词元切分忽略标点与纯符号`() {
        assertEquals(listOf("星轨3", "主线剧情", "后星轨书"), WebSearch.contentTokens("星轨3 主线剧情，后星轨书？"))
        assertTrue(WebSearch.contentTokens("??? ...").isEmpty())
    }

    @Test
    fun `360 摘要按 data-result-summary 取正文`() {
        val html = "<h3 class=\"res-title \" ><a href=\"https://www.so.com/link?m=x\" " +
            "data-mdurl=\"https://www.bilibili.com/opus/1\" target=\"_blank\"><em>星轨3剧情顺序</em>(主线精简版)</a></h3> " +
            "<p class=\"res-desc\"><span class=\"gray g-c-gray\">2025年11月18日&nbsp;-&nbsp;</span>" +
            "<span data-result-summary>特殊<em>剧情</em>:角色生日剧情.编年史:逐梦旅途…</span></p>"
        val hits = WebSearch.parse360(html, 5)
        assertEquals(1, hits.size)
        assertTrue(hits.first().snippet.contains("角色生日剧情"))
        assertFalse(hits.first().snippet.contains("2025年11月18日"))
    }

    @Test
    fun `360 富摘要模板 h3 多个空格也要解析`() {
        // 真机抓到的 richsum 模板：<h3   class="res-title">（h3 与 class 之间三空格）
        val html = "<h3   class=\"res-title\"><a href=\"https://www.so.com/link?m=y\" " +
            "data-mdurl=\"https://jingyan.baidu.com/article/1.html\" target=\"_blank\">" +
            "<em>西红柿炒鸡蛋</em>的<em>做法</em>的详细<em>步骤</em>-百度经验</a></h3>" +
            "<div class=\"res-rich so-rich-jingyan\"><div class=\"steps\"><ul><li>鸡蛋打散备用</li>" +
            "<li>番茄切块下锅</li></ul></div></div>"
        val hits = WebSearch.parse360(html, 5)
        assertEquals(1, hits.size)
        assertTrue(hits.first().title.contains("西红柿炒鸡蛋"))
        assertTrue(hits.first().snippet.contains("鸡蛋打散"))
        // 富摘要模板没有 res-desc，退化取块内文本时不许把裸链接/半截标签带出去
        assertFalse(hits.first().snippet.contains("http"))
        assertFalse(hits.first().snippet.contains("<"))
    }

    @Test
    fun `Bing 块 li 多个空格也要解析`() {
        val html = "<li   class=\"b_algo\"><h2><a href=\"https://example.com/1\">标题甲</a></h2><p>摘要</p></li>"
        val hits = WebSearch.parseBing(html, 5)
        assertEquals(1, hits.size)
        assertEquals("标题甲", hits.first().title)
    }

    // ───────── 2026-11-08 第四次报障：英文学术长查询在 Bing/360 上零召回 ─────────
    // 真机会话实录：「Parry 2023 Meta-Analysis "Brain Drain Effect"…」回到 Cisco 论坛，
    // 「Ward … working memory」回到影视站。这两类 query 改走学术库（Crossref→OpenAlex→S2）。

    @Test
    fun `引文式英文长查询 = 学术型`() {
        assertTrue(
            WebSearch.looksAcademic(
                "Parry 2023 Meta-Analysis \"Brain Drain Effect\" smartphone mere presence cognitive performance findings small effect"
            )
        )
        assertTrue(WebSearch.looksAcademic("Ward Duke Gneezy Bos 2017 Brain Drain smartphone working memory"))
    }

    @Test
    fun `中文查询永不进学术库`() {
        assertFalse(WebSearch.looksAcademic("不背单词 终身会员 价格"))
        assertFalse(WebSearch.looksAcademic("四级听力 精听方法 2023 真题"))
    }

    @Test
    fun `普通英文短查询不打扰学术库`() {
        assertFalse(WebSearch.looksAcademic("how to install kotlin"))
        assertFalse(WebSearch.looksAcademic("battery"))
    }
}
