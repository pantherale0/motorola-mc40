package dev.pantherale0.mc40.overlay

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiConfigParserTest {
    @Test
    fun parsesNestedSlotsAndRequestedDefault() {
        val data = json(
            """
            {
              "schema": 1,
              "default": "shop_trip",
              "slots": [
                {"id": "consume", "label": "Use", "behavior": "use"},
                {"id": "shop_trip", "label": "Shopping", "behavior": "shopping"},
                {"id": "", "label": "", "behavior": "use"}
              ]
            }
            """
        )

        val config = UiConfigParser.parse(data)!!

        assertEquals("shop_trip", config.defaultMode)
        assertEquals(2, config.slots.size)
        assertEquals(UiConfig.BEHAVIOR_SHOPPING, config.behaviorFor("shop_trip"))
    }

    @Test
    fun parsesFlattenedSlotsAndFallsBackToFirstDefault() {
        val data = json(
            """
            {
              "schema": "1",
              "default": "missing",
              "slot_1_id": "stock",
              "slot_1_label": "Stock",
              "slot_1_behavior": "other",
              "slot_2_id": "buy",
              "slot_2_label": "Buy",
              "slot_2_behavior": "shopping"
            }
            """
        )

        val config = UiConfigParser.parse(data)!!

        assertEquals("stock", config.defaultMode)
        assertEquals(UiConfig.BEHAVIOR_USE, config.behaviorFor("stock"))
        assertEquals(UiConfig.BEHAVIOR_SHOPPING, config.behaviorFor("buy"))
    }

    @Test
    fun rejectsUnsupportedOrEmptyConfiguration() {
        assertNull(UiConfigParser.parse(json("""{"schema": 2, "slots": []}""")))
        assertNull(UiConfigParser.parse(json("""{"schema": 1, "slots": []}""")))
    }

    @Test
    fun overlayParserRecognizesConfigReinitAndCustomMode() {
        val configCommand = OverlayParser.parse(
            json(
                """
                {
                  "data": {
                    "command": "ui_config",
                    "schema": 1,
                    "slots": [{"id": "Inventory", "label": "Inventory", "behavior": "use"}]
                  }
                }
                """
            )
        )!!
        val modeCommand = OverlayParser.parse(
            json("""{"data": {"command": "set_mode", "mode": "Inventory"}}""")
        )!!
        val reinitCommand = OverlayParser.parse(
            json("""{"data": {"command": "reinit"}}""")
        )!!

        assertEquals(OverlayAction.UI_CONFIG, configCommand.action)
        assertEquals("inventory", configCommand.uiConfig?.defaultMode)
        assertEquals("inventory", modeCommand.mode)
        assertEquals(OverlayAction.REINIT, reinitCommand.action)
    }

    private fun json(value: String) = JsonParser.parseString(value).asJsonObject
}
