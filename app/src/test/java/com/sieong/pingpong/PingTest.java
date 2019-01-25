package com.sieong.pingpong;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class PingTest {
    @Test
    public void addition_isCorrect() {
        int i = 2 + 2;
        assertTrue(4 == i);
    }

    @Test
    public void addition_isWrong() {
        int i = 2 + 2;
        assertFalse(5 == i);
    }
}