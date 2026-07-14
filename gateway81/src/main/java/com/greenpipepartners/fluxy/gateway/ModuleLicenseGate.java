/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package com.greenpipepartners.fluxy.gateway;

import java.util.Date;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.inductiveautomation.ignition.common.licensing.LicenseMode;
import com.inductiveautomation.ignition.common.licensing.LicenseState;

final class ModuleLicenseGate {
    private final boolean freeModule;
    private final AtomicReference<LicenseState> state = new AtomicReference<>();

    ModuleLicenseGate() {
        this(false);
    }

    ModuleLicenseGate(boolean freeModule) {
        this.freeModule = freeModule;
    }

    void update(LicenseState licenseState) {
        state.set(Objects.requireNonNull(licenseState, "licenseState"));
    }

    Decision decision() {
        if (freeModule) {
            return Decision.PERMITTED;
        }
        LicenseState current = state.get();
        if (current == null) {
            return new Decision(false, 503, "MODULE_LICENSE_UNAVAILABLE", "Fluxy module license is unavailable");
        }
        LicenseMode mode = current.getLicenseMode();
        if (mode == LicenseMode.Activated) {
            return Decision.PERMITTED;
        }
        if (mode == LicenseMode.Trial) {
            Date expiration = current.getTrialExpirationDate();
            boolean expired = expiration == null || !expiration.after(new Date());
            return current.isTrialExpired() || expired
                ? new Decision(false, 403, "MODULE_TRIAL_EXPIRED", "Fluxy module trial has expired")
                : Decision.PERMITTED;
        }
        return new Decision(false, 403, "MODULE_LICENSE_INVALID", "Fluxy module license is invalid");
    }

    record Decision(boolean permitted, int status, String code, String message) {
        private static final Decision PERMITTED = new Decision(true, 200, "", "");
    }
}
