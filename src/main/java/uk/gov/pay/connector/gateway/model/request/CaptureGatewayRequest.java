package uk.gov.pay.connector.gateway.model.request;

import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.util.CorporateCardSurchargeCalculator;
import uk.gov.pay.connector.gateway.GatewayOperation;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;

import java.time.Instant;
import java.util.Map;

public class CaptureGatewayRequest implements GatewayRequest {

    private ChargeEntity charge;

    private CaptureGatewayRequest(ChargeEntity charge) {
        this.charge = charge;
    }

    public String getAmountAsString() {
        return String.valueOf(CorporateCardSurchargeCalculator.getTotalAmountFor(charge));
    }
    
    public Long getAmount() {
        return charge.getAmount();
    }
    
    public String getTransactionId() {
        return charge.getGatewayTransactionId();
    }
    
    public String getExternalId() {
        return charge.getExternalId();
    }

    public Instant getCreatedDate() {
        return charge.getCreatedDate();
    }

    @Override
    public GatewayAccountEntity getGatewayAccount() {
        return charge.getGatewayAccount();
    }

    @Override
    public GatewayOperation getRequestType() {
        return GatewayOperation.CAPTURE;
    }

    @Override
    public Map<String, String> getGatewayCredentials() {
        return charge.getGatewayAccountCredentialsEntity().getCredentials();
    }

    public static CaptureGatewayRequest valueOf(ChargeEntity charge) {
        return new CaptureGatewayRequest(charge);
    }
}
