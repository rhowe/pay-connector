package uk.gov.pay.connector.it.resources;

import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.pay.connector.charge.resource.ChargesApiResource;
import uk.gov.pay.connector.charge.service.ChargeExpiryService;
import uk.gov.pay.connector.charge.service.ChargeService;
import uk.gov.pay.connector.common.exception.ConstraintViolationExceptionMapper;
import uk.gov.pay.connector.common.model.api.ErrorResponse;
import uk.gov.pay.connector.gatewayaccount.service.GatewayAccountService;
import uk.gov.service.payments.commons.model.ErrorIdentifier;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(DropwizardExtensionsSupport.class)
public class ChargesApiResourceTest {

    private static final ChargeService chargeService = mock(ChargeService.class);
    private static final ChargeExpiryService chargeExpiryService = mock(ChargeExpiryService.class);
    private static final GatewayAccountService gatewayAccountService = mock(GatewayAccountService.class);

    public static ResourceExtension resources = ResourceExtension.builder()
            .addResource(new ChargesApiResource(chargeService, chargeExpiryService, gatewayAccountService))
            .setRegisterDefaultExceptionMappers(false)
            .addProvider(ConstraintViolationExceptionMapper.class)
            .build();

    @Test
    void createCharge_invalidPaymentProvider_shouldReturn422() {
        var payload = Map.of(
                "amount", 100,
                "reference", "ref",
                "description", "desc",
                "return_url", "http://service.url/success-page/",
                "payment_provider", "simon"
        );

        Response response = resources
                .target("/v1/api/accounts/1/charges")
                .request()
                .post(Entity.json(payload));

        assertThat(response.getStatus(), is(422));
        ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
        assertThat(errorResponse.getMessages(), hasItem("Field [payment_provider] must be one of [epdq, sandbox, smartpay, stripe, worldpay]"));
        assertThat(errorResponse.getIdentifier(), is(ErrorIdentifier.GENERIC));
    }
    
}