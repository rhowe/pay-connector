package uk.gov.pay.connector.gatewayaccount.exception;

import javax.ws.rs.WebApplicationException;

import static java.lang.String.format;
import static uk.gov.pay.connector.util.ResponseUtil.notFoundResponse;

public class GatewayAccountCredentialsNotFoundException extends WebApplicationException {
    
    public GatewayAccountCredentialsNotFoundException(Long id) {
        super(notFoundResponse(format("Gateway account credentials with id [%s] not found.", id)));
    }
}