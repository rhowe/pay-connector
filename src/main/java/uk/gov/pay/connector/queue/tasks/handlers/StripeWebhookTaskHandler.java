package uk.gov.pay.connector.queue.tasks.handlers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.client.ledger.model.LedgerTransaction;
import uk.gov.pay.connector.client.ledger.service.LedgerService;
import uk.gov.pay.connector.events.EventService;
import uk.gov.pay.connector.events.eventdetails.dispute.DisputeCreatedEventDetails;
import uk.gov.pay.connector.events.model.Event;
import uk.gov.pay.connector.events.model.dispute.DisputeCreated;
import uk.gov.pay.connector.gateway.stripe.response.StripeNotification;
import uk.gov.pay.connector.queue.tasks.dispute.StripeDisputeData;

import javax.inject.Inject;
import java.util.Optional;

import static java.lang.String.format;
import static net.logstash.logback.argument.StructuredArguments.kv;
import static uk.gov.pay.connector.gateway.PaymentGatewayName.STRIPE;
import static uk.gov.pay.connector.gateway.stripe.StripeNotificationType.DISPUTE_CREATED;
import static uk.gov.service.payments.logging.LoggingKeys.GATEWAY_DISPUTE_ID;
import static uk.gov.service.payments.logging.LoggingKeys.LEDGER_EVENT_TYPE;

public class StripeWebhookTaskHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(StripeWebhookTaskHandler.class);
    private final LedgerService ledgerService;
    private final EventService eventService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public StripeWebhookTaskHandler(LedgerService ledgerService, EventService eventService) {
        this.ledgerService = ledgerService;
        this.eventService = eventService;
    }

    public void process(StripeNotification stripeNotification) throws JsonProcessingException {
        if (stripeNotification.getType().equals(DISPUTE_CREATED.getType())) {
            var stripeDisputeData = mapper.readValue(stripeNotification.getObject(), StripeDisputeData.class);
            if (stripeDisputeData.getBalanceTransactionList().size() > 1) {
                throw new RuntimeException("Dispute data has too many balance_transactions");
            }
            Optional<LedgerTransaction> ledgerTransaction = ledgerService
                    .getTransactionForProviderAndGatewayTransactionId(STRIPE.getName(), stripeDisputeData.getPaymentIntentId());
            ledgerTransaction.map(transaction -> {
                var disputeCreatedEvent = createDisputeCreatedEvent(stripeDisputeData, transaction);

                emitEvent(disputeCreatedEvent);

                return disputeCreatedEvent;
            }).orElseThrow(() ->
                    new RuntimeException(format("LedgerTransaction with gateway transaction id [%s] not found",
                            stripeDisputeData.getPaymentIntentId())));
        } else {
            throw new RuntimeException("Unknown webhook task: " + stripeNotification.getType());
        }
    }

    private void emitEvent(Event event) {
        eventService.emitEvent(event);
        logger.info("Event sent to payment event queue: {}", event.getResourceExternalId(),
                kv(LEDGER_EVENT_TYPE, event.getEventType()),
                kv(GATEWAY_DISPUTE_ID, event.getResourceExternalId()));
    }

    private DisputeCreated createDisputeCreatedEvent(StripeDisputeData stripeDisputeData,
                                                     LedgerTransaction transaction) {

        var balanceTransaction = stripeDisputeData.getBalanceTransactionList().get(0);
        var eventDetails = new DisputeCreatedEventDetails(Math.abs(balanceTransaction.getFee()),
                stripeDisputeData.getEvidenceDetails().getDueByTimestamp(), transaction.getGatewayAccountId(),
                Math.abs(balanceTransaction.getAmount()), Math.abs(balanceTransaction.getNetAmount()),
                stripeDisputeData.getReason());
        var disputeCreated = new DisputeCreated(stripeDisputeData.getResourceExternalId(), transaction.getTransactionId(),
                transaction.getServiceId(), transaction.getLive(), eventDetails, stripeDisputeData.getDisputeCreated());
        return disputeCreated;
    }
}
