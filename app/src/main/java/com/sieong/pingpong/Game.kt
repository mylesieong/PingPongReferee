package com.sieong.pingpong

class Game {
    var scoreHost = 0
    var scoreGuest = 0

    /* A is assumed to serve at the beginning*/
    fun shouldHostService() = isEvenNumber((scoreHost + scoreGuest) / 2)

    private fun isEvenNumber(number: Int) = number % 2 == 0

    fun hostScores() {
        scoreHost++
    }

    fun guestScores() {
        scoreGuest++
    }

}