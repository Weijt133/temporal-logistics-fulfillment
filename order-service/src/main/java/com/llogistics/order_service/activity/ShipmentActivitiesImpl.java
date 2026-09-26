package com.llogistics.order_service.activity;

import com.llogistics.order_service.shipment.ShipmentService;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component("shipmentActivities")
public class ShipmentActivitiesImpl implements ShipmentActivities {
    private static final Logger log = LoggerFactory.getLogger(ShipmentActivitiesImpl.class);
    private final ShipmentService shipments;
    private final Environment environment;

    public ShipmentActivitiesImpl(ShipmentService shipments, Environment environment) {
        this.shipments = shipments;
        this.environment = environment;
    }

    @Override
    public String createOrderShipment(String orderId) {
        if (demo(orderId, "demo-reject-")) {
            throw ApplicationFailure.newNonRetryableFailure("Simulated carrier rejection", SHIPMENT_REJECTED);
        }
        if (demo(orderId, "demo-retry-")) {
            int attempt = Activity.getExecutionContext().getInfo().getAttempt();
            if (attempt < 3) {
                log.warn("Simulated carrier outage for order {}, attempt {}", orderId, attempt);
                throw new IllegalStateException("Simulated temporary carrier outage, attempt " + attempt);
            }
        }
        String shipmentId;
        try {
            shipmentId = shipments.createForOrder(orderId);
        } catch (ResponseStatusException rejection) {
            if (!rejection.getStatusCode().is4xxClientError()) {
                throw rejection;
            }
            throw ApplicationFailure.newNonRetryableFailure(
                    rejection.getReason() == null ? rejection.getMessage() : rejection.getReason(), SHIPMENT_REJECTED);
        }
        // Deliberately fail outside the service transaction, after the mock shipment has committed.
        if (demo(orderId, "demo-ambiguous-")) {
            throw new IllegalStateException("Simulated response lost after shipment commit");
        }
        log.info("Shipment {} created for order {}", shipmentId, orderId);
        return shipmentId;
    }

    @Override
    public void beginShipmentCompensation(String orderId, String reason) {
        shipments.beginCompensation(orderId, reason);
    }

    @Override
    public void compensateShipmentFailure(String orderId, String reason) {
        shipments.compensateFailure(orderId, reason);
        log.info("Shipment compensation finished for order {}", orderId);
    }

    private boolean demo(String orderId, String prefix) {
        return environment.acceptsProfiles(Profiles.of("demo")) && orderId.startsWith(prefix);
    }
}
