fun buildA0() = AutomatonBuilder("Игровая машина", listOf("A0"))
    .automationEvent("e0", "A2", "Начало игры")
    .automationEvent("e11", "A1", "Опущен жетон")
    .environmentEvent("e01", "Нажата кнопка «Игра»")
    .environmentEvent("d02", "Нажата кнопка «Возврат»") // equivalent e02
    .environmentEvent("e03", "Нажата кнопка «Тех. сброс»")
    .environmentEvent("e05", "Сработал таймер третьего барабана")
    .environmentEvent("e06", "Выдан жетон из банка")
    .environmentEvent("z02", "Закрыть монетоприемник")
    .environmentEvent("z03", "Открыть монетоприемник")
    .environmentEvent("z04", "Выдать жетон из банка")
    .environmentEvent("z05", "Сбросить сумму выигрыша")
    .environmentEvent("z12", "Обновить индикатор «Ставка»")
    .environmentEvent("z13", "Сбросить ставку")
    .environmentEvent("z15", "Обновить индикатор «Банк»")
    .environmentEvent("z28", "Обновить индикатор «Выигрыш»")
    .environmentEvent("x01", "Cумма выдачи выигрыша больше нуля")
    .environmentEvent("x02", "В банке есть жетоны")
    .environmentEvent("!x01", "Cумма выдачи выигрыша не больше нуля")
    .environmentEvent("!x02", "В банке нет жетонов")
    .automationEvent("e02", "A1", "Нажата кнопка «Возврат»")
    .automationEvent("e12", "A1", "Необходимо перевести жетоны в банк")
    .automationEvent("e14", "A1", "Открыть монетоприемник") // #er-2

    .automationStateEvent("y1=1", "A1", "Жетон принят", 1)
    .automationStateEvent("y1=2", "A1", "Жетон не распознан", 2)
    .automationStateEvent("y1!=2", "A1", "y1!=2", 2, false)
    .automationStateEvent("y2=0", "A2", "Барабаны прокручены", 0) // #er-1
    .state(0, "Ожидание", listOf("z03", "z13", "z12", "e14"))
    .state(1, "Прием жетонов")
    .state(2, "Игра", listOf("e0"), nestedFsmIds = listOf("A2"))
    .state(3, "Ошибка")
    .state(4, "Выдача жетона")
// 0
    .transition(0, 1, "e11", listOf("y1=1"))
    .transition(0, 3, "e11", listOf("y1=2"), listOf("z02"))
// 1
    .transition(1, 0, "d02", listOf(), listOf("z02", "e02"))
    .transition(1, 2, "e01", listOf("y1=1"), listOf("e12"))
    .transition(1, 3, "e11", listOf("y1=2"), listOf("z02"))
// 2
    .transition(2, 4, "e05", listOf("y2=0", "x01", "x02"), listOf("z04")) //  #er-1
    .transition(2, 0, "e05", listOf("y2=0", "!x01")) // #er-1

// 3
    .transition(3, 0, "e03", listOf("y1!=2"), listOf("z05", "z28", "z15"))
    .transition(3, 0, "d02", listOf("y1=2"), listOf("e02"))
// 4
    .transition(4, 4, "e06", listOf("x01", "x02"), listOf("z04", "z28", "z15"))
    .transition(4, 3, "e06", listOf("x01", "!x02"), listOf("z28", "z15"))
    .transition(4, 0, "e06", listOf("!x01"), enterEventsId = listOf("z28", "z15"))
    .build()

fun buildA1() = AutomatonBuilder("Монетоприемник", listOf("A1"))
    .automationEvent("e02", "A0", "Нажата кнопка «Возврат»")
    .automationEvent("e14", "A0", "Открыть монетоприемник")  // #er-2
    .automationEvent("e11", "A0", "Опущен жетон")
    .automationEvent("e12", "A0", "Необходимо перевести жетоны в банк")
//    .automationEvent("f02", "A0", "Закрыть монетоприемник")
//    .automationEvent("f03", "A0", "Открыть монетоприемник")
    .environmentEvent("e10", "Опущен жетон")
    .environmentEvent("z02", "Закрыть монетоприемник")
    .environmentEvent("z12", "Обновить индикатор «Ставка»")
    .environmentEvent("z16", "Поместить жетоны в банк")
    .environmentEvent("z14", "Вернуть деньги")
    .environmentEvent("z15", "Обновить индикатор «Банк»")
    .environmentEvent("x11", "Жетон подлинный")
    .environmentEvent("!x11", "Жетон не подлинный")
    .state(0, "Монетоприемник пуст")
    .state(3, "Монетоприемник закрыт") // #er-2
    .state(1, "Жетон принят", listOf("e11"))
    .state(2, "Жетон не распознан", listOf("e11"))
    .transition(0, 1, "e10", listOf("x11"), listOf("z12"))
    .transition(0, 2, "e10", listOf("!x11"), listOf("z12"))
// 1
    .transition(1, 1, "e10", listOf("x11"), listOf("z12"))
    .transition(1, 0, "e02", enterEventsId = listOf("z14"))
    .transition(1, 3, "e12", enterEventsId = listOf("z02", "z16", "z15")) // #er-2
    .transition(1, 2, "e10", listOf("!x11"), listOf("z12"))
// 2
    .transition(2, 0, "e02", enterEventsId = listOf("z14"))
// 3
    .transition(3, 0, "e14") // #er-2
    .build()

fun buildA2() = AutomatonBuilder("Игровые барабаны", listOf("A2"))
    .environmentEvent("z20", "Запустить барабаны")
    .environmentEvent("z21", "Запустить таймер первого барабана")
    .environmentEvent("z23", "Запустить таймер второго барабана")
    .environmentEvent("z25", "Запустить таймер третьего барабана")
    .environmentEvent("z27", "Установить сумму выигрыша")
    .environmentEvent("z28", "Обновить индикатор «Выигрыш»")
    .automationEvent("e0", "A0", "Начало игры")
    .automationEvent("e01", "A0", "Нажата кнопка «Игра»")
    .automationEvent("e05", "A0", "Сработал таймер третьего барабана")
    .environmentEvent("e23", "Сработал таймер второго барабана")
    .environmentEvent("e22", "Сработал таймер первого барабана")
    .state(0, "Барабаны остановлены")
    .state(1, "Барабаны запущены")
    .state(2, "Первый барабан остановлен", listOf("z23"))
    .state(3, "Второй барабан остановлен", listOf("z25"))
    .transition(0, 1, "e0", enterEventsId = listOf("z20"))
    .transition(1, 1, "e01", enterEventsId = listOf("z21"))
    .transition(1, 2, "e22")
    .transition(2, 3, "e23")
    .transition(3, 0, "e05", enterEventsId = listOf("z27", "z28"))
    .build()

fun main() {
    val a0 = buildA0()
    val a1 = buildA1()
    val a2 = buildA2()

    var res = Multiplier(a0, a1).multiply()
    res = Multiplier(res, a2).multiply()

//    println()
//    println(a0.toPins())
//    println(a0.toUML())

//    println()
//    println(a1.toPins())
//    println(a1.toUML())


//    println()
//    println(a2.toPins())
//    println(a2.toUML())

//    println()
//    println(res.toPins())
//    println(res.toUML())
//    println(res.states.count())

    checkEquivalence(listOf(a0, a1, a2), res)

//    simulate(
//        res,
//        listOf(
//            "e10", // Опущен жетон
//            "e01", // Нажата кнопка 'Игра'
//            "e01", // Нажата кнопка 'Игра'
//            "e22", // Сработал таймер первого барабана
//            "e23", // Сработал таймер второго барабана
//            "e05", // Сработал таймер третьего барабана
//            "e06", // Выдан жетон из банка
//        )
//    )
}
