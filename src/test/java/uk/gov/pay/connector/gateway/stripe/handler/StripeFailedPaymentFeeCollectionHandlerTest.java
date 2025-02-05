package uk.gov.pay.connector.gateway.stripe.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.pay.connector.app.StripeGatewayConfig;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.model.domain.ChargeEntityFixture;
import uk.gov.pay.connector.charge.model.domain.ChargeStatus;
import uk.gov.pay.connector.fee.model.Fee;
import uk.gov.pay.connector.gateway.GatewayClient;
import uk.gov.pay.connector.gateway.stripe.request.StripeGetPaymentIntentRequest;
import uk.gov.pay.connector.gateway.stripe.request.StripeTransferInRequest;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialsEntity;
import uk.gov.pay.connector.pact.ChargeEventEntityFixture;
import uk.gov.pay.connector.util.JsonObjectMapper;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.pay.connector.charge.model.domain.ChargeEntityFixture.aValidChargeEntity;
import static uk.gov.pay.connector.charge.model.domain.FeeType.RADAR;
import static uk.gov.pay.connector.charge.model.domain.FeeType.THREE_D_S;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntityFixture.aGatewayAccountEntity;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialsEntityFixture.aGatewayAccountCredentialsEntity;
import static uk.gov.pay.connector.pact.ChargeEventEntityFixture.aValidChargeEventEntity;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.STRIPE_GET_PAYMENT_INTENT_WITH_3DS_AUTHORISED_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.STRIPE_GET_PAYMENT_INTENT_WITH_MULTIPLE_CHARGES;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.STRIPE_PAYMENT_INTENT_SUCCESS_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.STRIPE_TRANSFER_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.load;

@ExtendWith(MockitoExtension.class)
class StripeFailedPaymentFeeCollectionHandlerTest {

    @Mock
    private GatewayClient gatewayClient;

    @Mock
    private StripeGatewayConfig stripeGatewayConfig;

    @Captor
    private ArgumentCaptor<StripeTransferInRequest> stripeTransferInRequestCaptor;

    private final JsonObjectMapper objectMapper = new JsonObjectMapper(new ObjectMapper());

    public static final int radarFee = 7;
    public static final int threeDsFee = 6;

    private final String chargeExternalId = "a-charge-external-id";
    private final String stripeAccountId = "stripe-connect-account-id";
    private final String stripePlatformAccountId = "stripe-plarform-account-id";
    private final String paymentIntentId = "pi_1FHESeEZsufgnuO08A2FUSPy";

    private final GatewayAccountCredentialsEntity gatewayAccountCredentialsEntity = aGatewayAccountCredentialsEntity()
            .withPaymentProvider("stripe")
            .withCredentials(Map.of("stripe_account_id", stripeAccountId))
            .build();
    private final GatewayAccountEntity gatewayAccountEntity = aGatewayAccountEntity()
            .withGatewayAccountCredentials(List.of(gatewayAccountCredentialsEntity))
            .build();
    private final ChargeEntityFixture chargeEntityFixture = aValidChargeEntity()
            .withExternalId(chargeExternalId)
            .withGatewayAccountEntity(gatewayAccountEntity)
            .withStatus(ChargeStatus.EXPIRED)
            .withGatewayTransactionId(paymentIntentId)
            .withGatewayAccountCredentialsEntity(gatewayAccountCredentialsEntity);

    private StripeFailedPaymentFeeCollectionHandler stripeFailedPaymentFeeCollectionHandler;

    @BeforeEach
    void setup() {
        stripeFailedPaymentFeeCollectionHandler = new StripeFailedPaymentFeeCollectionHandler(
                gatewayClient, stripeGatewayConfig, objectMapper);


    }

    @Test
    void shouldTransferFeeForChargeThatHasBeenThrough3ds() throws Exception {
        mockGetRequestResponse(STRIPE_GET_PAYMENT_INTENT_WITH_3DS_AUTHORISED_RESPONSE);
        setupCommonMocksForCollectFeeSuccess();
        when(stripeGatewayConfig.getThreeDsFeeInPence()).thenReturn(threeDsFee);

        List<Fee> fees = stripeFailedPaymentFeeCollectionHandler.calculateAndTransferFees(chargeEntityFixture.build());

        verifyTransferRequestPayload(13);
        assertThat(fees, hasSize(2));
        assertThat(fees, containsInAnyOrder(
                Fee.of(RADAR, (long) radarFee),
                Fee.of(THREE_D_S, (long) threeDsFee)
        ));
    }

    @Test
    void shouldTransferFeeForChargeThatHasBeenThrough3ds_whenNoStripeChargeExistsForPaymentIntent_forAuthRejected() throws Exception {
        mockGetRequestResponse(STRIPE_PAYMENT_INTENT_SUCCESS_RESPONSE);
        setupCommonMocksForCollectFeeSuccess();
        when(stripeGatewayConfig.getThreeDsFeeInPence()).thenReturn(threeDsFee);

        chargeEntityFixture.withEvents(List.of(
                aValidChargeEventEntity().withChargeStatus(ChargeStatus.AUTHORISATION_3DS_REQUIRED).build(),
                aValidChargeEventEntity().withChargeStatus(ChargeStatus.AUTHORISATION_REJECTED).build()));

        List<Fee> fees = stripeFailedPaymentFeeCollectionHandler.calculateAndTransferFees(chargeEntityFixture.build());

        verifyTransferRequestPayload(13);
        assertThat(fees, hasSize(2));
        assertThat(fees, containsInAnyOrder(
                Fee.of(RADAR, (long) radarFee),
                Fee.of(THREE_D_S, (long) threeDsFee)
        ));
    }

    @Test
    void shouldTransferFeeForChargeThatHasBeenThrough3ds_whenNoStripeChargeExistsForPaymentIntent_forAuthSuccess() throws Exception {
        mockGetRequestResponse(STRIPE_PAYMENT_INTENT_SUCCESS_RESPONSE);
        setupCommonMocksForCollectFeeSuccess();
        when(stripeGatewayConfig.getThreeDsFeeInPence()).thenReturn(threeDsFee);

        chargeEntityFixture.withEvents(List.of(
                aValidChargeEventEntity().withChargeStatus(ChargeStatus.AUTHORISATION_3DS_REQUIRED).build(),
                aValidChargeEventEntity().withChargeStatus(ChargeStatus.AUTHORISATION_SUCCESS).build()));

        List<Fee> fees = stripeFailedPaymentFeeCollectionHandler.calculateAndTransferFees(chargeEntityFixture.build());

        verifyTransferRequestPayload(13);
        assertThat(fees, hasSize(2));
        assertThat(fees, containsInAnyOrder(
                Fee.of(RADAR, (long) radarFee),
                Fee.of(THREE_D_S, (long) threeDsFee)
        ));
    }

    @Test
    void shouldTransferFeeForChargeThatHasNotBeenThrough3ds_whenNoStripeChargeExistsForPaymentIntent() throws Exception {
        mockGetRequestResponse(STRIPE_PAYMENT_INTENT_SUCCESS_RESPONSE);
        setupCommonMocksForCollectFeeSuccess();

        chargeEntityFixture.withEvents(List.of(
                aValidChargeEventEntity().withChargeStatus(ChargeStatus.AUTHORISATION_SUCCESS).build()));

        List<Fee> fees = stripeFailedPaymentFeeCollectionHandler.calculateAndTransferFees(chargeEntityFixture.build());
        verifyTransferRequestPayload(7);

        assertThat(fees, hasSize(1));
        assertThat(fees, contains(
                Fee.of(RADAR, (long) radarFee)
        ));
    }

    @Test
    void shouldTransferFeeForChargeThatHasNotBeenThrough3ds() throws Exception {
        mockGetRequestResponse(STRIPE_PAYMENT_INTENT_SUCCESS_RESPONSE);
        setupCommonMocksForCollectFeeSuccess();

        List<Fee> fees = stripeFailedPaymentFeeCollectionHandler.calculateAndTransferFees(chargeEntityFixture.build());
        verifyTransferRequestPayload(7);

        assertThat(fees, hasSize(1));
        assertThat(fees, contains(
                Fee.of(RADAR, (long) radarFee)
        ));
    }

    @Test
    void shouldThrowExceptionWhenMultipleChargesForPaymentIntent() throws Exception {
        mockGetRequestResponse(STRIPE_GET_PAYMENT_INTENT_WITH_MULTIPLE_CHARGES);
        RuntimeException runtimeException = assertThrows(RuntimeException.class, () -> stripeFailedPaymentFeeCollectionHandler.calculateAndTransferFees(chargeEntityFixture.build()));
        assertThat(runtimeException.getMessage(), containsString("Expected at most 1 Charge for PaymentIntent, found"));
        verify(gatewayClient, never()).postRequestFor(any(StripeTransferInRequest.class));
    }

    private void verifyTransferRequestPayload(int amount) throws Exception {
        verify(gatewayClient).postRequestFor(stripeTransferInRequestCaptor.capture());
        String payload = stripeTransferInRequestCaptor.getValue().getGatewayOrder().getPayload();

        assertThat(payload, CoreMatchers.containsString("destination=" + stripePlatformAccountId));
        assertThat(payload, CoreMatchers.containsString("amount=" + amount));
        assertThat(payload, CoreMatchers.containsString("transfer_group=" + chargeExternalId));
        assertThat(payload, CoreMatchers.containsString("expand%5B%5D=balance_transaction"));
        assertThat(payload, CoreMatchers.containsString("expand%5B%5D=destination_payment"));
        assertThat(payload, CoreMatchers.containsString("currency=GBP"));
        assertThat(payload, CoreMatchers.containsString("metadata%5Bstripe_charge_id%5D=" + paymentIntentId));
        assertThat(payload, CoreMatchers.containsString("metadata%5Bgovuk_pay_transaction_external_id%5D=" + chargeExternalId));
    }

    private void setupCommonMocksForCollectFeeSuccess() throws Exception {
        mockPostTransferSuccess();
        when(stripeGatewayConfig.getPlatformAccountId()).thenReturn(stripePlatformAccountId);
        when(stripeGatewayConfig.getRadarFeeInPence()).thenReturn(radarFee);
    }

    private void mockGetRequestResponse(String stripeGetPaymentIntentWith3dsAuthorisedResponse) throws Exception {
        GatewayClient.Response response = mock(GatewayClient.Response.class);
        when(response.getEntity()).thenReturn(load(stripeGetPaymentIntentWith3dsAuthorisedResponse));
        when(gatewayClient.getRequestFor(any(StripeGetPaymentIntentRequest.class))).thenReturn(response);
    }

    private void mockPostTransferSuccess() throws Exception {
        GatewayClient.Response response = mock(GatewayClient.Response.class);
        when(response.getEntity()).thenReturn(load(STRIPE_TRANSFER_RESPONSE));
        when(gatewayClient.postRequestFor(any(StripeTransferInRequest.class))).thenReturn(response);
    }
}
