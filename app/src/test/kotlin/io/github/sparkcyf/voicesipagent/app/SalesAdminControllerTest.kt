package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verify

class SalesAdminControllerTest {
    @Test
    fun `price is staged then saved only after explicit approval`() {
        val store = mock(SalesAdminStore::class.java)
        val controller = SalesAdminController(store)
        val userId = 77123L

        assertTrue(controller.handleCallback("sales:price", userId).text.contains("أرسل سعراً"))
        val preview = controller.handleText("صنعاء | 500.50 | YER | لتر | 2026-11-25 | 2026-12-05", userId)!!
        assertTrue(preview.text.contains("500.5 YER"))
        val approval = preview.buttons.single().first().callbackData
        assertTrue(approval.startsWith("sales:save_price:"))

        controller.handleCallback(approval, userId)
        verify(store).savePrice(DieselPrice("صنعاء", "500.5", "YER", "لتر", "2026-11-25", "2026-12-05"), userId)
    }

    @Test
    fun `invalid dates do not create an approval action`() {
        val controller = SalesAdminController(mock(SalesAdminStore::class.java))
        controller.handleCallback("sales:price", 1L)
        val reply = controller.handleText("عدن | 600 | YER | لتر | 25-11-2026 | 2026-12-05", 1L)!!
        assertTrue(reply.text.contains("تاريخ البداية غير صالح"))
        assertTrue(reply.buttons.isEmpty())
    }

    @Test
    fun `stale approval button cannot save a newer preview`() {
        val store = mock(SalesAdminStore::class.java)
        val controller = SalesAdminController(store)
        controller.handleCallback("sales:price", 1L)
        val oldButton = controller.handleText("صنعاء | 500 | YER | لتر | 2026-11-25 | 2026-12-05", 1L)!!
            .buttons.single().first().callbackData
        controller.handleCallback("sales:price", 1L)
        controller.handleText("عدن | 600 | YER | لتر | 2026-11-25 | 2026-12-05", 1L)

        assertTrue(controller.handleCallback(oldButton, 1L).text.contains("قديم"))
        verifyNoInteractions(store)
    }

    @Test
    fun `coverage replacement requires explicit confirmation`() {
        val store = mock(SalesAdminStore::class.java)
        val controller = SalesAdminController(store)
        val userId = 55L

        controller.handleCallback("sales:coverage", userId)
        val preview = controller.handleText("صنعاء | مؤكدة\nعدن | تحتاج تحقق", userId)!!
        val approval = preview.buttons.single().first().callbackData
        assertTrue(approval.startsWith("sales:save_coverage:"))
        controller.handleCallback(approval, userId)
        verify(store).saveCoverage(listOf(CoverageEntry("صنعاء", true), CoverageEntry("عدن", false)), userId)
    }
}
