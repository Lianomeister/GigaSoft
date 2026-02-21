package com.clockwork.runtime

import com.clockwork.api.EventBus
import com.clockwork.api.GigaUiActionBarEvent
import com.clockwork.api.GigaUiDialogOpenEvent
import com.clockwork.api.GigaUiMenuOpenEvent
import com.clockwork.api.GigaUiNoticeEvent
import com.clockwork.api.HostAccess
import com.clockwork.api.PluginUi
import com.clockwork.api.UiDialog
import com.clockwork.api.UiMenu
import com.clockwork.api.UiNotice

class RuntimePluginUi(
    private val hostAccess: HostAccess,
    private val events: EventBus
) : PluginUi {
    companion object {
        private const val MAX_NOTICE_TEXT = 280
        private const val MAX_ACTIONBAR_TEXT = 120
        private const val MAX_MENU_ITEMS = 10
        private const val MAX_DIALOG_FIELDS = 8
    }

    override fun notify(player: String, notice: UiNotice): Boolean {
        val playerName = player.trim()
        if (playerName.isEmpty()) return false
        val prefix = when (notice.level.name) {
            "SUCCESS" -> "[UI/OK]"
            "WARNING" -> "[UI/WARN]"
            "ERROR" -> "[UI/ERR]"
            else -> "[UI/INFO]"
        }
        val text = buildString {
            if (notice.title.isNotBlank()) {
                append(notice.title.trim())
                append(": ")
            }
            append(notice.message.trim())
        }.sanitizeInline(MAX_NOTICE_TEXT)
        val delivered = hostAccess.sendPlayerMessage(playerName, "$prefix $text (${notice.durationMillis}ms)")
        events.publish(
            GigaUiNoticeEvent(
                player = hostAccess.findPlayer(playerName),
                title = notice.title,
                message = notice.message,
                level = notice.level,
                durationMillis = notice.durationMillis
            )
        )
        return delivered
    }

    override fun actionBar(player: String, message: String, durationTicks: Int): Boolean {
        val playerName = player.trim()
        if (playerName.isEmpty()) return false
        val normalizedDuration = durationTicks.coerceAtLeast(1)
        val delivered = hostAccess.sendPlayerMessage(
            playerName,
            "[UI/ACTIONBAR] ${message.sanitizeInline(MAX_ACTIONBAR_TEXT)} (${normalizedDuration}t)"
        )
        events.publish(
            GigaUiActionBarEvent(
                player = hostAccess.findPlayer(playerName),
                message = message,
                durationTicks = normalizedDuration
            )
        )
        return delivered
    }

    override fun openMenu(player: String, menu: UiMenu): Boolean {
        val playerName = player.trim()
        if (playerName.isEmpty()) return false
        val title = menu.title.sanitizeInline(MAX_NOTICE_TEXT)
        val shownItems = menu.items.take(MAX_MENU_ITEMS)
        val headerSent = hostAccess.sendPlayerMessage(
            playerName,
            "[UI/MENU] $title (${shownItems.size}/${menu.items.size})"
        )
        shownItems.forEachIndexed { index, item ->
            val state = if (item.enabled) "ON" else "OFF"
            val label = item.label.sanitizeInline(96)
            hostAccess.sendPlayerMessage(playerName, "  ${index + 1}. [$state] $label")
            if (item.description.isNotBlank()) {
                hostAccess.sendPlayerMessage(playerName, "      ${item.description.sanitizeInline(140)}")
            }
        }
        events.publish(
            GigaUiMenuOpenEvent(
                player = hostAccess.findPlayer(playerName),
                menuId = menu.id,
                title = menu.title,
                itemCount = menu.items.size
            )
        )
        return headerSent
    }

    override fun openDialog(player: String, dialog: UiDialog): Boolean {
        val playerName = player.trim()
        if (playerName.isEmpty()) return false
        val title = dialog.title.sanitizeInline(MAX_NOTICE_TEXT)
        val shownFields = dialog.fields.take(MAX_DIALOG_FIELDS)
        val headerSent = hostAccess.sendPlayerMessage(
            playerName,
            "[UI/DIALOG] $title (${shownFields.size}/${dialog.fields.size})"
        )
        shownFields.forEach { field ->
            val required = if (field.required) "required" else "optional"
            val type = field.type.name.lowercase()
            val label = field.label.sanitizeInline(96)
            hostAccess.sendPlayerMessage(playerName, "  - $label [$type, $required]")
            if (field.options.isNotEmpty()) {
                val options = field.options.joinToString(", ") { it.sanitizeInline(20) }.sanitizeInline(140)
                hostAccess.sendPlayerMessage(playerName, "      options: $options")
            }
            if (field.placeholder.isNotBlank()) {
                hostAccess.sendPlayerMessage(playerName, "      hint: ${field.placeholder.sanitizeInline(140)}")
            }
        }
        events.publish(
            GigaUiDialogOpenEvent(
                player = hostAccess.findPlayer(playerName),
                dialogId = dialog.id,
                title = dialog.title,
                fieldCount = dialog.fields.size
            )
        )
        return headerSent
    }

    override fun close(player: String): Boolean {
        val playerName = player.trim()
        if (playerName.isEmpty()) return false
        return hostAccess.sendPlayerMessage(playerName, "[UI] closed")
    }

    private fun String.sanitizeInline(maxChars: Int): String {
        val normalized = trim()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
        if (normalized.length <= maxChars) return normalized
        return normalized.take(maxChars - 3) + "..."
    }
}
