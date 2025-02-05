package uk.gov.pay.connector.gateway.epdq;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import uk.gov.pay.connector.charge.model.domain.Charge;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.gateway.ChargeQueryGatewayRequest;
import uk.gov.pay.connector.gateway.ChargeQueryResponse;
import uk.gov.pay.connector.gateway.GatewayException;
import uk.gov.pay.connector.gateway.model.ErrorType;
import uk.gov.pay.connector.gateway.model.GatewayError;
import uk.gov.pay.connector.gateway.model.response.BaseAuthoriseResponse;
import uk.gov.pay.connector.gateway.model.response.BaseCancelResponse;
import uk.gov.pay.connector.gateway.model.response.GatewayRefundResponse;
import uk.gov.pay.connector.gateway.model.response.GatewayResponse;

import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertTrue;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_SUCCESS;

@RunWith(MockitoJUnitRunner.class)
public class EpdqPaymentProviderIT extends BaseEpdqPaymentProviderIT {

    @Test
    public void shouldGetPaymentProviderName() {
        assertThat(provider.getPaymentGatewayName().getName(), is("epdq"));
    }

    @Test
    public void shouldGenerateNoTransactionId() {
        assertThat(provider.generateTransactionId().isPresent(), is(false));
    }

    @Test
    public void shouldAuthorise() throws Exception {
        mockPaymentProviderResponse(200, successAuthResponse());
        ChargeEntity charge = buildTestCharge();
        GatewayResponse<BaseAuthoriseResponse> response = provider.authorise(buildCardAuthorisationGatewayRequest(charge), charge);
        verifyPaymentProviderRequest(successAuthRequest());
        assertTrue(response.isSuccessful());
        assertThat(response.getBaseResponse().get().getTransactionId(), is("3014644340"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void shouldThrow_IfTryingToAuthoriseAnApplePayPayment() {
        provider.authoriseWallet(null);
    }

    @Test
    public void shouldNotAuthoriseIfPaymentProviderReturnsUnexpectedStatusCode() throws Exception {
        mockPaymentProviderResponse(200, errorAuthResponse());
        ChargeEntity charge = buildTestCharge();
        GatewayResponse<BaseAuthoriseResponse> response = provider.authorise(buildCardAuthorisationGatewayRequest(charge), charge);
        assertThat(response.isFailed(), is(true));
        assertThat(response.getGatewayError().isPresent(), is(true));
    }

    @Test
    public void shouldNotAuthoriseIfPaymentProviderReturnsNon200HttpStatusCode() throws Exception {
        try {
            mockPaymentProviderResponse(500, errorAuthResponse());
            ChargeEntity charge = buildTestCharge();
            provider.authorise(buildCardAuthorisationGatewayRequest(charge), charge);
        } catch (GatewayException.GatewayErrorException e) {
            assertEquals(e.toGatewayError(), new GatewayError("Non-success HTTP status code 500 from gateway", ErrorType.GATEWAY_ERROR));
        }
    }

    @Test
    public void shouldCancel() throws Exception {
        mockPaymentProviderResponse(200, successCancelResponse());
        GatewayResponse<BaseCancelResponse> response = provider.cancel(buildTestCancelRequest());
        verifyPaymentProviderRequest(successCancelRequest());
        assertTrue(response.isSuccessful());
        assertThat(response.getBaseResponse().get().getTransactionId(), is("3014644340"));
    }

    @Test
    public void shouldNotCancelIfPaymentProviderReturnsUnexpectedStatusCode() throws Exception {
        mockPaymentProviderResponse(200, errorCancelResponse());
        GatewayResponse<BaseCancelResponse> response = provider.cancel(buildTestCancelRequest());
        assertThat(response.isFailed(), is(true));
        assertThat(response.getGatewayError().isPresent(), is(true));
    }

    @Test
    public void shouldNotCancelIfPaymentProviderReturnsNon200HttpStatusCode() throws Exception {
        try {
            mockPaymentProviderResponse(500, errorCancelResponse());
            provider.cancel(buildTestCancelRequest());
        } catch (GatewayException.GatewayErrorException e) {
            assertEquals(e.toGatewayError(), new GatewayError("Non-success HTTP status code 500 from gateway", ErrorType.GATEWAY_ERROR));
        }
    }

    @Test
    public void shouldRefund() {
        mockPaymentProviderResponse(200, successRefundResponse());
        GatewayRefundResponse response = provider.refund(buildTestRefundRequest());
        verifyPaymentProviderRequest(successRefundRequest());
        assertTrue(response.isSuccessful());
        assertThat(response.getReference(), is(Optional.of("3014644340/1")));
    }

    @Test
    public void shouldRefundWithPaymentDeletion() {
        mockPaymentProviderResponse(200, successDeletionResponse());
        GatewayRefundResponse response = provider.refund(buildTestRefundRequest());
        verifyPaymentProviderRequest(successRefundRequest());
        assertTrue(response.isSuccessful());
        assertThat(response.getReference(), is(Optional.of("3014644340/1")));
    }

    @Test
    public void shouldNotRefundIfPaymentProviderReturnsErrorStatusCode() {
        mockPaymentProviderResponse(200, errorRefundResponse());
        GatewayRefundResponse response = provider.refund(buildTestRefundRequest());
        assertThat(response.isSuccessful(), is(false));
        assertThat(response.getError().isPresent(), is(true));
    }

    @Test
    public void shouldNotRefundIfPaymentProviderReturnsNon200HttpStatusCode() {
        mockPaymentProviderResponse(500, errorRefundResponse());
        GatewayRefundResponse response = provider.refund(buildTestRefundRequest());
        assertThat(response.isSuccessful(), is(false));
        assertThat(response.getError().isPresent(), is(true));
        assertEquals(response.getError().get(), new GatewayError("Non-success HTTP status code 500 from gateway", ErrorType.GATEWAY_ERROR));
    }

    @Test
    public void shouldSuccessfullyQueryChargeStatus() throws Exception {
        mockPaymentProviderResponse(200, successQueryAuthorisedResponse());
        ChargeEntity chargeEntity = buildChargeEntityThatHasTransactionId();
        ChargeQueryGatewayRequest request = ChargeQueryGatewayRequest.valueOf(Charge.from(chargeEntity), chargeEntity.getGatewayAccount(), chargeEntity.getGatewayAccountCredentialsEntity());
        ChargeQueryResponse response = provider.queryPaymentStatus(request);
        assertThat(response.getMappedStatus(), is(Optional.of(AUTHORISATION_SUCCESS)));
        assertThat(response.foundCharge(), is(true));
    }

    @Test
    public void shouldReturnQueryResponseWhenChargeNotFound() throws Exception {
        mockPaymentProviderResponse(200, errorQueryResponse());
        ChargeEntity chargeEntity = buildChargeEntityThatHasTransactionId();
        ChargeQueryGatewayRequest request = ChargeQueryGatewayRequest.valueOf(Charge.from(chargeEntity), chargeEntity.getGatewayAccount(), chargeEntity.getGatewayAccountCredentialsEntity());
        ChargeQueryResponse response = provider.queryPaymentStatus(request);
        assertThat(response.getMappedStatus(), is(Optional.empty()));
        assertThat(response.foundCharge(), is(false));
    }
}
