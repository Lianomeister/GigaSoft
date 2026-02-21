package com.gigasoft.runtime

import com.gigasoft.api.GigaUiActionBarEvent
import com.gigasoft.api.GigaUiDialogOpenEvent
import com.gigasoft.api.GigaUiMenuOpenEvent
import com.gigasoft.api.GigaUiNoticeEvent
import com.gigasoft.api.HostAccess
import com.gigasoft.api.HostLocationRef
import com.gigasoft.api.HostPlayerSnapshot
import com.gigasoft.api.UiDialog
import com.gigasoft.api.UiDialogField
import com.gigasoft.api.UiMenu
import com.gigasoft.api.UiMenuItem
import com.gigasoft.api.UiNotice
import com.gigasoft.api.UiLevel
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
            override fun worlds() = emptyList<com.gigasoft.api.HostWorldSnapshot>()
            override fun entities(world: String?) = emptyList<com.gigasoft.api.HostEntitySnapshot>()
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
