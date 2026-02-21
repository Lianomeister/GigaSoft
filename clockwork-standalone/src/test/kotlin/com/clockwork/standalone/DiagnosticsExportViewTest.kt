package com.clockwork.standalone

import kotlin.test.Test
import kotlin.test.assertTrue

class DiagnosticsExportViewTest {
    @Test
    fun `render diagnostics html includes plugin rows and recommendation classes`() {
        val html = renderDiagnosticsHtml(
            generatedAtUtc = "2026-02-21T00:00:00Z",
            pluginIds = listOf("clockwork-demo"),
            recommendations = mapOf(
                "clockwork-demo" to listOf(
                    OperatorRecommendation(
                        code = "FAULT_BUDGET_PRESSURE",
                        severity = "warning",
                        errorClass = "stability",
                        message = "budget pressure"
                    )
                )
            ),
            profiles = mapOf("clockwork-demo" to null)
        )

        assertTrue(html.contains("Clockwork Diagnostics Preview"))
        assertTrue(html.contains("clockwork-demo"))
        assertTrue(html.contains("FAULT_BUDGET_PRESSURE(warning/stability)"))
    }
}
