package com.clockwork.runtime

import com.clockwork.api.GigaUiActionBarEvent
import com.clockwork.api.GigaUiDialogOpenEvent
import com.clockwork.api.GigaUiMenuOpenEvent
import com.clockwork.api.GigaUiNoticeEvent
import com.clockwork.api.HostAccess
import com.clockwork.api.HostLocationRef
import com.clockwork.api.HostPlayerSnapshot
import com.clockwork.api.UiDialog
import com.clockwork.api.UiDialogField
import com.clockwork.api.UiMenu
import com.clockwork.api.UiMenuItem
import com.clockwork.api.UiNotice
import com.clockwork.api.UiLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimePluginUiTest {
    @Test
    fun `runtime plugin ui sends host messages and publishes ui events`() {
        val sent = mutableListOf<String>()
        val host = object : HostAccess {
            override fun serverInfo() = null
            override fun broadcast(message: String): Boolean = false
            override fun findPlayer(name: String): HostPlayerSnapshot? {
                return HostPlayerSnapshot("uuid-1", name, HostLocationRef("world", 0.0, 64.0, 0.0))
            }
            override fun worlds() = emptyList<com.clockwork.api.HostWorldSnapshot>()
            override fun entities(world: String?) = emptyList<com.clockwork.api.HostEntitySnapshot>()
            override fun spawnEntity(type: String, location: HostLocationRef) = null
            override fun playerInventory(name: String) = null
            override fun setPlayerInventoryItem(name: String, slot: Int, itemId: String) = false
            override fun sendPlayerMessage(name: String, message: String): Boolean {
                sent += "$name:$message"
                return true
            }
        }
        val bus = RuntimeEventBus(mode = EventDispatchMode.EXACT)
        var noticeEvent: GigaUiNoticeEvent? = null
        var actionEvent: GigaUiActionBarEvent? = null
        var menuEvent: GigaUiMenuOpenEvent? = null
        var dialogEvent: GigaUiDialogOpenEvent? = null
        bus.subscribe(GigaUiNoticeEvent::class.java) { noticeEvent = it }
        bus.subscribe(GigaUiActionBarEvent::class.java) { actionEvent = it }
        bus.subscribe(GigaUiMenuOpenEvent::class.java) { menuEvent = it }
        bus.subscribe(GigaUiDialogOpenEvent::class.java) { dialogEvent = it }

        val ui = RuntimePluginUi(hostAccess = host, events = bus)
        assertTrue(ui.notify("Alex", UiNotice(title = "Title", message = "Hello", level = UiLevel.INFO)))
        assertTrue(ui.actionBar("Alex", "Action", durationTicks = 20))
        assertTrue(
            ui.openMenu(
                "Alex",
                UiMenu("main", "Main", listOf(UiMenuItem("play", "Play"), UiMenuItem("quit", "Quit")))
            )
        )
        assertTrue(
            ui.openDialog(
                "Alex",
                UiDialog("settings", "Settings", listOf(UiDialogField("volume", "Volume")))
            )
        )
        assertTrue(ui.close("Alex"))

        assertTrue(sent.any { it.contains("Alex:[UI/INFO] Title: Hello") })
        assertTrue(sent.any { it.contains("Alex:[UI/ACTIONBAR] Action") })
        assertTrue(sent.any { it.contains("Alex:[UI/MENU] Main") })
        assertTrue(sent.any { it.contains("Alex:[UI/DIALOG] Settings") })
        assertTrue(sent.any { it.contains("Alex:[UI] closed") })
        assertEquals("Hello", noticeEvent?.message)
        assertEquals("Action", actionEvent?.message)
        assertEquals("main", menuEvent?.menuId)
        assertEquals("settings", dialogEvent?.dialogId)
    }
}
