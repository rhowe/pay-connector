package uk.gov.pay.connector.events.model.charge;

import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.model.domain.FeeEntity;
import uk.gov.pay.connector.events.eventdetails.EventDetails;
import uk.gov.pay.connector.events.eventdetails.charge.FeeIncurredEventDetails;
import uk.gov.pay.connector.events.exception.EventCreationException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class FeeIncurredEvent extends PaymentEvent {
    public FeeIncurredEvent(String resourceExternalId, EventDetails eventDetails, ZonedDateTime timestamp) {
        super(resourceExternalId, eventDetails, timestamp);
    }

    public static FeeIncurredEvent from(ChargeEntity charge) throws EventCreationException {
        Instant earliestInstant = charge.getFees()
                .stream()
                .map(FeeEntity::getCreatedDate)
                .min(Instant::compareTo)
                .orElseThrow(() -> new EventCreationException(charge.getExternalId(), "Failed to create FeeIncurredEvent due to no fees present on charge"));

        ZonedDateTime earliestCreatedDate = ZonedDateTime.ofInstant(earliestInstant, ZoneId.of("UTC"));

        return new FeeIncurredEvent(
                charge.getExternalId(),
                FeeIncurredEventDetails.from(charge),
                earliestCreatedDate
        );
    }
}
