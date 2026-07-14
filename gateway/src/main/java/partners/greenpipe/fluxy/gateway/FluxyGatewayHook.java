/*
 * SPDX-FileCopyrightText: 2026 Green Pipe Partners, LLC
 * SPDX-License-Identifier: MPL-2.0
 */
package partners.greenpipe.fluxy.gateway;

import java.util.Optional;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

public final class FluxyGatewayHook extends AbstractGatewayModuleHook {
    private static final LoggerEx LOG = LoggerEx.newBuilder().build("Fluxy.Module");
    private static final boolean FREE_MODULE = ModuleBuildInfo.isFreeModule();

    private final ModuleLicenseGate licenseGate = new ModuleLicenseGate(FREE_MODULE);
    private GatewayContext context;
    private FluxyRoutes routes;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
    }

    @Override
    public void startup(LicenseState licenseState) {
        applyLicenseState(licenseState);
        routes = new FluxyRoutes(context, LOG, licenseGate);
        LOG.info("Fluxy module started licenseMode=" + (FREE_MODULE ? "free" : "licensed"));
    }

    @Override
    public void notifyLicenseStateChanged(LicenseState licenseState) {
        applyLicenseState(licenseState);
    }

    private void applyLicenseState(LicenseState licenseState) {
        licenseGate.update(licenseState);
        LOG.info(String.format(
            "Fluxy license state mode=%s trialExpired=%s trialExpiration=%s",
            licenseState.getLicenseMode(),
            licenseState.isTrialExpired(),
            licenseState.getTrialExpirationDate()
        ));
    }

    @Override
    public void shutdown() {
        routes = null;
        context = null;
        LOG.info("Fluxy module stopped");
    }

    @Override
    public void mountRouteHandlers(RouteGroup routeGroup) {
        if (routes == null) {
            throw new IllegalStateException("Fluxy routes were mounted before module startup");
        }
        routes.mount(routeGroup);
    }

    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of("fluxy");
    }

    @Override
    public boolean isFreeModule() {
        return FREE_MODULE;
    }
}
