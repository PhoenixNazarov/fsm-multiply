import kotlin.random.Random


typealias OutputEvent = EventInput


class StateAutomatons(
    private val automatons: List<Automaton>,
    private val maxProcessedEvents: Int = 10_000,
) {
    private val automatonById: Map<String, Automaton> =
        automatons.flatMap { automaton -> automaton.id.map { id -> id to automaton } }.toMap()


    private val currentStateByAutomaton: MutableMap<Automaton, State> =
        automatons.associateWith { it.startState }.toMutableMap()


    fun currentStates(): Map<String, String> =
        currentStateByAutomaton
            .mapKeys { (automaton, _) -> automaton.id.joinToString("") }
            .mapValues { it.value.name }


    fun snapshot(): Map<String, Int> =
        currentStateByAutomaton
            .mapKeys { (automaton, _) -> automaton.id.joinToString("") }
            .mapValues { (_, state) -> state.id }


    fun restore(snapshot: Map<String, Int>) {
        currentStateByAutomaton.replaceAll { automaton, _ ->
            automaton.getState(snapshot.getValue(automaton.id.joinToString("")))
        }
    }


    fun reset() {
        currentStateByAutomaton.replaceAll { automaton, _ -> automaton.startState }
    }


    fun execute(eventId: String): List<OutputEvent> {
        val outputEvents = mutableListOf<OutputEvent>()


        val automaton = automatons.firstOrNull { automaton ->
            hasExternalInputEvent(automaton, eventId)
        } ?: return emptyList()


        executeWithNested(
            automaton = automaton,
            eventId = eventId,
            outputEvents = outputEvents,
            depth = 0,
            visited = emptySet(),
        )


        return outputEvents
    }


    private fun executeWithNested(
        automaton: Automaton,
        eventId: String,
        outputEvents: MutableList<OutputEvent>,
        depth: Int,
        visited: Set<Automaton>,
    ) {
        if (depth > maxProcessedEvents) {
            error("Too many nested automaton events. Possible event cycle.")
        }


        if (automaton in visited) {
            return
        }


        val currentState = currentStateByAutomaton.getValue(automaton)


        val nestedAutomatons =
            currentState.nestedFsmIds
                .mapNotNull { nestedAutomatonId -> automatonById[nestedAutomatonId] }
                .distinct()


        for (nestedAutomaton in nestedAutomatons) {
            executeWithNested(
                automaton = nestedAutomaton,
                eventId = eventId,
                outputEvents = outputEvents,
                depth = depth + 1,
                visited = visited + automaton,
            )
        }


        executeAutomaton(
            automaton = automaton,
            eventId = eventId,
            outputEvents = outputEvents,
            depth = depth,
        )
    }


    private fun hasExternalInputEvent(
        automaton: Automaton,
        eventId: String,
    ): Boolean {
        val event = automaton.events.firstOrNull { it.id == eventId }
        return event is EnvironmentEventInput
    }


    private fun executeAutomaton(
        automaton: Automaton,
        eventId: String,
        outputEvents: MutableList<OutputEvent>,
        depth: Int,
    ) {
        if (depth > maxProcessedEvents) {
            error("Too many nested automaton events. Possible event cycle.")
        }


        val currentState = currentStateByAutomaton.getValue(automaton)


        val transition = findTransition(
            automaton = automaton,
            state = currentState,
            eventId = eventId,
        ) ?: return


        val nextState = automaton.getState(transition.toStateId)
        currentStateByAutomaton[automaton] = nextState


        val events = automaton.getEvents(
            transition.enterEventsId + nextState.enterEventsId
        )


        for (event in events) {
            if (event is AutomationEventInput && event.automationId in automatonById) {
                executeAutomaton(
                    automaton = getAutomaton(event.automationId),
                    eventId = event.id,
                    outputEvents = outputEvents,
                    depth = depth + 1,
                )
            } else {
                outputEvents += event
            }
        }
    }


    private fun getAutomaton(automatonId: String): Automaton =
        automatonById[automatonId] ?: error("Automaton with id=$automatonId not found")


    private fun findTransition(
        automaton: Automaton,
        state: State,
        eventId: String,
    ): Transition? {
        val transitions = automaton.getTransitionFromState(state)


        val exactTransitions = transitions.filter { it.eventId == eventId }
        val candidates = exactTransitions.ifEmpty {
            transitions.filter { it.eventId == "*" }
        }


        return candidates.firstOrNull { transition ->
            canExecuteTransition(automaton, transition)
        }
    }


    private fun canExecuteTransition(
        automaton: Automaton,
        transition: Transition,
    ): Boolean {
        return automaton.getEvents(transition.inputIds).all { input ->
            when (input) {
                is EnvironmentEventInput -> true


                is AutomationEventInput ->
                    input.automationId !in automatonById


                is AutomationStateEventInput -> {
                    val targetAutomaton = automatonById[input.automationId] ?: return@all true
                    val targetState = currentStateByAutomaton.getValue(targetAutomaton)


                    if (input.eq) {
                        targetState.id == input.stateId
                    } else {
                        targetState.id != input.stateId
                    }
                }
            }
        }
    }
}


fun checkEquivalence(
    systemAutomatons: List<Automaton>,
    singleAutomaton: Automaton,
    iterations: Int = 1_000,
    sequenceLength: Int = 100,
    seed: Int = 42,
    bruteForceDepth: Int = 20,
): Boolean {
    val random = Random(seed)


    val inputEvents =
        (systemAutomatons.flatMap { it.events } + singleAutomaton.events)
            .filterIsInstance<EnvironmentEventInput>()
            .map { it.id }
            .distinct()


    val system = StateAutomatons(systemAutomatons)
    val single = StateAutomatons(listOf(singleAutomaton))


    fun printMismatch(
        label: String,
        step: Int,
        eventId: String,
        eventsHistory: List<String>,
        systemOutput: List<String>,
        singleOutput: List<String>,
    ) {
        println("Not equivalent")
        println("Mode: $label")
        println("Step: $step")
        println("Event: $eventId")
        println("Events: $eventsHistory")
        println("System output: $systemOutput")
        println("System state: ${system.currentStates()}")
        println("Single output: $singleOutput")
        println("Single state: ${single.currentStates()}")
    }


    data class SeenState(
        val systemState: Map<String, Int>,
        val singleState: Map<String, Int>,
        val depthLeft: Int,
    )


    fun checkAllCombinationsFast(
        depthLeft: Int,
        eventsHistory: MutableList<String>,
        seen: MutableSet<SeenState>,
    ): Boolean {
        val seenState = SeenState(
            systemState = system.snapshot(),
            singleState = single.snapshot(),
            depthLeft = depthLeft,
        )


        if (!seen.add(seenState)) {
            return true
        }


        if (depthLeft == 0) {
            return true
        }


        for (eventId in inputEvents) {
            val systemBefore = system.snapshot()
            val singleBefore = single.snapshot()


            eventsHistory.add(eventId)


            val systemOutput = system.execute(eventId).map { it.id }
            val singleOutput = single.execute(eventId).map { it.id }


            if (systemOutput != singleOutput) {
                printMismatch(
                    label = "all combinations depth=$bruteForceDepth",
                    step = eventsHistory.size - 1,
                    eventId = eventId,
                    eventsHistory = eventsHistory,
                    systemOutput = systemOutput,
                    singleOutput = singleOutput,
                )
                return false
            }


            if (!checkAllCombinationsFast(depthLeft - 1, eventsHistory, seen)) {
                return false
            }


            eventsHistory.removeAt(eventsHistory.lastIndex)
            system.restore(systemBefore)
            single.restore(singleBefore)
        }


        return true
    }


    if (!checkAllCombinationsFast(
            depthLeft = bruteForceDepth,
            eventsHistory = mutableListOf(),
            seen = mutableSetOf(),
        )
    ) {
        return false
    }


    repeat(iterations) { iteration ->
        system.reset()
        single.reset()


        val eventsHistory = mutableListOf<String>()


        repeat(sequenceLength) { step ->
            val eventId = inputEvents.random(random)
            eventsHistory.add(eventId)


            val systemOutput = system.execute(eventId).map { it.id }
            val singleOutput = single.execute(eventId).map { it.id }


            if (systemOutput != singleOutput) {
                printMismatch(
                    label = "random iteration=$iteration",
                    step = step,
                    eventId = eventId,
                    eventsHistory = eventsHistory,
                    systemOutput = systemOutput,
                    singleOutput = singleOutput,
                )
                return false
            }
        }
    }


    return true
}
