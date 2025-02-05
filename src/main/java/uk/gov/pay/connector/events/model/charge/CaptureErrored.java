package uk.gov.pay.connector.events.model.charge;

import java.time.ZonedDateTime;

// In rare circumstances.. only smartpay?
public class CaptureErrored extends PaymentEventWithoutDetails {
    public CaptureErrored(String serviceId, boolean live, String resourceExternalId, ZonedDateTime timestamp) {
        super(serviceId, live, resourceExternalId, timestamp);
    }
}
