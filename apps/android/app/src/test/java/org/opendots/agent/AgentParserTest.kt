package org.opendots.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentParserTest {
    @Test
    fun parsesOpenApp() {
        assertEquals("WhatsApp", AgentParser.openAppTarget("Open WhatsApp"))
        assertEquals("WhatsApp", AgentParser.openAppTarget("WhatsApp open karo"))
    }

    @Test
    fun parsesMemory() {
        assertEquals("I prefer dark mode", AgentParser.memoryText("Remember that I prefer dark mode"))
    }

    @Test
    fun detectsConsequentialMessageSeparately() {
        val draft = AgentParser.sendMessageDraft("Message Rahul that I am coming in 10 minutes")
        assertNotNull(draft)
        assertEquals("Rahul", draft!!.recipient)
    }
}
