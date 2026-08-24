package org.example.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HydeServiceTest {

    @Test
    void disabledReturnsOriginalQuery() {
        HydeService service = new HydeService(null, false, 400);
        HydeService.Rewrite rewrite = service.rewrite("MySQL 连不上");
        assertFalse(rewrite.applied);
        assertEquals("MySQL 连不上", rewrite.retrievalText);
    }
}
