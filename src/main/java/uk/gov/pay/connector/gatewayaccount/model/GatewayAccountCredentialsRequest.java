package uk.gov.pay.connector.gatewayaccount.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GatewayAccountCredentialsRequest {
    private String paymentProvider;
    private Map<String, String> credentials;
    public static final String PAYMENT_PROVIDER_FIELD_NAME = "payment_provider";

    public GatewayAccountCredentialsRequest(
            @Schema(example = "stripe", description = "Payment provider. Accepted values - stripe, worldpay")
            @JsonProperty(PAYMENT_PROVIDER_FIELD_NAME) String paymentProvider,
            @Schema(example = "{" +
                    "  \"stripe_account_id\": \"accnt_id\"" +
                    "  }")
            @JsonProperty("credentials") Map<String, String> credentials) {
        this.credentials = credentials;
        this.paymentProvider = paymentProvider;
    }

    @JsonProperty(PAYMENT_PROVIDER_FIELD_NAME)
    public String getPaymentProvider() {
        return paymentProvider;
    }

    @Schema(hidden = true)
    public Map<String, String> getCredentialsAsMap() {
        return credentials;
    }
}
