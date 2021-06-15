package uk.gov.pay.connector.gateway.worldpay;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.gateway.GatewayClient;
import uk.gov.pay.connector.gateway.GatewayException;
import uk.gov.pay.connector.gateway.GatewayOrder;
import uk.gov.pay.connector.gateway.PaymentGatewayName;
import uk.gov.pay.connector.gateway.worldpay.exception.UnexpectedValidateCredentialsResponse;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.gatewayaccount.model.WorldpayCredentials;

import javax.inject.Named;
import java.net.URI;
import java.util.Collections;
import java.util.Map;

import static java.lang.String.format;
import static org.apache.http.HttpStatus.SC_UNAUTHORIZED;
import static uk.gov.pay.connector.gateway.GatewayResponseUnmarshaller.unmarshallResponse;
import static uk.gov.pay.connector.gateway.util.AuthUtil.getWorldpayCredentialsCheckAuthHeader;
import static uk.gov.pay.connector.gateway.worldpay.WorldpayOrderRequestBuilder.aWorldpayInquiryRequestBuilder;

public class WorldpayCredentialsValidationService implements WorldpayGatewayResponseGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldpayCredentialsValidationService.class);

    private final Map<String, URI> gatewayUrlMap;
    private final GatewayClient gatewayClient;

    @Inject
    public WorldpayCredentialsValidationService(@Named("WorldpayGatewayUrlMap") Map<String, URI> gatewayUrlMap,
                                                @Named("WorldpayValidateCredentialsGatewayClient") GatewayClient gatewayClient) {
        this.gatewayUrlMap = gatewayUrlMap;
        this.gatewayClient = gatewayClient;
    }

    public boolean validateCredentials(GatewayAccountEntity gatewayAccountEntity, WorldpayCredentials worldpayCredentials) {
        GatewayOrder order = aWorldpayInquiryRequestBuilder()
                .withTransactionId("an-order-id-that-will-not-exist")
                .withMerchantCode(worldpayCredentials.getMerchantId())
                .build();

        try {
            GatewayClient.Response response = gatewayClient.postRequestFor(
                    gatewayUrlMap.get(gatewayAccountEntity.getType()),
                    PaymentGatewayName.WORLDPAY.getName(),
                    gatewayAccountEntity.getType(),
                    order,
                    Collections.emptyList(),
                    getWorldpayCredentialsCheckAuthHeader(worldpayCredentials)
            );

            // There is no Worldpay endpoint to explicitly check credentials, so we make a query for an non-existent 
            // transaction and expect:
            // - a 401 if username/password are incorrect
            // - a 200 with error code 5 (transaction not found) in the response if all credentials are correct
            // - a 200 with error code 4 (security violation) if merchant id is incorrect
            WorldpayQueryResponse worldpayQueryResponse = unmarshallResponse(response, WorldpayQueryResponse.class);
            switch (worldpayQueryResponse.getErrorCode()) {
                case "5":
                    return true;
                case "4":
                    return false;
                default:
                    LOGGER.error(format("Unexpected error code %s returned by Worldpay when validating credentials", worldpayQueryResponse.getErrorCode()));
                    throw new UnexpectedValidateCredentialsResponse();
            }
        } catch (GatewayException.GatewayErrorException e) {
            return e.getStatus().map(status -> {
                if (status == SC_UNAUTHORIZED) {
                    return false;
                }
                throw new UnexpectedValidateCredentialsResponse();
            }).orElseThrow(UnexpectedValidateCredentialsResponse::new);
        } catch (GatewayException e) {
            throw new UnexpectedValidateCredentialsResponse();
        }
    }
}
