import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MultiplierTest {
    @Test
    fun `undependent event`() {
        val a1 = AutomatonBuilder("A1", listOf("A1"), 1)
            .environmentEvent("e1")
            .automationEvent("a1", "A2", "a1")
            .state(1, "A")
            .state(2, "B")
            .transition(1, 2, "e1")
            .build()

        val a2 = AutomatonBuilder("A2", listOf("A2"), 1)
            .environmentEvent("e2")
            .state(1, "C")
            .state(2, "D")
            .transition(1, 2, "e2")
            .build()

        val res = Multiplier(a1, a2).multiply()

        println(a1.toUML())
        println(a2.toUML())
        println(res.toUML())

        assertEquals(
            res.toUML(), """
                state "«A1 x A2» (A1A2)" as A1A2 {
                    [*] --> A1A20
                    state "A * C" as A1A20 :  
                    state "B * C" as A1A21 :  
                    state "A * D" as A1A22 :  
                    state "B * D" as A1A24 :  
                    A1A20 --> A1A21 : <u>e1\n~
                    A1A20 --> A1A22 : <u>e2\n~
                    A1A21 --> A1A24 : <u>e2\n~
                    A1A22 --> A1A24 : <u>e1\n~
                }
        """.trimIndent()
        )
    }

    @Test
    fun `dependent event`() {
        val a1 = AutomatonBuilder("A1", listOf("A1"), 1)
            .environmentEvent("e1")
            .automationEvent("a1", "A2", "a1")
            .state(1, "A")
            .state(2, "B")
            .transition(1, 2, "e1", listOf(), listOf("a1"))
            .build()

        val a2 = AutomatonBuilder("A2", listOf("A2"), 1)
            .automationEvent("a1", "A1", "a1")
            .state(1, "C")
            .state(2, "D")
            .transition(1, 2, "a1")
            .build()

        val res = Multiplier(a1, a2).multiply()

        assertEquals(
            res.toUML(), """
                state "«A1 x A2» (A1A2)" as A1A2 {
                    [*] --> A1A20
                    state "A * C" as A1A20 :  
                    state "B * D" as A1A21 :  
                    A1A20 --> A1A21 : <u>e1\n~
                }
        """.trimIndent()
        )
    }

    @Test
    fun `multiply nested 1`() {
        val a1 = AutomatonBuilder("A1", listOf("A1"), 1)
            .state(1, "A", nestedFsmIds = listOf("A2"))
            .build()

        val a2 = AutomatonBuilder("A2", listOf("A2"), 1)
            .automationEvent("e1", "A1", "e1")
            .state(1, "C")
            .state(2, "D")
            .transition(1, 2, "e1")
            .build()


        val res = Multiplier(a1, a2).multiply()

        assertEquals(
            res.toUML(), """
                state "«A1 x A2» (A1A2)" as A1A2 {
                    [*] --> A1A20
                    state "A * C" as A1A20 :  
                    state "A * D" as A1A21 :  
                    A1A20 --> A1A21 : <u>e1\n~
                }
        """.trimIndent()
        )
    }

    @Test
    fun `multiply nested with all event`() {
        val a1 = AutomatonBuilder("A1", listOf("A1"), 1)
            .environmentEvent("*", "*")
            .environmentEvent("z1", "z1")
            .state(1, "A", nestedFsmIds = listOf("A2"))
            .state(2, "B")
            .transition(1, 2, "*", enterEventsId = listOf("z1"))
            .build()

        val a2 = AutomatonBuilder("A2", listOf("A2"), 1)
            .automationEvent("e1", "A1", "e1")
            .environmentEvent("z2", "z2")
            .state(1, "C")
            .state(2, "D")
            .transition(1, 2, "e1", enterEventsId = listOf("z2"))
            .build()

        val res = Multiplier(a1, a2).multiply()

        assertEquals(
            res.toUML(), """
                state "«A1 x A2» (A1A2)" as A1A2 {
                    [*] --> A1A20
                    state "A * C" as A1A20 :  
                    state "B * C" as A1A21 :  
                    state "B * D" as A1A22 :  
                    A1A20 --> A1A21 : <u>*\nz1
                    A1A20 --> A1A22 : <u>e1\nz1,z2
                }
        """.trimIndent()
        )
    }

    @Test
    fun `multiply nested with all event only one`() {
        val a1 = AutomatonBuilder("A1", listOf("A1"), 1)
            .environmentEvent("*", "*")
            .environmentEvent("e1", "e1")
            .environmentEvent("z1", "z1")
            .state(1, "A", nestedFsmIds = listOf("A2"))
            .state(2, "B")
            .state(3, "C")
            .transition(1, 2, "*", enterEventsId = listOf("z1"))
            .transition(1, 3, "e1")
            .build()

        val a2 = AutomatonBuilder("A2", listOf("A2"), 1)
            .automationEvent("e1", "A1", "e1")
            .environmentEvent("z2", "z2")
            .state(1, "C")
            .state(2, "D")
            .transition(1, 2, "e1", enterEventsId = listOf("z2"))
            .build()

        val res = Multiplier(a1, a2).multiply()

        assertEquals(
            res.toUML(), """
                state "«A1 x A2» (A1A2)" as A1A2 {
                    [*] --> A1A20
                    state "A * C" as A1A20 :  
                    state "B * C" as A1A21 :  
                    state "C * D" as A1A22 :  
                    A1A20 --> A1A21 : <u>*\nz1
                    A1A20 --> A1A22 : <u>e1\nz2
                }
        """.trimIndent()
        )
    }

    @Test
    fun `multiply duplicateexit events`() {
        val a1 = AutomatonBuilder("B0", listOf("B0"), 1)
            .environmentEvent("z1", "z1")
            .state(1, "A", listOf("z1"))
            .build()

        val a2 = AutomatonBuilder("B1", listOf("B1"), 1)
            .automationEvent("e1", "A1", "e1")
            .state(1, "C")
            .transition(1, 1, "e1")
            .build()

        val res = Multiplier(a1, a2).multiply()

        println(a1.toUML())
        println(a2.toUML())
        println(res.toUML())

        assertEquals(
            res.toUML(), """
                state "«B0 x B1» (B0B1)" as B0B1 {
                    [*] --> B0B10
                    state "A * C" as B0B10 :  z1
                    state "A * C" as B0B11 :  
                    B0B10 --> B0B11 : <u>e1\n~
                    B0B11 --> B0B11 : <u>e1\n~
                }
        """.trimIndent()
        )
    }
}