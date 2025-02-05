package uk.gov.pay.connector.gateway.stripe.request;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import uk.gov.pay.connector.app.StripeAuthTokens;
import uk.gov.pay.connector.app.StripeGatewayConfig;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.gateway.model.request.CaptureGatewayRequest;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;
import static uk.gov.pay.connector.gateway.PaymentGatewayName.STRIPE;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntityFixture.aGatewayAccountEntity;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialState.ACTIVE;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialsEntityFixture.aGatewayAccountCredentialsEntity;

@RunWith(MockitoJUnitRunner.class)
public class StripeTransferOutRequestTest {
    
    private final String netTransferAmount = "200";
    private final String chargeExternalId = "payChargeExternalId";
    private final String stripeChargeId = "stripeChargeId";
    private final String stripeBaseUrl = "stripeUrl";
    private final String stripeConnectAccountId = "stripe_account_id";

    private StripeTransferOutRequest stripeTransferOutRequest;

    @Mock
    ChargeEntity charge;

    GatewayAccountEntity gatewayAccount;
    @Mock
    StripeGatewayConfig stripeGatewayConfig;
    @Mock
    StripeAuthTokens stripeAuthTokens;


    @Before
    public void setUp() {
        gatewayAccount = aGatewayAccountEntity()
                .withGatewayName("stripe")
                .build();
        var gatewayAccountCredentialsEntity = aGatewayAccountCredentialsEntity()
                .withCredentials(Map.of("stripe_account_id", stripeConnectAccountId))
                .withGatewayAccountEntity(gatewayAccount)
                .withPaymentProvider(STRIPE.getName())
                .withState(ACTIVE)
                .build();
        gatewayAccount.setGatewayAccountCredentials(List.of(gatewayAccountCredentialsEntity));

        when(charge.getGatewayAccount()).thenReturn(gatewayAccount);
        when(charge.getExternalId()).thenReturn(chargeExternalId);
        when(charge.getGatewayAccountCredentialsEntity()).thenReturn(gatewayAccountCredentialsEntity);

        when(stripeGatewayConfig.getUrl()).thenReturn(stripeBaseUrl);
        when(stripeGatewayConfig.getAuthTokens()).thenReturn(stripeAuthTokens);

        final CaptureGatewayRequest captureGatewayRequest = CaptureGatewayRequest.valueOf(charge);

        stripeTransferOutRequest = StripeTransferOutRequest.of(netTransferAmount, stripeChargeId, captureGatewayRequest, stripeGatewayConfig);
    }

    @Test
    public void shouldCreateCorrectUrl() {
        assertThat(stripeTransferOutRequest.getUrl(), is(URI.create(stripeBaseUrl + "/v1/transfers")));
    }

    @Test
    public void shouldCreateCorrectPayload() {
        String payload = stripeTransferOutRequest.getGatewayOrder().getPayload();

        assertThat(payload, containsString("destination=" + stripeConnectAccountId));
        assertThat(payload, containsString("source_transaction=" + stripeChargeId));
        assertThat(payload, containsString("amount=" + netTransferAmount));
        assertThat(payload, containsString("currency=GBP"));
        assertThat(payload, containsString("expand%5B%5D=balance_transaction"));
        assertThat(payload, containsString("expand%5B%5D=destination_payment"));
        assertThat(payload, containsString("metadata%5Bgovuk_pay_transaction_external_id%5D=" + chargeExternalId));
        assertThat(payload, containsString("metadata%5Breason%5D=" + StripeTransferMetadataReason.TRANSFER_PAYMENT_AMOUNT));
    }

    @Test
    public void shouldHaveIdempotencyKeySetToChargeExternalId() {
        assertThat(
                stripeTransferOutRequest.getHeaders().get("Idempotency-Key"),
                is("transfer_out" + chargeExternalId)
        );
    }
}
