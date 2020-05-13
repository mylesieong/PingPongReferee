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
     * Host should serve at the beginning
     */
    fun shouldHostService(): Boolean {
        return ((scoreHost + scoreGuest) / 2) % 2 == 0
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
        val isPointsExceedTotalPoint = (scoreHost >= TOTAL_POINTS) || (scoreGuest >= TOTAL_POINTS)
        val isInDeuce = isPointsExceedTotalPoint && abs(scoreGuest - scoreHost) < 2
        return isPointsExceedTotalPoint && !isInDeuce
    }

    override fun toString() = "Host $scoreHost to guest $scoreGuest."

    enum class PlayerRole { HOST, GUEST }
}