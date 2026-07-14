/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package com.greenpipepartners.fluxy.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import com.inductiveautomation.ignition.common.licensing.ActivatedLicenseState;
import com.inductiveautomation.ignition.common.licensing.FreeLicenseState;
import com.inductiveautomation.ignition.common.licensing.InvalidLicenseState;
import com.inductiveautomation.ignition.common.licensing.TrialLicenseState;
import org.junit.jupiter.api.Test;

class ModuleLicenseGateTest {
    private final ModuleLicenseGate gate = new ModuleLicenseGate();

    @Test
    void failsClosedBeforeLicenseStateIsKnown() {
        ModuleLicenseGate.Decision decision = gate.decision();

        assertFalse(decision.permitted());
        assertEquals(503, decision.status());
        assertEquals("MODULE_LICENSE_UNAVAILABLE", decision.code());
    }

    @Test
    void freeBuildPermitsRequestsWithoutALicenseState() {
        assertTrue(new ModuleLicenseGate(true).decision().permitted());
    }

    @Test
    void permitsAnActiveTrial() {
        gate.update(new TrialLicenseState(false, new Date(System.currentTimeMillis() + 60_000)));

        assertTrue(gate.decision().permitted());
    }

    @Test
    void rejectsAnExpiredTrial() {
        gate.update(new TrialLicenseState(true, new Date(System.currentTimeMillis() - 1_000)));

        ModuleLicenseGate.Decision decision = gate.decision();
        assertFalse(decision.permitted());
        assertEquals(403, decision.status());
        assertEquals("MODULE_TRIAL_EXPIRED", decision.code());
        assertEquals("Fluxy module trial has expired", decision.message());
    }

    @Test
    void rejectsAPastExpirationEvenBeforeTheExpiryCallbackArrives() {
        gate.update(new TrialLicenseState(false, new Date(System.currentTimeMillis() - 1_000)));

        assertEquals("MODULE_TRIAL_EXPIRED", gate.decision().code());
    }

    @Test
    void rejectsATrialWithoutAnExpirationDate() {
        gate.update(new TrialLicenseState(false, null));

        assertEquals("MODULE_TRIAL_EXPIRED", gate.decision().code());
    }

    @Test
    void permitsAnActivatedLicenseWithoutAMaintenanceExpiryCheck() {
        gate.update(new ActivatedLicenseState(null, null));

        assertTrue(gate.decision().permitted());
    }

    @Test
    void rejectsInvalidAndFreeStates() {
        gate.update(new InvalidLicenseState("test"));
        assertEquals("MODULE_LICENSE_INVALID", gate.decision().code());

        gate.update(FreeLicenseState.getSharedInstance());
        assertEquals("MODULE_LICENSE_INVALID", gate.decision().code());
    }

    @Test
    void transitionsWithoutRecreatingTheGate() {
        gate.update(new TrialLicenseState(false, new Date(System.currentTimeMillis() + 60_000)));
        assertTrue(gate.decision().permitted());

        gate.update(new TrialLicenseState(true, new Date()));
        assertFalse(gate.decision().permitted());

        gate.update(new TrialLicenseState(false, new Date(System.currentTimeMillis() + 60_000)));
        assertTrue(gate.decision().permitted());
    }
}
