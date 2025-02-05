package uk.gov.pay.connector.paymentprocessor.resource;

import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import org.apache.commons.lang.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.pay.connector.charge.exception.InvalidAttributeValueExceptionMapper;
import uk.gov.pay.connector.charge.exception.motoapi.AuthorisationErrorExceptionMapper;
import uk.gov.pay.connector.charge.exception.motoapi.AuthorisationRejectedExceptionMapper;
import uk.gov.pay.connector.charge.exception.motoapi.CardNumberRejectedException;
import uk.gov.pay.connector.charge.exception.motoapi.CardNumberRejectedExceptionMapper;
import uk.gov.pay.connector.charge.exception.motoapi.OneTimeTokenAlreadyUsedException;
import uk.gov.pay.connector.charge.exception.motoapi.OneTimeTokenAlreadyUsedExceptionMapper;
import uk.gov.pay.connector.charge.exception.motoapi.OneTimeTokenInvalidException;
import uk.gov.pay.connector.charge.exception.motoapi.OneTimeTokenInvalidExceptionMapper;
import uk.gov.pay.connector.charge.exception.motoapi.OneTimeTokenUsageInvalidForMotoApiException;
import uk.gov.pay.connector.charge.exception.motoapi.OneTimeTokenUsageInvalidForMotoApiExceptionMapper;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.service.ChargeCancelService;
import uk.gov.pay.connector.charge.service.ChargeEligibleForCaptureService;
import uk.gov.pay.connector.charge.service.DelayedCaptureService;
import uk.gov.pay.connector.charge.service.motoapi.MotoApiCardNumberValidationService;
import uk.gov.pay.connector.common.model.api.ErrorResponse;
import uk.gov.pay.connector.gateway.model.GatewayError;
import uk.gov.pay.connector.gateway.model.response.BaseAuthoriseResponse;
import uk.gov.pay.connector.gateway.model.response.GatewayResponse;
import uk.gov.pay.connector.paymentprocessor.api.AuthorisationResponse;
import uk.gov.pay.connector.paymentprocessor.model.AuthoriseRequest;
import uk.gov.pay.connector.paymentprocessor.service.Card3dsResponseAuthService;
import uk.gov.pay.connector.paymentprocessor.service.CardAuthoriseService;
import uk.gov.pay.connector.rules.ResourceTestRuleWithCustomExceptionMappersBuilder;
import uk.gov.pay.connector.token.TokenService;
import uk.gov.pay.connector.token.model.domain.TokenEntity;
import uk.gov.pay.connector.wallets.applepay.ApplePayService;
import uk.gov.pay.connector.wallets.googlepay.GooglePayService;
import uk.gov.service.payments.commons.model.ErrorIdentifier;

import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.time.YearMonth;
import java.util.Optional;

import static java.time.ZoneOffset.UTC;
import static java.time.format.DateTimeFormatter.ofPattern;
import static java.time.temporal.ChronoUnit.MONTHS;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.pay.connector.charge.model.domain.ChargeEntityFixture.aValidChargeEntity;
import static uk.gov.pay.connector.gateway.model.response.BaseAuthoriseResponse.AuthoriseStatus.AUTHORISED;
import static uk.gov.pay.connector.gateway.model.response.BaseAuthoriseResponse.AuthoriseStatus.CANCELLED;
import static uk.gov.pay.connector.gateway.model.response.BaseAuthoriseResponse.AuthoriseStatus.ERROR;
import static uk.gov.pay.connector.gateway.model.response.BaseAuthoriseResponse.AuthoriseStatus.EXCEPTION;
import static uk.gov.pay.connector.gateway.model.response.BaseAuthoriseResponse.AuthoriseStatus.REJECTED;
import static uk.gov.pay.connector.gateway.model.response.BaseAuthoriseResponse.AuthoriseStatus.REQUIRES_3DS;
import static uk.gov.pay.connector.gateway.model.response.BaseAuthoriseResponse.AuthoriseStatus.SUBMITTED;
import static uk.gov.service.payments.commons.model.ErrorIdentifier.AUTHORISATION_ERROR;
import static uk.gov.service.payments.commons.model.ErrorIdentifier.AUTHORISATION_REJECTED;
import static uk.gov.service.payments.commons.model.ErrorIdentifier.CARD_NUMBER_REJECTED;
import static uk.gov.service.payments.commons.model.ErrorIdentifier.GENERIC;
import static uk.gov.service.payments.commons.model.ErrorIdentifier.INVALID_ATTRIBUTE_VALUE;
import static uk.gov.service.payments.commons.model.ErrorIdentifier.ONE_TIME_TOKEN_ALREADY_USED;
import static uk.gov.service.payments.commons.model.ErrorIdentifier.ONE_TIME_TOKEN_INVALID;

@ExtendWith(DropwizardExtensionsSupport.class)
class CardResourceTest {

    private static final CardAuthoriseService mockCardAuthoriseService = mock(CardAuthoriseService.class);
    private static final Card3dsResponseAuthService mockCard3dsResponseAuthService = mock(Card3dsResponseAuthService.class);
    private static final ChargeEligibleForCaptureService mockChargeEligibleForCaptureService = mock(ChargeEligibleForCaptureService.class);
    private static final DelayedCaptureService mockDelayedCaptureService = mock(DelayedCaptureService.class);
    private static final ChargeCancelService mockChargeCancelService = mock(ChargeCancelService.class);
    private static final ApplePayService mockApplePayService = mock(ApplePayService.class);
    private static final GooglePayService mockGooglePayService = mock(GooglePayService.class);
    private static final TokenService mockTokenService = mock(TokenService.class);
    private static final MotoApiCardNumberValidationService mockMotoApiCardNumberValidationService = mock(MotoApiCardNumberValidationService.class);

    private static final ResourceExtension resources = ResourceTestRuleWithCustomExceptionMappersBuilder
            .getBuilder()
            .addResource(new CardResource(mockCardAuthoriseService,
                    mockCard3dsResponseAuthService,
                    mockChargeEligibleForCaptureService,
                    mockDelayedCaptureService,
                    mockChargeCancelService,
                    mockApplePayService,
                    mockGooglePayService,
                    mockTokenService,
                    mockMotoApiCardNumberValidationService))
            .setRegisterDefaultExceptionMappers(false)
            .addProvider(OneTimeTokenInvalidExceptionMapper.class)
            .addProvider(OneTimeTokenAlreadyUsedExceptionMapper.class)
            .addProvider(OneTimeTokenUsageInvalidForMotoApiExceptionMapper.class)
            .addProvider(InvalidAttributeValueExceptionMapper.class)
            .addProvider(CardNumberRejectedExceptionMapper.class)
            .addProvider(AuthorisationRejectedExceptionMapper.class)
            .addProvider(AuthorisationErrorExceptionMapper.class)
            .build();

    private final ChargeEntity chargeEntity = aValidChargeEntity().build();
    private TokenEntity tokenEntity;

    @BeforeEach
    public void setUp() {
        tokenEntity = new TokenEntity();
        tokenEntity.setUsed(false);
        tokenEntity.setChargeEntity(chargeEntity);
    }

    private static Object[] authoriseMotoApiPaymentInvalidInput() {
        return new Object[]{
                // one-time-token 
                new Object[]{null, "4242424242424242", "123", "11/99", "Joe Bogs", "Missing mandatory attribute: one_time_token", GENERIC},
                new Object[]{"", "4242424242424242", "123", "11/99", "Joe Bogs", "Missing mandatory attribute: one_time_token", GENERIC},
                // card number
                new Object[]{"one-time-token-123", null, "123", "11/99", "Joe Bogs", "Missing mandatory attribute: card_number", GENERIC},
                new Object[]{"one-time-token-123", "", "123", "11/99", "Joe Bogs", "Missing mandatory attribute: card_number", GENERIC},
                new Object[]{"one-time-token-123", "1234567890", "123", "11/99", "Joe Bogs", "Invalid attribute value: card_number. Must be between 12 and 19 characters long", GENERIC},
                new Object[]{"one-time-token-123", "12345678901234567890", "123", "11/99", "Joe Bogs", "Invalid attribute value: card_number. Must be between 12 and 19 characters long", GENERIC},
                new Object[]{"one-time-token-123", "card-number-123", "123", "11/99", "Joe Bogs", "Invalid attribute value: card_number. Must be a valid card number", INVALID_ATTRIBUTE_VALUE},
                // cvc
                new Object[]{"one-time-token-123", "4242424242424242", null, "11/99", "Joe Bogs", "Missing mandatory attribute: cvc", GENERIC},
                new Object[]{"one-time-token-123", "4242424242424242", "", "11/99", "Joe Bogs", "Missing mandatory attribute: cvc", GENERIC},
                new Object[]{"one-time-token-123", "4242424242424242", "12345", "11/99", "Joe Bogs", "Invalid attribute value: cvc. Must be between 3 and 4 characters long", GENERIC},
                new Object[]{"one-time-token-123", "4242424242424242", "12", "11/99", "Joe Bogs", "Invalid attribute value: cvc. Must be between 3 and 4 characters long", GENERIC},
                new Object[]{"one-time-token-123", "4242424242424242", "xyz", "11/99", "Joe Bogs", "Invalid attribute value: cvc. Must contain numbers only", INVALID_ATTRIBUTE_VALUE},
                // expiry date
                new Object[]{"one-time-token-123", "4242424242424242", "123", null, "Joe Bogs", "Missing mandatory attribute: expiry_date", GENERIC},
                new Object[]{"one-time-token-123", "4242424242424242", "123", "", "Joe Bogs", "Missing mandatory attribute: expiry_date", GENERIC},
                new Object[]{"one-time-token-123", "4242424242424242", "123", "1109", "Joe Bogs", "Invalid attribute value: expiry_date. Must be a valid date with the format MM/YY", INVALID_ATTRIBUTE_VALUE},
                new Object[]{"one-time-token-123", "4242424242424242", "123", "109", "Joe Bogs", "Invalid attribute value: expiry_date. Must be a valid date with the format MM/YY", INVALID_ATTRIBUTE_VALUE},
                new Object[]{"one-time-token-123", "4242424242424242", "123", "asdf", "Joe Bogs", "Invalid attribute value: expiry_date. Must be a valid date with the format MM/YY", INVALID_ATTRIBUTE_VALUE},
                new Object[]{"one-time-token-123", "4242424242424242", "123", "11/21", "Joe Bogs", "Invalid attribute value: expiry_date. Must be a valid date in the future", INVALID_ATTRIBUTE_VALUE},
                // cardholder_name
                new Object[]{"one-time-token-123", "4242424242424242", "123", "11/99", null, "Missing mandatory attribute: cardholder_name", GENERIC},
                new Object[]{"one-time-token-123", "4242424242424242", "123", "11/99", "", "Invalid attribute value: cardholder_name. Must be less than or equal to 255 characters length", GENERIC},
                new Object[]{"one-time-token-123", "4242424242424242", "123", "", StringUtils.repeat("*", 256),
                        "Invalid attribute value: cardholder_name. Must be less than or equal to 255 characters length", GENERIC}
        };
    }

    @ParameterizedTest
    @MethodSource("authoriseMotoApiPaymentInvalidInput")
    void authoriseMotoApiPaymentShouldReturn422ForInvalidPayload(String oneTimeToken,
                                                                 String cardNumber,
                                                                 String cvc,
                                                                 String expiryDate,
                                                                 String cardHolderName,
                                                                 String expectedMessage,
                                                                 ErrorIdentifier expectedErrorIdentifier) {
        AuthoriseRequest request =
                new AuthoriseRequest(oneTimeToken, cardNumber, cvc, expiryDate, cardHolderName);

        Response response = resources.target("/v1/api/charges/authorise")
                .request().post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

        assertThat(response.getStatus(), is(422));

        ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
        assertThat(errorResponse.getMessages(), hasItem(expectedMessage));

        assertThat(errorResponse.getIdentifier(), is(expectedErrorIdentifier));
    }

    private static Object[] expiryDateMonthOffsetAndExpectedResponseStatus() {
        return new Object[]{
                new Object[]{0, 204, "Expiry date is same as current month and year"},
                new Object[]{1, 204, "Expiry date is in the future"},
                new Object[]{10000, 204, "Expiry date is in the future"},
                new Object[]{-2, 422, "Expiry date is in the past (two months before current month and year)"}
        };
    }

    @ParameterizedTest
    @MethodSource("expiryDateMonthOffsetAndExpectedResponseStatus")
    void authoriseMotoApiPaymentShouldReturnCorrectResponsesForExpiryDate(int monthsToAddOrSubstractFromCurrentMonthAndYear,
                                                                          int expectedResponseCode,
                                                                          String description) {
        String token = "one-time-token-123";
        String cardNumber = "4242424242424242";
        doReturn(tokenEntity).when(mockTokenService).validateAndMarkTokenAsUsedForMotoApi(token);
        YearMonth expiryMonthAndDate = YearMonth.now(UTC).plus(monthsToAddOrSubstractFromCurrentMonthAndYear, MONTHS);
        AuthoriseRequest request = new AuthoriseRequest(token, cardNumber, "123",
                expiryMonthAndDate.format(ofPattern("MM/yy")), "Job Bogs");

        mockAuthorisationResponse(AUTHORISED);

        Response response = resources.target("/v1/api/charges/authorise")
                .request().post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

        assertThat(response.getStatus(), is(expectedResponseCode));

        if (expectedResponseCode == 422) {
            ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
            assertThat(errorResponse.getMessages(), hasItem("Invalid attribute value: expiry_date. Must be a valid date in the future"));
            assertThat(errorResponse.getIdentifier(), is(INVALID_ATTRIBUTE_VALUE));
        }
    }

    @Test
    void authoriseMotoApiPaymentShouldReturn204ForValidPayload() {
        String token = "one-time-token-123";
        String cardNumber = "4242424242424242";
        AuthoriseRequest request =
                new AuthoriseRequest(token, cardNumber, "123", "11/99", "Job Bogs");

        doReturn(tokenEntity).when(mockTokenService).validateAndMarkTokenAsUsedForMotoApi(token);
        mockAuthorisationResponse(AUTHORISED);

        Response response = resources.target("/v1/api/charges/authorise")
                .request().post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

        assertThat(response.getStatus(), is(204));
    }

    @Test
    void authoriseMotoApiPaymentShouldReturn400ForInvalidOneTimeTaken() {
        AuthoriseRequest request =
                new AuthoriseRequest("one-time-token-123", "4242424242424242", "123", "11/99", "Job Bogs");

        doThrow(new OneTimeTokenInvalidException()).when(mockTokenService).validateAndMarkTokenAsUsedForMotoApi("one-time-token-123");

        Response response = resources.target("/v1/api/charges/authorise")
                .request().post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

        assertThat(response.getStatus(), is(400));

        ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
        assertThat(errorResponse.getMessages(), hasItem("The one_time_token is not valid"));
        assertThat(errorResponse.getIdentifier(), is(ONE_TIME_TOKEN_INVALID));
    }

    @Test
    void authoriseMotoApiPaymentShouldReturn400ForOneTimeTakenAlreadyUsed() {
        AuthoriseRequest request =
                new AuthoriseRequest("one-time-token-123", "4242424242424242", "123", "11/99", "Job Bogs");

        doThrow(new OneTimeTokenAlreadyUsedException()).when(mockTokenService).validateAndMarkTokenAsUsedForMotoApi("one-time-token-123");

        Response response = resources.target("/v1/api/charges/authorise")
                .request().post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

        assertThat(response.getStatus(), is(400));

        ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
        assertThat(errorResponse.getMessages(), hasItem("The one_time_token has already been used"));
        assertThat(errorResponse.getIdentifier(), is(ONE_TIME_TOKEN_ALREADY_USED));
    }

    @Test
    void authoriseMotoApiPaymentShouldReturn400IfOneTimeTakenUsageIsInvalidForMotoApi() {
        AuthoriseRequest request =
                new AuthoriseRequest("one-time-token-123", "4242424242424242", "123", "11/99", "Job Bogs");

        doThrow(new OneTimeTokenUsageInvalidForMotoApiException()).when(mockTokenService).validateAndMarkTokenAsUsedForMotoApi("one-time-token-123");

        Response response = resources.target("/v1/api/charges/authorise")
                .request().post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

        assertThat(response.getStatus(), is(400));

        ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
        assertThat(errorResponse.getMessages(), hasItem("The one_time_token is not a valid moto api token"));
        assertThat(errorResponse.getIdentifier(), is(GENERIC));
    }

    @Test
    void authoriseMotoApiPaymentShouldReturn402IfCardNumberIsNotValid() {
        String token = "one-time-token-123";
        String cardNumber = "4242424242424242";
        AuthoriseRequest request =
                new AuthoriseRequest(token, cardNumber, "123", "11/99", "Job Bogs");

        doReturn(tokenEntity).when(mockTokenService).validateAndMarkTokenAsUsedForMotoApi(token);
        doThrow(new CardNumberRejectedException("Card number rejected")).when(mockMotoApiCardNumberValidationService).validateCardNumber(chargeEntity, cardNumber);

        Response response = resources.target("/v1/api/charges/authorise")
                .request().post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

        assertThat(response.getStatus(), is(402));

        ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
        assertThat(errorResponse.getMessages(), hasItem("Card number rejected"));
        assertThat(errorResponse.getIdentifier(), is(CARD_NUMBER_REJECTED));
    }

    private static Object[] authorisationFailedInput() {
        return new Object[]{
                new Object[]{REJECTED, 402, "The payment was rejected", AUTHORISATION_REJECTED},
                new Object[]{CANCELLED, 402, "The payment was rejected", AUTHORISATION_REJECTED},
                new Object[]{ERROR, 500, "There was an error authorising the payment", AUTHORISATION_ERROR},
                new Object[]{EXCEPTION, 500, "There was an error authorising the payment", AUTHORISATION_ERROR},
                new Object[]{SUBMITTED, 500, "There was an error authorising the payment", AUTHORISATION_ERROR}
        };
    }

    @ParameterizedTest
    @MethodSource("authorisationFailedInput")
    void authoriseMotoApiPaymentShouldReturnErrorResponseForAuthorisationFailedPayments(BaseAuthoriseResponse.AuthoriseStatus authoriseStatus,
                                                                                        int expectedResponseCode,
                                                                                        String expectedErrorMessage,
                                                                                        ErrorIdentifier expectedErrorIdentifier) {
        String token = "one-time-token-123";
        AuthoriseRequest request =
                new AuthoriseRequest(token, "4242424242424242", "123", "11/99", "Job Bogs");

        doReturn(tokenEntity).when(mockTokenService).validateAndMarkTokenAsUsedForMotoApi(token);
        mockAuthorisationResponse(authoriseStatus);

        Response response = resources.target("/v1/api/charges/authorise")
                .request().post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

        assertThat(response.getStatus(), is(expectedResponseCode));
        ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
        assertThat(errorResponse.getMessages(), hasItem(expectedErrorMessage));
        assertThat(errorResponse.getIdentifier(), is(expectedErrorIdentifier));
    }

    private static Object[] gatewayExceptions() {
        return new Object[]{
                new Object[]{GatewayError.genericGatewayError("generic gateway error exception")},
                new Object[]{GatewayError.gatewayConnectionError("gateway connection exception")}
        };
    }

    @ParameterizedTest
    @MethodSource("gatewayExceptions")
    void authoriseMotoApiPaymentShouldReturn500ForGatewayError(GatewayError gatewayError) {
        String token = "one-time-token-123";
        AuthoriseRequest request =
                new AuthoriseRequest(token, "4242424242424242", "123", "11/99", "Job Bogs");

        doReturn(tokenEntity).when(mockTokenService).validateAndMarkTokenAsUsedForMotoApi(token);
        mockGatewayError(gatewayError);

        Response response = resources.target("/v1/api/charges/authorise")
                .request().post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

        assertThat(response.getStatus(), is(500));
        ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
        assertThat(errorResponse.getMessages(), hasItem("There was an error authorising the payment"));
        assertThat(errorResponse.getIdentifier(), is(AUTHORISATION_ERROR));
    }

    @Test
    void authoriseMotoApiPaymentShouldReturn500ForUnexpectedAuthorisationState() {
        String token = "one-time-token-123";
        AuthoriseRequest request =
                new AuthoriseRequest(token, "4242424242424242", "123", "11/99", "Job Bogs");

        doReturn(tokenEntity).when(mockTokenService).validateAndMarkTokenAsUsedForMotoApi(token);
        mockAuthorisationResponse(REQUIRES_3DS);

        Response response = resources.target("/v1/api/charges/authorise")
                .request().post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

        assertThat(response.getStatus(), is(500));
        ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
        assertThat(errorResponse.getMessages(), hasItem("Authorisation status unexpected"));
        assertThat(errorResponse.getIdentifier(), is(GENERIC));
    }

    @Test
    void authoriseMotoApiPaymentShouldReturnGenericErrorWhenBothAuthorisationStatusAndGatewayErrorAreNotAvaialable() {
        String token = "one-time-token-123";
        AuthoriseRequest request =
                new AuthoriseRequest(token, "4242424242424242", "123", "11/99", "Job Bogs");

        doReturn(tokenEntity).when(mockTokenService).validateAndMarkTokenAsUsedForMotoApi(token);

        GatewayResponse<BaseAuthoriseResponse> operationResponse = mock(GatewayResponse.class);
        BaseAuthoriseResponse baseAuthoriseResponse = mock(BaseAuthoriseResponse.class);
        when(operationResponse.getBaseResponse()).thenReturn(Optional.of(baseAuthoriseResponse));

        AuthorisationResponse authorisationResponse = new AuthorisationResponse(operationResponse);

        when(mockCardAuthoriseService.doAuthoriseMotoApi(any(), any(), any())).thenReturn(authorisationResponse);

        Response response = resources.target("/v1/api/charges/authorise")
                .request().post(Entity.entity(request, MediaType.APPLICATION_JSON_TYPE));

        assertThat(response.getStatus(), is(500));
        ErrorResponse errorResponse = response.readEntity(ErrorResponse.class);
        assertThat(errorResponse.getMessages(), hasItem("InterpretedStatus not found for Gateway response"));
        assertThat(errorResponse.getIdentifier(), is(GENERIC));
    }

    private void mockGatewayError(GatewayError gatewayError) {
        GatewayResponse<BaseAuthoriseResponse> operationResponse = mock(GatewayResponse.class);
        when(operationResponse.getGatewayError()).thenReturn(Optional.of(gatewayError));

        AuthorisationResponse authorisationResponse = new AuthorisationResponse(operationResponse);

        when(mockCardAuthoriseService.doAuthoriseMotoApi(any(), any(), any())).thenReturn(authorisationResponse);
    }

    private static void mockAuthorisationResponse(BaseAuthoriseResponse.AuthoriseStatus authoriseStatus) {
        GatewayResponse<BaseAuthoriseResponse> operationResponse = mock(GatewayResponse.class);
        BaseAuthoriseResponse baseAuthoriseResponse = mock(BaseAuthoriseResponse.class);

        when(operationResponse.getBaseResponse()).thenReturn(Optional.of(baseAuthoriseResponse));
        when(baseAuthoriseResponse.authoriseStatus()).thenReturn(authoriseStatus);

        AuthorisationResponse authorisationResponse = new AuthorisationResponse(operationResponse);

        when(mockCardAuthoriseService.doAuthoriseMotoApi(any(), any(), any())).thenReturn(authorisationResponse);
    }
}
