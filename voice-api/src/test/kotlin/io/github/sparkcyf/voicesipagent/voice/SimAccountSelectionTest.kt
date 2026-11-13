package io.github.sparkcyf.voicesipagent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimAccountSelectionTest {
    private data class Account(val key: String, val isSim: Boolean)

    @Test
    fun selectsTheRequestedSimWhenMultipleAccountsExist() {
        val simOne = Account("sim-one", true)
        val simTwo = Account("sim-two", true)
        val voip = Account("voip", false)
        val result = selectSimAccount("sim-two", listOf(simOne, simTwo, voip), Account::key, Account::isSim)
        assertEquals(simTwo, result)
    }

    @Test
    fun neverFallsBackToAnotherSimOrVoipAccount() {
        val accounts = listOf(Account("sim-one", true), Account("voip", false))
        assertNull(selectSimAccount("sim-missing", accounts, Account::key, Account::isSim))
        assertNull(selectSimAccount("voip", accounts, Account::key, Account::isSim))
    }

    @Test
    fun blankSelectionLeavesAndroidDefaultUnchanged() {
        assertNull(selectSimAccount("", listOf(Account("sim-one", true)), Account::key, Account::isSim))
    }
}
