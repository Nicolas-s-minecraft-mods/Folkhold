package io.github.nicolasgoyon.folkhold.events.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The weighted selection of a player for the center of an event ({@link EventPlacement#select}). */
class WeightedSelectTest {
    @Test
    void selectionFollowsTheWeights() {
        double[] weights = {1.0, 0.0, 2.0};
        assertEquals(0, EventPlacement.select(weights, 0.0));
        assertEquals(0, EventPlacement.select(weights, 0.99));
        assertEquals(2, EventPlacement.select(weights, 1.0), "A weight of 0 is never selected");
        assertEquals(2, EventPlacement.select(weights, 2.99));
    }
}
