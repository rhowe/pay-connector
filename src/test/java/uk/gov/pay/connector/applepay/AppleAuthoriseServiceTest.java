package uk.gov.pay.connector.applepay;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableMap;
import io.dropwizard.setup.Environment;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import uk.gov.pay.connector.app.ConnectorConfiguration;
import uk.gov.pay.connector.charge.exception.ChargeNotFoundRuntimeException;
import uk.gov.pay.connector.charge.model.CardDetailsEntity;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.model.domain.ChargeStatus;
import uk.gov.pay.connector.charge.service.ChargeService;
import uk.gov.pay.connector.common.exception.IllegalStateRuntimeException;
import uk.gov.pay.connector.common.exception.OperationAlreadyInProgressRuntimeException;
import uk.gov.pay.connector.gateway.model.GatewayError;
import uk.gov.pay.connector.gateway.model.response.BaseAuthoriseResponse.AuthoriseStatus;
import uk.gov.pay.connector.gateway.model.response.GatewayResponse;
import uk.gov.pay.connector.gateway.model.response.GatewayResponse.GatewayResponseBuilder;
import uk.gov.pay.connector.gateway.worldpay.WorldpayOrderStatusResponse;
import uk.gov.pay.connector.paymentprocessor.service.CardExecutorService;
import uk.gov.pay.connector.paymentprocessor.service.CardServiceTest;
import uk.gov.pay.connector.util.AuthUtils;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static java.lang.String.format;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_CANCELLED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_ERROR;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_REJECTED;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_SUCCESS;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_TIMEOUT;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_UNEXPECTED_ERROR;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.ENTERING_CARD_DETAILS;
import static uk.gov.pay.connector.gateway.model.GatewayError.gatewayConnectionTimeoutException;
import static uk.gov.pay.connector.gateway.model.GatewayError.malformedResponseReceivedFromGateway;
import static uk.gov.pay.connector.gateway.model.response.GatewayResponse.GatewayResponseBuilder.responseBuilder;
import static uk.gov.pay.connector.model.domain.applepay.ApplePayDecryptedPaymentDataFixture.anApplePayDecryptedPaymentData;
import static uk.gov.pay.connector.model.domain.applepay.ApplePayPaymentInfoFixture.anApplePayPaymentInfo;
import static uk.gov.pay.connector.paymentprocessor.service.CardExecutorService.ExecutionStatus.COMPLETED;
import static uk.gov.pay.connector.paymentprocessor.service.CardExecutorService.ExecutionStatus.IN_PROGRESS;

@RunWith(MockitoJUnitRunner.class)
public class AppleAuthoriseServiceTest extends CardServiceTest {

    private static final String SESSION_IDENTIFIER = "session-identifier";
    private static final String TRANSACTION_ID = "transaction-id";

    private final ChargeEntity charge = createNewChargeWith(1L, ENTERING_CARD_DETAILS);

    @Mock
    private CardExecutorService mockExecutorService;

    @Mock
    private Environment mockEnvironment;

    @Mock
    private Counter mockCounter;

    private AppleAuthoriseService appleAuthoriseService;
    private AppleDecryptedPaymentData validApplePayDetails =
            anApplePayDecryptedPaymentData()
                    .withApplePaymentInfo(
                            anApplePayPaymentInfo().build())
                    .build();

    @Before
    public void setUpAppleAuthorisationService() {
        mockMetricRegistry = mock(MetricRegistry.class);
        when(mockedProviders.byName(any())).thenReturn(mockedPaymentProvider);
        when(mockedPaymentProvider.generateTransactionId()).thenReturn(Optional.of(TRANSACTION_ID));
        when(mockMetricRegistry.counter(anyString())).thenReturn(mockCounter);
        when(mockEnvironment.metrics()).thenReturn(mockMetricRegistry);
        when(mockedChargeDao.findByExternalId(charge.getExternalId())).thenReturn(Optional.of(charge));
        ConnectorConfiguration mockConfiguration = mock(ConnectorConfiguration.class);

        ChargeService chargeService = new ChargeService(null, mockedChargeDao, mockedChargeEventDao,
                null, null, mockConfiguration, null);
        appleAuthoriseService = new AppleAuthoriseService(
                mockedProviders,
                mockExecutorService,
                chargeService,
                mockEnvironment);
    }

    public void mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue() {
        doAnswer(invocation -> Pair.of(COMPLETED, ((Supplier) invocation.getArguments()[0]).get()))
                .when(mockExecutorService).execute(any(Supplier.class));
    }

    private GatewayResponse mockAuthResponse(String TRANSACTION_ID, AuthoriseStatus authoriseStatus, String errorCode) {
        WorldpayOrderStatusResponse worldpayResponse = mock(WorldpayOrderStatusResponse.class);
        when(worldpayResponse.getTransactionId()).thenReturn(TRANSACTION_ID);
        when(worldpayResponse.authoriseStatus()).thenReturn(authoriseStatus);
        when(worldpayResponse.getErrorCode()).thenReturn(errorCode);
        GatewayResponseBuilder<WorldpayOrderStatusResponse> gatewayResponseBuilder = responseBuilder();
        return gatewayResponseBuilder
                .withResponse(worldpayResponse)
                .withSessionIdentifier(SESSION_IDENTIFIER)
                .build();
    }

    private void setupPaymentProviderMock(GatewayError gatewayError) {
        GatewayResponseBuilder<WorldpayOrderStatusResponse> gatewayResponseBuilder = responseBuilder();
        GatewayResponse authorisationResponse = gatewayResponseBuilder
                .withGatewayError(gatewayError)
                .build();
        when(mockedPaymentProvider.authoriseApplePay(any(ApplePayAuthorisationGatewayRequest.class))).thenReturn(authorisationResponse);
    }

    @Test
    public void doAuthoriseCard_shouldRespondAuthorisationSuccess() {
        providerWillAuthorise();
        GatewayResponse response = appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        assertThat(response.isSuccessful(), is(true));
        assertThat(response.getSessionIdentifier().isPresent(), is(true));
        assertThat(response.getSessionIdentifier().get(), is(SESSION_IDENTIFIER));

        assertThat(charge.getProviderSessionId(), is(SESSION_IDENTIFIER));
        assertThat(charge.getStatus(), is(AUTHORISATION_SUCCESS.getValue()));
        assertThat(charge.getGatewayTransactionId(), is(TRANSACTION_ID));
        verify(mockedChargeEventDao).persistChargeEventOf(charge);
        assertThat(charge.get3dsDetails(), is(nullValue()));
        assertThat(charge.getCardDetails(), is(notNullValue()));
        assertThat(charge.getCorporateSurcharge().isPresent(), is(false));
    }

    @Test
    public void doAuthorise_shouldRespondAuthorisationSuccess_overridingGeneratedTransactionId() {

        providerWillAuthorise();

        GatewayResponse response = appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        assertThat(response.isSuccessful(), is(true));
        assertThat(response.getSessionIdentifier().isPresent(), is(true));
        assertThat(response.getSessionIdentifier().get(), is(SESSION_IDENTIFIER));

        assertThat(charge.getProviderSessionId(), is(SESSION_IDENTIFIER));
        assertThat(charge.getStatus(), is(AUTHORISATION_SUCCESS.getValue()));
        assertThat(charge.getGatewayTransactionId(), is(TRANSACTION_ID));
        assertThat(charge.get3dsDetails(), is(nullValue()));
        assertThat(charge.getGatewayTransactionId(), is(notNullValue()));
        assertThat(charge.getWalletType(), is(WalletType.APPLE_PAY));
        verify(mockedChargeEventDao).persistChargeEventOf(charge);
    }

    @Test
    public void doAuthorise_shouldRespondAuthorisationRejected_whenProviderAuthorisationIsRejected() {

        providerWillReject();

        GatewayResponse response = appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        assertThat(response.isSuccessful(), is(true));
        assertThat(charge.getStatus(), is(AUTHORISATION_REJECTED.getValue()));
        assertThat(charge.getGatewayTransactionId(), is(TRANSACTION_ID));
        assertThat(charge.getWalletType(), is(WalletType.APPLE_PAY));
    }

    @Test
    public void doAuthorise_shouldRespondAuthorisationCancelled_whenProviderAuthorisationIsCancelled() {

        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        GatewayResponse authResponse = mockAuthResponse(TRANSACTION_ID, AuthoriseStatus.CANCELLED, null);
        providerWillRespondToAuthoriseWith(authResponse);

        GatewayResponse response = appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        assertThat(response.isSuccessful(), is(true));
        assertThat(charge.getStatus(), is(AUTHORISATION_CANCELLED.getValue()));
        assertThat(charge.getGatewayTransactionId(), is(TRANSACTION_ID));
        assertThat(charge.getWalletType(), is(WalletType.APPLE_PAY));
    }

    @Test
    public void shouldRespondAuthorisationError() {

        providerWillError();

        GatewayResponse response = appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        assertThat(response.isFailed(), is(true));
        assertThat(charge.getStatus(), is(AUTHORISATION_ERROR.getValue()));
        assertThat(charge.getGatewayTransactionId(), is(TRANSACTION_ID));
        assertThat(charge.getWalletType(), is(WalletType.APPLE_PAY));
    }

    @Test
    public void doAuthorise_shouldStoreExpectedCardDetails_whenAuthorisationSuccess() {
        providerWillAuthorise();
        appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        CardDetailsEntity cardDetails = charge.getCardDetails();

        assertThat(cardDetails.getCardHolderName(), is("Mr. Payment"));
        assertThat(cardDetails.getCardBrand(), is("visa"));
        assertThat(cardDetails.getExpiryDate(), is("12/23"));
        assertThat(cardDetails.getLastDigitsCardNumber().toString(), is("4242"));
        assertThat(cardDetails.getFirstDigitsCardNumber(), is(nullValue()));
        assertThat(cardDetails.getBillingAddress().isPresent(), is(false));
    }

    @Test
    public void doAuthorise_shouldStoreCardDetails_evenIfAuthorisationRejected() {

        providerWillReject();

        appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        CardDetailsEntity cardDetails = charge.getCardDetails();
        assertThat(cardDetails, is(notNullValue()));
    }

    @Test
    public void doAuthorise_shouldStoreCardDetails_evenIfInAuthorisationError() {

        providerWillError();

        appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        CardDetailsEntity cardDetails = charge.getCardDetails();
        assertThat(cardDetails, is(notNullValue()));
    }

    @Test
    public void doAuthorise_shouldStoreProviderSessionId_evenIfAuthorisationRejected() {

        providerWillReject();

        appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        assertThat(charge.getProviderSessionId(), is(SESSION_IDENTIFIER));
    }

    @Test
    public void doAuthorise_shouldNotProviderSessionId_whenAuthorisationError() {

        providerWillError();

        appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        assertThat(charge.getProviderSessionId(), is(nullValue()));
    }

    @Test
    public void doAuthorise_shouldThrowAnOperationAlreadyInProgressRuntimeException_whenTimeout() {

        when(mockExecutorService.execute(any())).thenReturn(Pair.of(IN_PROGRESS, null));

        try {
            appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);
            fail("Exception not thrown.");
        } catch (OperationAlreadyInProgressRuntimeException e) {
            Map<String, String> expectedMessage = ImmutableMap.of("message", format("Authorisation for charge already in progress, %s", charge.getExternalId()));
            assertThat(e.getResponse().getEntity(), is(expectedMessage));
        }
    }

    @Test(expected = ChargeNotFoundRuntimeException.class)
    public void doAuthorise_shouldThrowAChargeNotFoundRuntimeException_whenChargeDoesNotExist() {

        String chargeId = "jgk3erq5sv2i4cds6qqa9f1a8a";

        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();

        when(mockedChargeDao.findByExternalId(chargeId))
                .thenReturn(Optional.empty());

        appleAuthoriseService.doAuthorise(chargeId, validApplePayDetails);
    }

    @Test(expected = OperationAlreadyInProgressRuntimeException.class)
    public void doAuthorise_shouldThrowAnOperationAlreadyInProgressRuntimeException_whenStatusIsAuthorisationReady() {

        ChargeEntity charge = createNewChargeWith(1L, ChargeStatus.AUTHORISATION_READY);
        when(mockedChargeDao.findByExternalId(charge.getExternalId())).thenReturn(Optional.of(charge));
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();

        appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        verifyNoMoreInteractions(mockedChargeDao, mockedProviders);
    }

    @Test(expected = IllegalStateRuntimeException.class)
    public void doAuthorise_shouldThrowAnIllegalStateRuntimeException_whenInvalidStatus() {

        ChargeEntity charge = createNewChargeWith(1L, ChargeStatus.CREATED);
        when(mockedChargeDao.findByExternalId(charge.getExternalId())).thenReturn(Optional.of(charge));
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();

        appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        verifyNoMoreInteractions(mockedChargeDao, mockedProviders);
    }

    @Test
    public void doAuthorise_shouldReportAuthorisationTimeout_whenProviderTimeout() {
        GatewayError gatewayError = gatewayConnectionTimeoutException("Connection timed out");
        providerWillRespondWithError(gatewayError);

        GatewayResponse response = appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        assertThat(response.isFailed(), is(true));
        assertThat(charge.getStatus(), is(AUTHORISATION_TIMEOUT.getValue()));
    }

    @Test
    public void doAuthorise_shouldReportUnexpectedError_whenProviderError() {

        GatewayError gatewayError = malformedResponseReceivedFromGateway("Malformed response received");
        providerWillRespondWithError(gatewayError);

        GatewayResponse response = appleAuthoriseService.doAuthorise(charge.getExternalId(), validApplePayDetails);

        assertThat(response.isFailed(), is(true));
        assertThat(charge.getStatus(), is(AUTHORISATION_UNEXPECTED_ERROR.getValue()));
    }

    private void providerWillRespondToAuthoriseWith(GatewayResponse value) {
        when(mockedPaymentProvider.authoriseApplePay(any(ApplePayAuthorisationGatewayRequest.class))).thenReturn(value);
    }

    private void providerWillAuthorise() {
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        GatewayResponse authResponse = mockAuthResponse(TRANSACTION_ID, AuthoriseStatus.AUTHORISED, null);
        providerWillRespondToAuthoriseWith(authResponse);
    }

    private void providerWillReject() {
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        GatewayResponse authResponse = mockAuthResponse(TRANSACTION_ID, AuthoriseStatus.REJECTED, null);
        providerWillRespondToAuthoriseWith(authResponse);
    }

    private void providerWillError() {
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        GatewayResponse authResponse = mockAuthResponse(null, AuthoriseStatus.ERROR, "error-code");
        providerWillRespondToAuthoriseWith(authResponse);
    }

    private void providerWillRespondWithError(GatewayError gatewayError) {
        mockExecutorServiceWillReturnCompletedResultWithSupplierReturnValue();
        setupPaymentProviderMock(gatewayError);
    }
}
