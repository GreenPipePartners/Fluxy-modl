/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package com.greenpipepartners.fluxy.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import com.inductiveautomation.ignition.common.licensing.InvalidLicenseState;
import com.inductiveautomation.ignition.common.licensing.TrialLicenseState;
import org.junit.jupiter.api.Test;

class ModuleLicenseGateTest {
    private final ModuleLicenseGate gate = new ModuleLicenseGate();

    @Test
    void permitsActiveTrialAndRejectsExpiredTrial() {
        gate.update(new TrialLicenseState(false, new Date(System.currentTimeMillis() + 60_000)));
        assertTrue(gate.decision().permitted());

        gate.update(new TrialLicenseState(true, new Date(System.currentTimeMillis() - 1_000)));
        assertFalse(gate.decision().permitted());
        assertEquals(403, gate.decision().status());
        assertEquals("MODULE_TRIAL_EXPIRED", gate.decision().code());
    }

    @Test
    void freeBuildPermitsRequestsWithoutALicenseState() {
        assertTrue(new ModuleLicenseGate(true).decision().permitted());
    }

    @Test
    void failsClosedForUnknownAndInvalidStates() {
        assertEquals(503, gate.decision().status());
        gate.update(new InvalidLicenseState("test"));
        assertEquals("MODULE_LICENSE_INVALID", gate.decision().code());
    }
}
