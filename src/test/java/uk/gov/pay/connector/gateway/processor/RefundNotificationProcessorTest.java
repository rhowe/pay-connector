package uk.gov.pay.connector.gateway.processor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.charge.model.ServicePaymentReference;
import uk.gov.pay.connector.charge.model.domain.Charge;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.model.domain.ChargeEntityFixture;
import uk.gov.pay.connector.gateway.PaymentGatewayName;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.model.domain.RefundEntityFixture;
import uk.gov.pay.connector.refund.model.domain.Refund;
import uk.gov.pay.connector.refund.model.domain.RefundEntity;
import uk.gov.pay.connector.refund.model.domain.RefundStatus;
import uk.gov.pay.connector.refund.service.RefundService;
import uk.gov.pay.connector.usernotification.service.UserNotificationService;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RefundNotificationProcessorTest {

    @Mock
    private RefundService refundService;
    @Mock
    private UserNotificationService userNotificationService;

    RefundNotificationProcessor refundNotificationProcessor;
    RefundEntity refundEntity;

    private static final PaymentGatewayName paymentGatewayName = PaymentGatewayName.WORLDPAY;
    private static final String paymentReference = "payment-reference";
    private static final String refundGatewayTransactionId = "refund-gateway-tx-id";
    private static final String transactionId = "transactionId";
    private GatewayAccountEntity gatewayAccountEntity = ChargeEntityFixture.defaultGatewayAccountEntity();
    private ChargeEntity chargeEntity = ChargeEntityFixture.aValidChargeEntity()
            .withGatewayAccountEntity(gatewayAccountEntity)
            .withReference(ServicePaymentReference.of(paymentReference))
            .withTransactionId(transactionId)
            .build();
    private Charge charge;

    @Mock
    private Appender<ILoggingEvent> mockAppender;

    @Captor
    ArgumentCaptor<LoggingEvent> loggingEventArgumentCaptor;

    @Before
    public void setup() {
        charge = Charge.from(chargeEntity);
        refundEntity = aValidRefundEntity().build();
        Optional<RefundEntity> optionalRefundEntity = Optional.of(refundEntity);

        when(refundService.findByChargeExternalIdAndGatewayTransactionId(charge.getExternalId(), refundGatewayTransactionId)).thenReturn(optionalRefundEntity);

        refundNotificationProcessor = new RefundNotificationProcessor(refundService, userNotificationService);

        Logger root = (Logger) LoggerFactory.getLogger(RefundNotificationProcessor.class);
        root.setLevel(Level.INFO);
        root.addAppender(mockAppender);
    }

    @Test
    public void shouldInvokeSendEmailNotificationsForSuccessfulRefunds() {
        refundNotificationProcessor.invoke(paymentGatewayName, RefundStatus.REFUNDED, gatewayAccountEntity, refundGatewayTransactionId, transactionId, charge);
        verify(userNotificationService).sendRefundIssuedEmail(refundEntity, charge, gatewayAccountEntity);
    }

    @Test
    public void shouldNotInvokeSendEmailNotifications_WhenRefundStatusIsNotRefunded() {
        refundNotificationProcessor.invoke(paymentGatewayName, RefundStatus.REFUND_ERROR, gatewayAccountEntity, refundGatewayTransactionId, transactionId, charge);
        verify(userNotificationService, never()).sendRefundIssuedEmail(refundEntity, charge, gatewayAccountEntity);
    }

    @Test
    public void shouldLogError_whenRefundGatewayTransactionIdIsNotAvailable() {
        refundNotificationProcessor.invoke(paymentGatewayName, RefundStatus.REFUND_ERROR, gatewayAccountEntity, null, transactionId, charge);

        verify(mockAppender).doAppend(loggingEventArgumentCaptor.capture());

        List<LoggingEvent> logStatement = loggingEventArgumentCaptor.getAllValues();
        String expectedLogMessage = "Refund notification could not be used to update charge (missing reference)";

        assertThat(logStatement.get(0).getFormattedMessage(), is(expectedLogMessage));
    }

    @Test
    public void shouldLogError_whenRefundEntityIsNotAvailable() {
        refundNotificationProcessor.invoke(paymentGatewayName, RefundStatus.REFUNDED, gatewayAccountEntity, "unknown", transactionId, charge);
        verify(mockAppender).doAppend(loggingEventArgumentCaptor.capture());

        List<LoggingEvent> logStatement = loggingEventArgumentCaptor.getAllValues();
        String expectedLogMessage = String.format("%s notification '%s' could not be used to update refund (associated refund entity not found) for charge [%s]",
                paymentGatewayName,
                "unknown",
                charge.getExternalId());

        assertThat(logStatement.get(0).getFormattedMessage(), is(expectedLogMessage));
    }

    @Test
    public void shouldLogWarning_whenNotificationIsForAnExpungedRefund() {
        String gatewayTransactionId = "refund-gateway-tx-id123";
        when(refundService.findHistoricRefundByChargeExternalIdAndGatewayTransactionId(charge, gatewayTransactionId))
                .thenReturn(Optional.of(Refund.from(refundEntity)));

        refundNotificationProcessor.invoke(paymentGatewayName, RefundStatus.REFUNDED, gatewayAccountEntity, gatewayTransactionId, transactionId, charge);
        verify(mockAppender).doAppend(loggingEventArgumentCaptor.capture());

        List<LoggingEvent> logStatement = loggingEventArgumentCaptor.getAllValues();
        String expectedLogMessage = String.format("%s notification could not be processed as refund [%s] has been expunged from connector",
                paymentGatewayName, refundEntity.getExternalId());

        assertThat(logStatement.get(0).getFormattedMessage(), is(expectedLogMessage));
    }

    public static RefundEntityFixture aValidRefundEntity() {
        return new RefundEntityFixture();
    }
}
