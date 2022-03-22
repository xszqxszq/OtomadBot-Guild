package xyz.xszq.otomadbot

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.console.util.retryCatching
import net.mamoe.mirai.message.data.MessageChainBuilder
import net.mamoe.mirai.message.data.PlainText
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.info
import xyz.xszq.gocqhttp.GlobalGuildEventChannel
import xyz.xszq.gocqhttp.GuildMessageEvent
import xyz.xszq.gocqhttp.quoteReply
import xyz.xszq.gocqhttp.uploadAsGuildImage
import xyz.xszq.otomadbot.api.PythonApi
import xyz.xszq.otomadbot.api.RandomEropic
import xyz.xszq.otomadbot.core.Cooldown
import xyz.xszq.otomadbot.image.ImageCommonHandler
import xyz.xszq.otomadbot.text.AutoReplyHandler
import xyz.xszq.otomadbot.text.SentimentDetector

object Guild : KotlinPlugin(
    JvmPluginDescription(
        id = "xyz.xszq.otomadbot.guild",
        name = "OtomadBotGuildExt",
        version = "0.1.0",
    ) {
        author("xszqxszq")
    }
) {
    override fun onEnable() {
        GlobalGuildEventChannel.subscribeMessages {
            always {
                AutoReplyHandler.matchText(message.toSimple().lowercase(), channel.id.toLong())?.let { quoteReply(it) }
            }
            atBot {
                ifReady(SentimentDetector.cooldown) {
                    quoteReply(
                        ImageCommonHandler.replyPic.getRandom(
                            if (PythonApi.sentiment(message)!!) "reply"
                            else "afraid"
                        ).toExternalResource().use {
                            it.uploadAsGuildImage()
                        }
                    )
                    update(SentimentDetector.cooldown)
                }
            }
            startsWith("/eropic") { keyword ->
                eropic(keyword, this)
            }
        }
        logger.info { "Plugin loaded" }
    }
}
suspend fun <T> GuildMessageEvent.ifReady(cd: Cooldown, block: suspend () -> T): T? =
    if (cd.isReady(channel.id.toLong()))
    block.invoke() else null
fun GuildMessageEvent.update(cd: Cooldown) = cd.update(channel.id.toLong())
fun GuildMessageEvent.remaining(cd: Cooldown) = cd.remaining(channel.id.toLong()) / 1000L
val eropicCooldown = Cooldown("eropic")
suspend fun eropic(keyword: String, event: GuildMessageEvent, amount: Int = 1) = event.run {
    ifReady(eropicCooldown) {
        update(eropicCooldown)
        val result = RandomEropic.get(keyword.trim(), r18 = false, num = amount)
        if (result.data.isNotEmpty()) {
            kotlin.runCatching {
                val content = MessageChainBuilder()
                coroutineScope {
                    result.data.forEach {
                        val pid = it.pid.toString()
                        val author = it.author
                        launch {
                            OtomadBotCore.logger.info(it.urls.toString())
                            retryCatching(10) {
                                NetworkUtils.downloadAsByteArray(
                                    it.urls["regular"] ?: it.urls["original"]!!,
                                    RandomEropic.pixivHeader, true
                                )
                            }.onSuccess { now ->
                                kotlin.runCatching {
                                    now.toExternalResource().use { ex ->
                                        content.add(
                                            ex.uploadAsGuildImage()
                                                    + PlainText("\nPID: $pid\n作者：$author")
                                        )
                                    }
                                }.onFailure { e ->
                                    e.printStackTrace()
                                }
                            }.onFailure { e ->
                                e.printStackTrace()
                            }
                        }
                    }
                }
                kotlin.runCatching {
                    channel.sendMessage(content.build()) ?: throw Exception("传不上来")
                }.onFailure { err ->
                    err.printStackTrace()
                    quoteReply("被腾讯拦截了o(╥﹏╥)o\n请稍后重试")
                    eropicCooldown.reset(channel.id.toLong())
                }
            }.onFailure { err ->
                OtomadBotCore.logger.error(err)
                quoteReply("下载不下来，怎么会事呢")
                eropicCooldown.reset(channel.id.toLong())
            }
        } else {
            quoteReply("没有找到涩图，怎么会事呢")
            eropicCooldown.reset(channel.id.toLong())
        }
    }
}