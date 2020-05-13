package com.sieong.pingpong

import kotlin.math.abs

class Game {
    companion object {
        private const val TOTAL_POINTS = 11
    }

    var scoreHost = 0
    var scoreGuest = 0
    var lastScoredPlayer: PlayerRole? = null

    /**
     * Tell who should serve next. For convention, host should always serve at the beginning of a game.
     */
    fun whoShouldServeNext() = if (!isInDeuce()) {
        if (((scoreHost + scoreGuest) / 2) % 2 == 0) PlayerRole.HOST else PlayerRole.GUEST
    } else {
        if ((scoreHost + scoreGuest) % 2 == 0) PlayerRole.HOST else PlayerRole.GUEST
    }

    fun hostScores() {
        if (isGameOver()) return
        scoreHost++
        lastScoredPlayer = PlayerRole.HOST
    }

    fun guestScores() {
        if (isGameOver()) return
        scoreGuest++
        lastScoredPlayer = PlayerRole.GUEST
    }

    fun cancelLastPoint() {
        if (lastScoredPlayer == PlayerRole.HOST) {
            scoreHost--
        } else if (lastScoredPlayer == PlayerRole.GUEST) {
            scoreGuest--
        }
    }

    fun isGameOver(): Boolean {
        //TODO remove duplicated code here and in isInDuece
        val isPointsExceedTotalPoint = (scoreHost >= TOTAL_POINTS) || (scoreGuest >= TOTAL_POINTS)
        return isPointsExceedTotalPoint && !isInDeuce()
    }

    fun isInDeuce(): Boolean {
        val isPointsExceedTotalPoint = (scoreHost >= TOTAL_POINTS) || (scoreGuest >= TOTAL_POINTS)
        return isPointsExceedTotalPoint && abs(scoreGuest - scoreHost) < 2
    }

    override fun toString(): String {
        return if (scoreGuest == 10 && scoreHost == 10) {
            "Deuce."

        } else if (isInDeuce()) {
            if (scoreHost == scoreGuest) {
                "Even."
            } else {
                if (scoreHost > scoreGuest) "Host's advantage." else "Guest's advantage."
            }

        } else {
            "Host $scoreHost to guest $scoreGuest."
        }
    }

    enum class PlayerRole { HOST, GUEST }
}