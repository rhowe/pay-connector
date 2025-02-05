package uk.gov.pay.connector.queue.tasks;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import org.apache.commons.lang.math.RandomUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.app.ConnectorApp;
import uk.gov.pay.connector.charge.model.FirstDigitsCardNumber;
import uk.gov.pay.connector.charge.model.LastDigitsCardNumber;
import uk.gov.pay.connector.it.base.ChargingITestBase;
import uk.gov.pay.connector.junit.DropwizardConfig;
import uk.gov.pay.connector.junit.DropwizardJUnitRunner;
import uk.gov.pay.connector.paymentinstrument.model.PaymentInstrumentStatus;
import uk.gov.pay.connector.queue.capture.CaptureQueue;
import uk.gov.pay.connector.queue.tasks.handlers.AuthoriseWithUserNotPresentHandler;
import uk.gov.pay.connector.util.AddAgreementParams;
import uk.gov.pay.connector.util.AddPaymentInstrumentParams;

import java.util.Map;

import static io.restassured.http.ContentType.JSON;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_ERROR;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_REJECTED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_USER_NOT_PRESENT_QUEUED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.CAPTURE_APPROVED;
import static uk.gov.pay.connector.common.model.api.ExternalChargeState.EXTERNAL_ERROR_GATEWAY;
import static uk.gov.pay.connector.common.model.api.ExternalChargeState.EXTERNAL_FAILED_REJECTED;
import static uk.gov.pay.connector.common.model.api.ExternalChargeState.EXTERNAL_STARTED;
import static uk.gov.pay.connector.common.model.api.ExternalChargeState.EXTERNAL_SUCCESS;
import static uk.gov.pay.connector.util.AddAgreementParams.AddAgreementParamsBuilder.anAddAgreementParams;
import static uk.gov.pay.connector.util.AddPaymentInstrumentParams.AddPaymentInstrumentParamsBuilder.anAddPaymentInstrumentParams;
import static uk.gov.pay.connector.util.JsonEncoder.toJson;

@RunWith(DropwizardJUnitRunner.class)
@DropwizardConfig(app = ConnectorApp.class, config = "config/test-it-config.yaml", withDockerSQS = true)
public class AuthoriseWithUserNotPresentTaskHandlerIT extends ChargingITestBase {
    private static final String JSON_AGREEMENT_ID_KEY = "agreement_id";
    private static final String JSON_VALID_AGREEMENT_ID_VALUE = "12345678901234567890123456";
    private static final String JSON_AUTH_MODE_AGREEMENT = "agreement";

    private Appender<ILoggingEvent> mockAppender = mock(Appender.class);
    private ArgumentCaptor<LoggingEvent> loggingEventArgumentCaptor = ArgumentCaptor.forClass(LoggingEvent.class);

    public AuthoriseWithUserNotPresentTaskHandlerIT() {
        super(PROVIDER_NAME);
    }

    @Override
    @Before
    public void setUp() {
        super.setUp();
        Logger root = (Logger) LoggerFactory.getLogger(CaptureQueue.class);
        root.setLevel(Level.INFO);
        root.addAppender(mockAppender);
    }

    @Test
    public void shouldProcess_ACorrectlyConfiguredAuthorisationModeAgreementCharge_AndMarkForCapture() {
        AuthoriseWithUserNotPresentHandler taskHandler = testContext.getInstanceFromGuiceContainer(AuthoriseWithUserNotPresentHandler.class);
        String SUCCESS_LAST_FOUR_DIGITS = "4242";
        String SUCCESS_FIRST_SIX_DIGITS = "424242";

        String chargeWithValidAgreementAndPaymentInstrument = setupChargeWithAgreementAndPaymentInstrument(SUCCESS_FIRST_SIX_DIGITS, SUCCESS_LAST_FOUR_DIGITS);

        taskHandler.process(chargeWithValidAgreementAndPaymentInstrument);

        assertFrontendChargeStatusIs(chargeWithValidAgreementAndPaymentInstrument, CAPTURE_APPROVED.getValue());
        assertApiStateIs(chargeWithValidAgreementAndPaymentInstrument, EXTERNAL_SUCCESS.getStatus());

        verify(mockAppender).doAppend(loggingEventArgumentCaptor.capture());
        assertThat(
                loggingEventArgumentCaptor.getValue().getFormattedMessage(),
                containsString("Charge [" + chargeWithValidAgreementAndPaymentInstrument + "] added to capture queue.")
        );
    }

    @Test
    public void shouldProcess_AndMarkComplete_AnAuthorisationModeAgreementChargeThatWillDecline_AndNotMarkForCapture() {
        AuthoriseWithUserNotPresentHandler taskHandler = testContext.getInstanceFromGuiceContainer(AuthoriseWithUserNotPresentHandler.class);
        String DECLINE_LAST_FOUR_DIGITS = "0002";
        String DECLINE_FIRST_SIX_DIGITS = "400000";

        String chargeWithValidAgreementAndPaymentInstrument = setupChargeWithAgreementAndPaymentInstrument(DECLINE_FIRST_SIX_DIGITS, DECLINE_LAST_FOUR_DIGITS);

        taskHandler.process(chargeWithValidAgreementAndPaymentInstrument);

        assertFrontendChargeStatusIs(chargeWithValidAgreementAndPaymentInstrument, AUTHORISATION_REJECTED.getValue());
        assertApiStateIs(chargeWithValidAgreementAndPaymentInstrument, EXTERNAL_FAILED_REJECTED.getStatus());
        verifyNoInteractions(mockAppender);
    }

    @Test
    public void shouldProcess_AndMarkComplete_AnAuthorisationModeAgreementChargeThatWillGatewayError_AndNotMarkForCapture() {
        AuthoriseWithUserNotPresentHandler taskHandler = testContext.getInstanceFromGuiceContainer(AuthoriseWithUserNotPresentHandler.class);
        String GATEWAY_ERROR_LAST_FOUR_DIGITS = "0119";
        String GATEWAY_ERROR_FIRST_SIX_DIGITS = "400000";

        String chargeWithValidAgreementAndPaymentInstrument = setupChargeWithAgreementAndPaymentInstrument(GATEWAY_ERROR_FIRST_SIX_DIGITS, GATEWAY_ERROR_LAST_FOUR_DIGITS);

        taskHandler.process(chargeWithValidAgreementAndPaymentInstrument);

        assertFrontendChargeStatusIs(chargeWithValidAgreementAndPaymentInstrument, AUTHORISATION_ERROR.getValue());
        assertApiStateIs(chargeWithValidAgreementAndPaymentInstrument, EXTERNAL_ERROR_GATEWAY.getStatus());
        verifyNoInteractions(mockAppender);
    }
    
    private String setupChargeWithAgreementAndPaymentInstrument(String first6Digits, String last4Digits) {
        Long paymentInstrumentId = RandomUtils.nextLong();

        AddPaymentInstrumentParams paymentInstrumentParams = anAddPaymentInstrumentParams()
                .withPaymentInstrumentId(paymentInstrumentId)
                .withFirstDigitsCardNumber(FirstDigitsCardNumber.of(first6Digits))
                .withLastDigitsCardNumber(LastDigitsCardNumber.of(last4Digits))
                .withPaymentInstrumentStatus(PaymentInstrumentStatus.ACTIVE).build();
        databaseTestHelper.addPaymentInstrument(paymentInstrumentParams);

        AddAgreementParams agreementParams = anAddAgreementParams()
                .withGatewayAccountId(accountId)
                .withExternalAgreementId(JSON_VALID_AGREEMENT_ID_VALUE)
                .withPaymentInstrumentId(paymentInstrumentId)
                .build();
        databaseTestHelper.addAgreement(agreementParams);

        String postBody = toJson(Map.of(
                JSON_AMOUNT_KEY, AMOUNT,
                JSON_REFERENCE_KEY, JSON_REFERENCE_VALUE,
                JSON_DESCRIPTION_KEY, JSON_DESCRIPTION_VALUE,
                JSON_AGREEMENT_ID_KEY, JSON_VALID_AGREEMENT_ID_VALUE,
                JSON_AUTH_MODE_KEY, JSON_AUTH_MODE_AGREEMENT
        ));

        String chargeId = connectorRestApiClient
                .postCreateCharge(postBody)
                .statusCode(SC_CREATED)
                .body(JSON_AGREEMENT_ID_KEY, is(JSON_VALID_AGREEMENT_ID_VALUE))
                .contentType(JSON)
                .extract().path("charge_id");

        assertFrontendChargeStatusIs(chargeId, AUTHORISATION_USER_NOT_PRESENT_QUEUED.getValue());
        assertApiStateIs(chargeId, EXTERNAL_STARTED.getStatus());
        return chargeId;
    }
}
