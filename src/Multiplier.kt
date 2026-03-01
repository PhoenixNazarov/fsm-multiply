class Multiplier(
    private val automaton1: Automaton,
    private val automaton2: Automaton,
) {
    private fun executeSpawn(calculateAutomatonSpawn: CalculateAutomatonSpawn2): List<CalculateAutomatonExecution> {
        val calculateAutomatonSpawns = mutableListOf<Pair<CalculateAutomatonSpawn2, List<Transition>>>()
        if (calculateAutomatonSpawn.subCalculateAutomaton.automaton.id.any { it in calculateAutomatonSpawn.handleCalculateAutomaton.state.nestedFsmIds }) {
            val nestedProcess = executeSpawn(
                calculateAutomatonSpawn.copy(
                    handleCalculateAutomaton = calculateAutomatonSpawn.subCalculateAutomaton,
                    subCalculateAutomaton = calculateAutomatonSpawn.handleCalculateAutomaton,
                )
            )
            calculateAutomatonSpawns.addAll(
                nestedProcess.map {
                    Pair(
                        CalculateAutomatonSpawn2(
                            handleCalculateAutomaton = it.state.second,
                            subCalculateAutomaton = it.state.first,
                            event = calculateAutomatonSpawn.event
                        ), it.transitions
                    )
                }
            )
        } else {
            calculateAutomatonSpawns.add(Pair(calculateAutomatonSpawn, listOf()))
        }
        val subSpawns = calculateAutomatonSpawns.flatMap {
            val subSpawns = it.first.makeSubSpawns()

            subSpawns.map { subSpawn ->
                subSpawn.copy(transitions = (it.second + subSpawn.transitions).toMutableList())
            }
        }
        if (subSpawns.isEmpty()) {
            return calculateAutomatonSpawns.map {
                CalculateAutomatonExecution(
                    state = CalculateStateWithEntries(
                        it.first.handleCalculateAutomaton,
                        it.first.subCalculateAutomaton,
                    ),
                    transitions = it.second
                )
            }
        }
        val nextSubSpawns = mutableListOf<CalculateAutomatonSubSpawn>()

        // Transition Events
        for (subSpawn in subSpawns) {
            val automateTransitionEvents =
                calculateAutomatonSpawn.handleCalculateAutomaton.getEvents(subSpawn.transition.enterEventsId)
                    .filterIsInstance<AutomationEventInput>()
                    .filter { it.automationId in calculateAutomatonSpawn.subCalculateAutomaton.automaton.id }
            if (automateTransitionEvents.isEmpty()) {
                nextSubSpawns.add(subSpawn)
            }
            for (it in automateTransitionEvents) {
                val nextStates = executeSpawn(
                    CalculateAutomatonSpawn2(
                        handleCalculateAutomaton = subSpawn.second,
                        subCalculateAutomaton = subSpawn.first,
                        event = it
                    )
                )
                nextSubSpawns.addAll(nextStates.map {
                    subSpawn.copy(
                        first = it.state.second,
                        second = it.state.first,
                        stateEvents = subSpawn.stateEvents + it.state.enterEvents
                    ).apply {
                        transitions.addAll(it.transitions)
                    }
                })
                break // TODO: MAYBE FIX
            }
        }

        // Change State
        val changedStates = nextSubSpawns.map {
            it.copy(
                first = it.first.copy(state = it.state),
                second = it.second,
                stateEvents = (it.stateEvents + it.first.getEvents(it.state.enterEventsId)
                    .filter { it2 -> filterEvent(it.first, it.second, it2) }
                        )
            )
        }

        val resultSubSpawns = mutableListOf<CalculateAutomatonSubSpawn>()

        // StateEvents
        for (setState in changedStates) {
            val automateStateEvents =
                calculateAutomatonSpawn.handleCalculateAutomaton.getEvents(setState.state.enterEventsId)
                    .filterIsInstance<AutomationEventInput>()
                    .filter { it.automationId in calculateAutomatonSpawn.subCalculateAutomaton.automaton.id }
            if (automateStateEvents.isEmpty()) {
                resultSubSpawns.add(setState)
            }
            for (it in automateStateEvents) {
                val calculateSpawns = executeSpawn(
                    CalculateAutomatonSpawn2(
                        handleCalculateAutomaton = setState.second,
                        subCalculateAutomaton = setState.first,
                        event = it
                    )
                )
                resultSubSpawns.addAll(calculateSpawns.map {
                    setState.copy(
                        first = it.state.second,
                        second = it.state.first,
                        stateEvents = setState.stateEvents + it.state.enterEvents
                    ).apply {
                        transitions.addAll(it.transitions)
                    }
                })
                break // TODO: FIX
            }
        }

        return resultSubSpawns.map {
            CalculateAutomatonExecution(
                state = CalculateStateWithEntries(it.first, it.second, it.stateEvents), transitions = it.transitions
            )
        }
    }

    fun multiply(): Automaton {
        val calc = createCalculateStateWithEntries(
            CalculateAutomaton(automaton = automaton1, state = automaton1.startState),
            CalculateAutomaton(automaton = automaton2, state = automaton2.startState),
            (automaton1.getEvents(automaton1.startState.enterEventsId) + automaton2.getEvents(automaton2.startState.enterEventsId)).toSet()
        )

        val stack = mutableListOf(CalculateAutomatonExecution(state = calc))
        val executions = mutableSetOf<CalculateAutomatonExecution>()
        val calculateTransitions =
            mutableMapOf<CalculateStateWithEntries, MutableMap<CalculateStateWithEntries, MutableSet<List<Transition>>>>()
        while (stack.isNotEmpty()) {
            var process = stack.removeAt(0)
            if (process.state.first.automaton.id.joinToString(",") > process.state.second.automaton.id.joinToString(",")) {
                process = process.copy(
                    state = CalculateStateWithEntries(
                        process.state.second,
                        process.state.first,
                        enterEvents = process.state.enterEvents
                    )
                )
            }
            if (process in executions) {
                continue
            }
            executions.add(process)

            val envSpawns =
                process.state.first.getSpawn(process.state.second) + process.state.second.getSpawn(process.state.first)

            val newSpawn = envSpawns.flatMap { executeSpawn(it) }.filter { it.transitions.isNotEmpty() }

            if (process.state !in calculateTransitions) {
                calculateTransitions[process.state] = mutableMapOf()
            }
            newSpawn.forEach {
                var orderIt = it
                if (orderIt.state.first.automaton.id.joinToString(",") > orderIt.state.second.automaton.id.joinToString(
                        ","
                    )
                ) {
                    orderIt = orderIt.copy(
                        state = CalculateStateWithEntries(
                            orderIt.state.second,
                            orderIt.state.first,
                            orderIt.state.enterEvents
                        )
                    )
                }
                if (calculateTransitions[process.state]?.contains(orderIt.state) != true) {
                    calculateTransitions[process.state]?.set(orderIt.state, mutableSetOf())
                }

                if (orderIt.transitions.isNotEmpty()) {
                    calculateTransitions[process.state]?.get(orderIt.state)?.add(orderIt.transitions)
                }
            }

            stack.addAll(newSpawn)
        }

        var id = 0
        val states = executions.associate {
            CalculateStateWithEntriesId(
                it.state.first.state.id,
                it.state.second.state.id,
                it.state.enterEvents.map { it2 -> it2.id }.toSet(),
            ) to State(
                name = "${it.state.first.state.name} * ${it.state.second.state.name}",
                id = id++,
                referenceId = listOf(),
                enterEventsId = it.state.enterEvents.map { it2 -> it2.id },
                nestedFsmIds = (it.state.first.state.nestedFsmIds + it.state.second.state.nestedFsmIds).distinct()
                    .filter { it2 ->
                        it2 !in it.state.first.automaton.id + it.state.second.automaton.id
                    },
                referencesUUID = it.state.first.state.referencesUUID + it.state.second.state.referencesUUID + it.state.first.state.uuid + it.state.second.state.uuid,
            )
        }

        val allEvents = (automaton1.events + automaton2.events).distinct().filter {
            when (it) {
                is EnvironmentEventInput -> true
                is AutomationEventInput -> !(automaton1.id + automaton2.id).contains(it.automationId)
                is AutomationStateEventInput -> !(automaton1.id + automaton2.id).contains(it.automationId)
            }
        }
        val allEventsById = allEvents.associateBy { it.id }

        val transitions = calculateTransitions.flatMap {
            val fromKey = CalculateStateWithEntriesId(
                it.key.first.state.id,
                it.key.second.state.id,
                it.key.enterEvents.map { it2 -> it2.id }.toSet(),
            )
            val stateFrom = states[fromKey]!!
            it.value.flatMap { it2 ->
                val toKey = CalculateStateWithEntriesId(
                    it2.key.first.state.id,
                    it2.key.second.state.id,
                    it2.key.enterEvents.map { it3 -> it3.id }.toSet(),
                )
                val stateTo = states[toKey]!!

                it2.value.map { it3 ->
                    Transition(
                        fromStateId = stateFrom.id,
                        toStateId = stateTo.id,
                        eventId = it3[0].eventId,
                        inputIds = it3
                            .flatMap { it4 -> it4.inputIds }
                            .filter { it4 -> it4 in allEventsById },
                        enterEventsId = it3
                            .flatMap { it4 -> it4.enterEventsId }
                            .filter { it4 -> it4 in allEventsById },
                        referencesUUID = it3.flatMap { it4 -> it4.referencesUUID + it4.uuid }
                    )
                }
            }
        }

        return Automaton(
            name = "${automaton1.name} x ${automaton2.name}",
            id = automaton1.id + automaton2.id,
            startStateId = states[CalculateStateWithEntriesId(
                calc.first.state.id,
                calc.second.state.id,
                calc.enterEvents.map { it.id }.toSet()
            )]!!.id,
            states = states.values.toList(),
            events = allEvents,
            transitions = transitions
        )
    }
}