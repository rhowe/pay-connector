package uk.gov.pay.connector.events.model.charge;

import uk.gov.pay.connector.agreement.model.AgreementEntity;
import uk.gov.pay.connector.events.eventdetails.EventDetails;
import uk.gov.pay.connector.paymentinstrument.model.PaymentInstrumentStatus;
import uk.gov.service.payments.commons.model.agreement.AgreementStatus;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

public class AgreementCreated extends AgreementEvent {

    public AgreementCreated(String serviceId, boolean live, String resourceExternalId, EventDetails eventDetails, ZonedDateTime timestamp) {
        super(serviceId, live, resourceExternalId, eventDetails, timestamp);
    }

    public static AgreementCreated from(AgreementEntity agreement) {
        return new AgreementCreated(
                agreement.getServiceId(),
                agreement.getGatewayAccount().isLive(),
                agreement.getExternalId(),
                new AgreementCreatedEventDetails(
                        agreement.getGatewayAccount().getId(),
                        agreement.getReference(),
                        agreement.getDescription(),
                        agreement.getUserIdentifier(),
                        AgreementStatus.CREATED
                ),
                ZonedDateTime.ofInstant(agreement.getCreatedDate(), ZoneOffset.UTC)
        );
    }

    static class AgreementCreatedEventDetails extends EventDetails {
        private final String gatewayAccountId;
        private final String reference;
        private final String description;
        private final String userIdentifier;
        private final AgreementStatus status;

        public AgreementCreatedEventDetails(Long gatewayAccountId, String reference, String description, String userIdentifier, AgreementStatus status) {
            this.gatewayAccountId = String.valueOf(gatewayAccountId);
            this.reference = reference;
            this.description = description;
            this.userIdentifier = userIdentifier;
            this.status = status;
        }

        public AgreementStatus getStatus() {
            return status;
        }

        public String getGatewayAccountId() {
            return gatewayAccountId;
        }

        public String getReference() {
            return reference;
        }

        public String getDescription() {
            return description;
        }

        public String getUserIdentifier() {
            return userIdentifier;
        }
    }
}
