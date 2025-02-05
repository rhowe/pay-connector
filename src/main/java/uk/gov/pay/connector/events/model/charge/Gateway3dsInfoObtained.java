package uk.gov.pay.connector.events.model.charge;

import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.events.eventdetails.charge.Gateway3dsInfoObtainedEventDetails;

import java.time.ZonedDateTime;

public class Gateway3dsInfoObtained extends PaymentEvent {

    public Gateway3dsInfoObtained(String serviceId, boolean live, String resourceExternalId,
                                  Gateway3dsInfoObtainedEventDetails eventDetails, ZonedDateTime timestamp) {
        super(serviceId, live, resourceExternalId, eventDetails, timestamp);
    }

    public static Gateway3dsInfoObtained from(ChargeEntity charge, ZonedDateTime eventDate) {
        return new Gateway3dsInfoObtained(
                charge.getServiceId(),
                charge.getGatewayAccount().isLive(),
                charge.getExternalId(),
                Gateway3dsInfoObtainedEventDetails.from(charge),
                eventDate);
    }
}
