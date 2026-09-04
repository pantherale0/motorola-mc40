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
        assertNull(UiConfigParser.parse(json("""{"schema": 0, "slots": [{"id": "a", "label": "A"}]}""")))
        // Schema above MAX is clamped; still requires slots.
        val clamped = UiConfigParser.parse(
            json("""{"schema": 4, "slots": [{"id": "a", "label": "A", "behavior": "use"}]}""")
        )!!
        assertEquals(UiConfig.MAX_SCHEMA, clamped.schema)
    }

    @Test
    fun parseJsonRejectsEmptyOrInvalid() {
        assertNull(UiConfigParser.parseJson(""))
        assertNull(UiConfigParser.parseJson("   "))
        assertNull(UiConfigParser.parseJson("not-json"))
        assertNull(UiConfigParser.parseJson("[]"))
        assertNull(UiConfigParser.parseJson("""{"schema": 1, "slots": []}"""))
    }

    @Test
    fun writerRoundTripsSchema1() {
        val original = UiConfigParser.parse(
            json(
                """
                {
                  "schema": 1,
                  "default": "buy",
                  "slots": [
                    {"id": "use", "label": "Use", "behavior": "use"},
                    {"id": "buy", "label": "Buy", "behavior": "shopping"}
                  ]
                }
                """
            )
        )!!
        val restored = UiConfigParser.parseJson(UiConfigWriter.toJson(original).toString())!!
        assertEquals(original, restored)
    }

    @Test
    fun writerRoundTripsSchema2Actions() {
        val original = UiConfigParser.parse(
            json(
                """
                {
                  "schema": 2,
                  "default": "use",
                  "slots": [
                    {"id": "use", "label": "Use", "behavior": "use"},
                    {"id": "check", "label": "Check", "behavior": "custom"}
                  ],
                  "actions": [
                    {"id": "note", "label": "Note", "kind": "event"},
                    {"id": "find", "label": "Search", "kind": "search"}
                  ]
                }
                """
            )
        )!!
        val restored = UiConfigParser.parseJson(UiConfigWriter.toJson(original).toString())!!
        assertEquals(original, restored)
    }

    @Test
    fun writerRoundTripsSchema3Pages() {
        val original = UiConfigParser.parse(
            json(
                """
                {
                  "schema": 3,
                  "default": "use",
                  "default_page": "lists",
                  "slots": [{"id": "use", "label": "Use", "behavior": "use"}],
                  "pages": [
                    {
                      "id": "home",
                      "label": "Home",
                      "widgets": [
                        {"type": "text", "id": "hint", "label": "Scan"},
                        {"type": "button", "id": "products", "label": "Search", "kind": "search"},
                        {"type": "nav", "id": "go", "label": "Lists", "page": "lists"}
                      ]
                    },
                    {
                      "id": "lists",
                      "label": "Lists",
                      "widgets": [
                        {"type": "nav", "id": "back", "label": "Home", "page": "home"}
                      ]
                    }
                  ]
                }
                """
            )
        )!!
        val restored = UiConfigParser.parseJson(UiConfigWriter.toJson(original).toString())!!
        assertEquals(original, restored)
        assertEquals("lists", restored.defaultPage)
        assertEquals(UiAction.KIND_SEARCH, restored.page("home")!!.widgets[1].kind)
    }

    @Test
    fun parsesSchema3PagesAndWidgets() {
        val data = json(
            """
            {
              "schema": 3,
              "default": "use",
              "default_page": "lists",
              "slots": [{"id": "use", "label": "Use", "behavior": "use"}],
              "pages": [
                {
                  "id": "home",
                  "label": "Home",
                  "widgets": [
                    {"type": "text", "id": "hint", "label": "Scan"},
                    {"type": "button", "id": "products", "label": "Search", "kind": "search"},
                    {"type": "nav", "id": "go", "label": "Lists", "page": "lists"}
                  ]
                },
                {
                  "id": "lists",
                  "label": "Lists",
                  "widgets": [
                    {"type": "nav", "id": "back", "label": "Home", "page": "home"}
                  ]
                }
              ],
              "actions": [{"id": "ignored", "label": "Ignored"}]
            }
            """
        )

        val config = UiConfigParser.parse(data)!!

        assertEquals(3, config.schema)
        assertEquals("lists", config.defaultPage)
        assertEquals(2, config.pages.size)
        assertEquals(3, config.pages[0].widgets.size)
        assertEquals(UiAction.KIND_SEARCH, config.pages[0].widgets[1].kind)
        assertEquals("lists", config.pages[0].widgets[2].page)
        assertEquals(0, config.actions.size)
    }

    @Test
    fun parsesFlattenedSchema3Pages() {
        val data = json(
            """
            {
              "schema": 3,
              "slot_1_id": "use",
              "slot_1_label": "Use",
              "slot_1_behavior": "use",
              "page_1_id": "home",
              "page_1_label": "Home",
              "page_2_id": "lists",
              "page_2_label": "Lists",
              "widget_1_page": "home",
              "widget_1_type": "nav",
              "widget_1_id": "go",
              "widget_1_label": "Lists",
              "widget_1_target": "lists",
              "widget_2_page": "lists",
              "widget_2_type": "button",
              "widget_2_id": "pick",
              "widget_2_label": "Pick",
              "widget_2_kind": "event"
            }
            """
        )

        val config = UiConfigParser.parse(data)!!

        assertEquals("home", config.defaultPage)
        assertEquals(2, config.pages.size)
        assertEquals(UiWidget.TYPE_NAV, config.pages[0].widgets[0].type)
        assertEquals("lists", config.pages[0].widgets[0].page)
        assertEquals(1, config.pages[1].widgets.size)
    }

    @Test
    fun overlayParserParsesSetPage() {
        val command = OverlayParser.parse(
            json("""{"data": {"command": "set_page", "page": "Lists"}}""")
        )!!
        assertEquals(OverlayAction.SET_PAGE, command.action)
        assertEquals("lists", command.page)
        assertNull(
            OverlayParser.parse(json("""{"data": {"command": "set_page"}}"""))
        )
    }

    @Test
    fun parsesSchema2ActionsAndCustomBehavior() {
        val data = json(
            """
            {
              "schema": 2,
              "default": "check",
              "slots": [
                {"id": "use", "label": "Use", "behavior": "use"},
                {"id": "check", "label": "Check", "behavior": "custom"},
                {"id": "", "label": "", "behavior": "use"}
              ],
              "actions": [
                {"id": "lists", "label": "Lists"},
                {"id": "", "label": ""},
                {"id": "note", "label": "Note"}
              ]
            }
            """
        )

        val config = UiConfigParser.parse(data)!!

        assertEquals(2, config.schema)
        assertEquals("check", config.defaultMode)
        assertEquals(UiConfig.BEHAVIOR_CUSTOM, config.behaviorFor("check"))
        assertEquals(2, config.actions.size)
        assertEquals("lists", config.actions[0].id)
        assertEquals("note", config.actions[1].id)
    }

    @Test
    fun schema1CoercesCustomBehaviorAndIgnoresActions() {
        val data = json(
            """
            {
              "schema": 1,
              "slots": [
                {"id": "check", "label": "Check", "behavior": "custom"}
              ],
              "actions": [
                {"id": "lists", "label": "Lists"}
              ]
            }
            """
        )

        val config = UiConfigParser.parse(data)!!

        assertEquals(1, config.schema)
        assertEquals(UiConfig.BEHAVIOR_USE, config.behaviorFor("check"))
        assertEquals(0, config.actions.size)
    }

    @Test
    fun parsesFlattenedSchema2Actions() {
        val data = json(
            """
            {
              "schema": 2,
              "slot_1_id": "use",
              "slot_1_label": "Use",
              "slot_1_behavior": "use",
              "action_1_id": "lists",
              "action_1_label": "Lists",
              "action_1_kind": "search",
              "action_2_id": "",
              "action_2_label": ""
            }
            """
        )

        val config = UiConfigParser.parse(data)!!

        assertEquals(1, config.actions.size)
        assertEquals("lists", config.actions.first().id)
        assertEquals(UiAction.KIND_SEARCH, config.actions.first().kind)
    }

    @Test
    fun overlayParserParsesSearchAndResults() {
        val search = OverlayParser.parse(
            json(
                """
                {
                  "data": {
                    "command": "search",
                    "id": "products",
                    "title": "Find product",
                    "placeholder": "Name or barcode",
                    "query": "flour"
                  }
                }
                """
            )
        )!!
        val results = OverlayParser.parse(
            json(
                """
                {
                  "data": {
                    "command": "search_results",
                    "id": "products",
                    "items": [
                      {"id": "1", "label": "Plain flour", "subtitle": "1kg"}
                    ]
                  }
                }
                """
            )
        )!!
        val emptyResults = OverlayParser.parse(
            json("""{"data": {"command": "search_results", "id": "products", "items": []}}""")
        )!!

        assertEquals(OverlayAction.SEARCH, search.action)
        assertEquals("products", search.search?.id)
        assertEquals("flour", search.search?.query)
        assertEquals(OverlayAction.SEARCH_RESULTS, results.action)
        assertEquals(1, results.list?.items?.size)
        assertEquals(0, emptyResults.list?.items?.size)
        assertNull(
            OverlayParser.parse(json("""{"data": {"command": "search", "title": "Missing id"}}"""))
        )
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

    @Test
    fun overlayParserParsesToastFormAndList() {
        val toast = OverlayParser.parse(
            json(
                """
                {
                  "data": {
                    "command": "toast",
                    "message": "Lookup failed",
                    "level": "error",
                    "duration": "long",
                    "beep": "error"
                  }
                }
                """
            )
        )!!
        val form = OverlayParser.parse(
            json(
                """
                {
                  "data": {
                    "command": "form",
                    "id": "add_note",
                    "title": "Add note",
                    "confirm_label": "Save",
                    "fields": [
                      {"id": "note", "label": "Note", "type": "text", "value": "hello"},
                      {"id": "qty", "label": "Qty", "type": "number"}
                    ]
                  }
                }
                """
            )
        )!!
        val list = OverlayParser.parse(
            json(
                """
                {
                  "data": {
                    "command": "picker",
                    "id": "pick_list",
                    "title": "Shopping lists",
                    "filter": true,
                    "items": [
                      {"id": "groceries", "label": "Groceries", "subtitle": "Weekly"},
                      {"id": "hardware", "label": "Hardware"}
                    ]
                  }
                }
                """
            )
        )!!

        assertEquals(OverlayAction.TOAST, toast.action)
        assertEquals("Lookup failed", toast.toast?.message)
        assertEquals(ToastPayload.LEVEL_ERROR, toast.toast?.level)
        assertEquals(true, toast.toast?.durationLong)
        assertEquals("error", toast.beep)

        assertEquals(OverlayAction.FORM, form.action)
        assertEquals("add_note", form.form?.id)
        assertEquals(2, form.form?.fields?.size)
        assertEquals(FormField.TYPE_NUMBER, form.form?.fields?.get(1)?.type)
        assertEquals("Save", form.form?.confirmLabel)

        assertEquals(OverlayAction.LIST, list.action)
        assertEquals("pick_list", list.list?.id)
        assertEquals(2, list.list?.items?.size)
        assertEquals(true, list.list?.filter)
    }

    @Test
    fun overlayParserParsesRichFormFields() {
        val form = OverlayParser.parse(
            json(
                """
                {
                  "data": {
                    "command": "form",
                    "id": "receive_stock",
                    "title": "Receive",
                    "fields": [
                      {"type": "barcode", "id": "code", "label": "Barcode"},
                      {"type": "number", "id": "qty", "label": "Qty", "value": "1"},
                      {"type": "select", "id": "unit", "label": "Unit", "options": ["pcs", "g"], "value": "pcs"},
                      {"type": "toggle", "id": "opened", "label": "Already open", "value": true}
                    ]
                  }
                }
                """
            )
        )!!

        val fields = form.form!!.fields
        assertEquals(4, fields.size)
        assertEquals(FormField.TYPE_BARCODE, fields[0].type)
        assertEquals(FormField.TYPE_NUMBER, fields[1].type)
        assertEquals(FormField.TYPE_SELECT, fields[2].type)
        assertEquals(2, fields[2].options.size)
        assertEquals("pcs", fields[2].options[0].id)
        assertEquals("pcs", fields[2].value)
        assertEquals(FormField.TYPE_TOGGLE, fields[3].type)
        assertEquals("true", fields[3].value)
    }

    @Test
    fun overlayParserDropsSelectWithoutOptionsAndCapsOptions() {
        val options = (1..25).joinToString(",") { """{"id": "o$it", "label": "Option $it"}""" }
        val form = OverlayParser.parse(
            json(
                """
                {
                  "data": {
                    "command": "form",
                    "id": "mixed",
                    "fields": [
                      {"id": "bad", "label": "Bad", "type": "select", "options": []},
                      {"id": "ok", "label": "Ok", "type": "dropdown", "options": [$options]},
                      {"id": "sw", "label": "Switch", "type": "checkbox", "value": "yes"}
                    ]
                  }
                }
                """
            )
        )!!

        assertEquals(2, form.form?.fields?.size)
        assertEquals("ok", form.form?.fields?.get(0)?.id)
        assertEquals(20, form.form?.fields?.get(0)?.options?.size)
        assertEquals(FormField.TYPE_TOGGLE, form.form?.fields?.get(1)?.type)
        assertEquals("true", form.form?.fields?.get(1)?.value)
    }

    @Test
    fun overlayParserAcceptsJsonStringListItems() {
        val results = OverlayParser.parse(
            json(
                """
                {
                  "message": "search_results",
                  "data": {
                    "command": "search_results",
                    "id": "products",
                    "items": "[{\"id\":\"200012570\",\"label\":\"Plain flour\",\"subtitle\":\"1 kg\"},{\"id\":\"200012571\",\"label\":\"Self-raising flour\",\"subtitle\":\"500 g\"}]"
                  }
                }
                """
            )
        )!!
        assertEquals(OverlayAction.SEARCH_RESULTS, results.action)
        assertEquals(2, results.list?.items?.size)
        assertEquals("Plain flour", results.list?.items?.get(0)?.label)
        assertEquals("1 kg", results.list?.items?.get(0)?.subtitle)
    }

    @Test
    fun overlayParserRejectsInvalidToastFormAndList() {
        assertNull(
            OverlayParser.parse(json("""{"data": {"command": "toast"}}"""))
        )
        assertNull(
            OverlayParser.parse(
                json("""{"data": {"command": "form", "title": "Missing id", "fields": [{"id": "a", "label": "A"}]}}""")
            )
        )
        assertNull(
            OverlayParser.parse(
                json("""{"data": {"command": "form", "id": "empty", "fields": []}}""")
            )
        )
        assertNull(
            OverlayParser.parse(
                json("""{"data": {"command": "list", "id": "empty", "items": []}}""")
            )
        )
    }

    @Test
    fun overlayParserCapsFormFieldsAndListItems() {
        val fields = (1..6).joinToString(",") { """{"id": "f$it", "label": "Field $it"}""" }
        val items = (1..45).joinToString(",") { """{"id": "i$it", "label": "Item $it"}""" }
        val form = OverlayParser.parse(
            json("""{"data": {"command": "form", "id": "cap", "fields": [$fields]}}""")
        )!!
        val list = OverlayParser.parse(
            json("""{"data": {"command": "list", "id": "cap", "items": [$items]}}""")
        )!!

        assertEquals(4, form.form?.fields?.size)
        assertEquals(40, list.list?.items?.size)
    }

    @Test
    fun parsesBlueprintFlatSchema3Payload() {
        val event = json(
            """
            {
              "message": "ui_config",
              "data": {
                "command": "ui_config",
                "schema": 3,
                "default": "use",
                "default_page": "home",
                "slots": [
                  {"id": "use", "label": "Use", "behavior": "use"},
                  {"id": "shopping", "label": "Shopping", "behavior": "shopping"},
                  {"id": "", "label": "", "behavior": "use"},
                  {"id": "", "label": "", "behavior": "use"}
                ],
                "page_1_id": "home",
                "page_1_label": "Home",
                "page_2_id": "",
                "page_2_label": "",
                "page_3_id": "",
                "page_3_label": "",
                "widget_1_page": "home",
                "widget_1_type": "text",
                "widget_1_id": "hint",
                "widget_1_label": "Scan a product barcode",
                "widget_1_kind": "event",
                "widget_1_target": "",
                "widget_2_page": "home",
                "widget_2_type": "button",
                "widget_2_id": "products",
                "widget_2_label": "Search",
                "widget_2_kind": "search",
                "widget_2_target": "",
                "actions": [
                  {"id": "", "label": "", "kind": "event"},
                  {"id": "", "label": "", "kind": "event"},
                  {"id": "", "label": "", "kind": "event"},
                  {"id": "", "label": "", "kind": "event"}
                ]
              }
            }
            """
        )
        val command = OverlayParser.parse(event)!!
        assertEquals(OverlayAction.UI_CONFIG, command.action)
        val config = command.uiConfig
        assertEquals("expected ui_config to parse, got null", true, config != null)
        assertEquals(3, config!!.schema)
        assertEquals(2, config.slots.size)
        assertEquals(1, config.pages.size)
        assertEquals("home", config.defaultPage)
        assertEquals(2, config.page("home")!!.widgets.size)
    }

    @Test
    fun parsesFlattenedSlotsWhenNestedSlotsMissing() {
        val config = UiConfigParser.parse(
            json(
                """
                {
                  "schema": 3,
                  "default": "use",
                  "default_page": "home",
                  "slot_1_id": "use",
                  "slot_1_label": "Use",
                  "slot_1_behavior": "use",
                  "slot_2_id": "shopping",
                  "slot_2_label": "Shopping",
                  "slot_2_behavior": "shopping",
                  "page_1_id": "home",
                  "page_1_label": "Home",
                  "widget_1_page": "home",
                  "widget_1_type": "text",
                  "widget_1_id": "hint",
                  "widget_1_label": "Scan"
                }
                """
            )
        )!!
        assertEquals(2, config.slots.size)
        assertEquals(1, config.pages.size)
    }

    private fun json(value: String) = JsonParser.parseString(value).asJsonObject
}
