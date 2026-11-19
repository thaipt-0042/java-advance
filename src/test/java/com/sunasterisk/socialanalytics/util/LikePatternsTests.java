package com.sunasterisk.socialanalytics.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LikePatternsTests {

    @Test
    void wrapsTermInWildcards() {
        assertThat(LikePatterns.contains("spring")).isEqualTo("%spring%");
    }

    @Test
    void escapesWildcardsAndTheEscapeCharacterItself() {
        assertThat(LikePatterns.contains("50%")).isEqualTo("%50!%%");
        assertThat(LikePatterns.contains("a_b")).isEqualTo("%a!_b%");
        assertThat(LikePatterns.contains("hi!")).isEqualTo("%hi!!%");
        assertThat(LikePatterns.contains("!%")).isEqualTo("%!!!%%");
    }
}
