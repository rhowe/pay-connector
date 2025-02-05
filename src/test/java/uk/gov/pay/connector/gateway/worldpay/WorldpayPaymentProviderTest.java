package uk.gov.pay.connector.gateway.worldpay;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import io.dropwizard.setup.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.app.ConnectorConfiguration;
import uk.gov.pay.connector.charge.dao.ChargeDao;
import uk.gov.pay.connector.charge.model.domain.Charge;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.model.domain.ChargeEntityFixture;
import uk.gov.pay.connector.charge.model.domain.ChargeStatus;
import uk.gov.pay.connector.events.EventService;
import uk.gov.pay.connector.events.eventdetails.charge.Gateway3dsExemptionResultObtainedEventDetails;
import uk.gov.pay.connector.events.model.Event;
import uk.gov.pay.connector.gateway.ChargeQueryGatewayRequest;
import uk.gov.pay.connector.gateway.ChargeQueryResponse;
import uk.gov.pay.connector.gateway.GatewayClient;
import uk.gov.pay.connector.gateway.GatewayException;
import uk.gov.pay.connector.gateway.GatewayOrder;
import uk.gov.pay.connector.gateway.model.Auth3dsResult;
import uk.gov.pay.connector.gateway.model.ProviderSessionIdentifier;
import uk.gov.pay.connector.gateway.model.request.Auth3dsResponseGatewayRequest;
import uk.gov.pay.connector.gateway.model.request.CardAuthorisationGatewayRequest;
import uk.gov.pay.connector.gateway.model.response.Gateway3DSAuthorisationResponse;
import uk.gov.pay.connector.gateway.model.response.GatewayResponse;
import uk.gov.pay.connector.gateway.util.AuthUtil;
import uk.gov.pay.connector.gateway.util.AuthorisationRequestSummaryStringifier;
import uk.gov.pay.connector.gateway.util.AuthorisationRequestSummaryStructuredLogging;
import uk.gov.pay.connector.gateway.worldpay.wallets.WorldpayWalletAuthorisationHandler;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.logging.AuthorisationLogger;
import uk.gov.pay.connector.paymentprocessor.model.Exemption3ds;
import uk.gov.pay.connector.paymentprocessor.service.AuthorisationService;
import uk.gov.pay.connector.paymentprocessor.service.CardExecutorService;

import javax.ws.rs.WebApplicationException;
import java.net.HttpCookie;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.UUID.randomUUID;
import static org.custommonkey.xmlunit.XMLAssert.assertXMLEqual;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.pay.connector.charge.model.domain.ChargeEntityFixture.aValidChargeEntity;
import static uk.gov.pay.connector.gateway.PaymentGatewayName.WORLDPAY;
import static uk.gov.pay.connector.gateway.model.GatewayError.gatewayConnectionError;
import static uk.gov.pay.connector.gateway.model.response.GatewayResponse.GatewayResponseBuilder.responseBuilder;
import static uk.gov.pay.connector.gateway.util.XMLUnmarshaller.unmarshall;
import static uk.gov.pay.connector.gateway.worldpay.WorldpayPaymentProvider.WORLDPAY_MACHINE_COOKIE_NAME;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccount.CREDENTIALS_MERCHANT_ID;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccount.CREDENTIALS_PASSWORD;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccount.CREDENTIALS_USERNAME;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntityFixture.aGatewayAccountEntity;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccountType.TEST;
import static uk.gov.pay.connector.gatewayaccount.model.Worldpay3dsFlexCredentialsEntity.Worldpay3dsFlexCredentialsEntityBuilder.aWorldpay3dsFlexCredentialsEntity;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialState.ACTIVE;
import static uk.gov.pay.connector.gatewayaccountcredentials.model.GatewayAccountCredentialsEntityFixture.aGatewayAccountCredentialsEntity;
import static uk.gov.pay.connector.model.domain.AuthCardDetailsFixture.anAuthCardDetails;
import static uk.gov.pay.connector.paymentprocessor.model.Exemption3ds.EXEMPTION_HONOURED;
import static uk.gov.pay.connector.paymentprocessor.model.Exemption3ds.EXEMPTION_NOT_REQUESTED;
import static uk.gov.pay.connector.paymentprocessor.model.Exemption3ds.EXEMPTION_OUT_OF_SCOPE;
import static uk.gov.pay.connector.paymentprocessor.model.Exemption3ds.EXEMPTION_REJECTED;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.WORLDPAY_3DS_FLEX_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.WORLDPAY_3DS_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.WORLDPAY_AUTHORISATION_SUCCESS_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.WORLDPAY_AUTHORISED_INQUIRY_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.WORLDPAY_ERROR_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.WORLDPAY_EXEMPTION_REQUEST_DECLINE_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.WORLDPAY_EXEMPTION_REQUEST_HONOURED_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.WORLDPAY_EXEMPTION_REQUEST_REJECTED_AUTHORISED_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.WORLDPAY_EXEMPTION_REQUEST_SOFT_DECLINE_RESULT_OUT_OF_SCOPE_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.WORLDPAY_EXEMPTION_REQUEST_SOFT_DECLINE_RESULT_REJECTED_RESPONSE;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.WORLDPAY_VALID_3DS_FLEX_RESPONSE_AUTH_WORLDPAY_REQUEST;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.WORLDPAY_VALID_3DS_RESPONSE_AUTH_WORLDPAY_REQUEST;
import static uk.gov.pay.connector.util.TestTemplateResourceLoader.load;

@ExtendWith(MockitoExtension.class)
public class WorldpayPaymentProviderTest {

    private static final URI WORLDPAY_URL = URI.create("http://worldpay.url");
    private static final Map<String, URI> GATEWAY_URL_MAP = Map.of(TEST.toString(), WORLDPAY_URL);

    @Mock
    private GatewayClient authoriseClient;
    @Mock
    private GatewayClient cancelClient;
    @Mock
    private GatewayClient inquiryClient;
    @Mock
    private WorldpayWalletAuthorisationHandler worldpayWalletAuthorisationHandler;
    @Mock
    private WorldpayAuthoriseHandler worldpayAuthoriseHandler;
    @Mock
    private WorldpayCaptureHandler worldpayCaptureHandler;
    @Mock
    private WorldpayRefundHandler worldpayRefundHandler;
    @Mock
    private GatewayClient.Response response = mock(GatewayClient.Response.class);
    @Mock
    private Appender<ILoggingEvent> mockAppender;
    @Mock
    private ChargeDao chargeDao;
    @Mock
    private EventService eventService;
    @Captor
    private ArgumentCaptor<CardAuthorisationGatewayRequest> cardAuthorisationGatewayRequestArgumentCaptor;

    private WorldpayPaymentProvider worldpayPaymentProvider;
    private ChargeEntityFixture chargeEntityFixture;
    protected GatewayAccountEntity gatewayAccountEntity;

    @BeforeEach
    void setup() {
        worldpayPaymentProvider = new WorldpayPaymentProvider(
                GATEWAY_URL_MAP,
                authoriseClient,
                cancelClient,
                inquiryClient,
                worldpayWalletAuthorisationHandler,
                worldpayAuthoriseHandler,
                worldpayCaptureHandler,
                worldpayRefundHandler,
                new AuthorisationService(mock(CardExecutorService.class), mock(Environment.class), mock(ConnectorConfiguration.class)),
                new AuthorisationLogger(new AuthorisationRequestSummaryStringifier(), new AuthorisationRequestSummaryStructuredLogging()),
                chargeDao,
                eventService);

        gatewayAccountEntity = aServiceAccount();
        chargeEntityFixture = aValidChargeEntity()
                .withGatewayAccountCredentialsEntity(aGatewayAccountCredentialsEntity()
                        .withPaymentProvider("worldpay")
                        .withCredentials(Map.of(
                                CREDENTIALS_MERCHANT_ID, "MERCHANTCODE",
                                CREDENTIALS_USERNAME, "worldpay-password",
                                CREDENTIALS_PASSWORD, "password"
                        ))
                        .build())
                .withGatewayAccountEntity(gatewayAccountEntity);

        Logger root = (Logger) LoggerFactory.getLogger(WorldpayPaymentProvider.class);
        root.setLevel(Level.INFO);
        root.addAppender(mockAppender);
    }

    @Test
    void should_not_update_exemption_3ds_null_when_authorisation_results_in_error() {

        gatewayAccountEntity.setRequires3ds(true);
        gatewayAccountEntity.setIntegrationVersion3ds(1);
        gatewayAccountEntity.setWorldpay3dsFlexCredentialsEntity(aWorldpay3dsFlexCredentialsEntity().withExemptionEngine(true).build());
        chargeEntityFixture.withGatewayAccountEntity(gatewayAccountEntity);
        ChargeEntity chargeEntity = chargeEntityFixture.build();
        var cardAuthRequest = new CardAuthorisationGatewayRequest(chargeEntity, anAuthCardDetails().build());

        when(worldpayAuthoriseHandler.authoriseWithExemption(cardAuthRequest))
                .thenReturn(responseBuilder().withGatewayError(gatewayConnectionError("connetion problemo")).build());

        worldpayPaymentProvider.authorise(cardAuthRequest, chargeEntity);

        verify(chargeDao, never()).merge(any(ChargeEntity.class));
    }

    @Test
    void should_not_include_exemption_if_account_has_no_worldpay_3ds_flex_credentials() throws Exception {

        gatewayAccountEntity.setRequires3ds(true);
        gatewayAccountEntity.setIntegrationVersion3ds(1);
        gatewayAccountEntity.setWorldpay3dsFlexCredentialsEntity(null);
        chargeEntityFixture.withGatewayAccountEntity(gatewayAccountEntity);

        ChargeEntity chargeEntity = chargeEntityFixture.build();
        var cardAuthRequest = new CardAuthorisationGatewayRequest(chargeEntity, anAuthCardDetails().build());

        when(worldpayAuthoriseHandler.authoriseWithoutExemption(cardAuthRequest))
                .thenReturn(getGatewayResponse(WORLDPAY_AUTHORISATION_SUCCESS_RESPONSE));

        worldpayPaymentProvider.authorise(cardAuthRequest, chargeEntity);

        verifyChargeUpdatedWith(EXEMPTION_NOT_REQUESTED);
        verifyEventEmitted(chargeEntity, EXEMPTION_NOT_REQUESTED);
    }

    private void verifyChargeUpdatedWith(Exemption3ds exemption3ds) {
        ArgumentCaptor<ChargeEntity> chargeDaoArgumentCaptor = ArgumentCaptor.forClass(ChargeEntity.class);
        verify(chargeDao).merge(chargeDaoArgumentCaptor.capture());
        assertThat(chargeDaoArgumentCaptor.getValue().getExemption3ds(), is(exemption3ds));
    }

    @Test
    void should_not_include_exemption_if_account_has_exemption_engine_set_to_false() throws Exception {

        gatewayAccountEntity.setRequires3ds(true);
        gatewayAccountEntity.setIntegrationVersion3ds(1);
        gatewayAccountEntity.setWorldpay3dsFlexCredentialsEntity(
                aWorldpay3dsFlexCredentialsEntity().withExemptionEngine(false).build());
        chargeEntityFixture.withGatewayAccountEntity(gatewayAccountEntity);

        ChargeEntity chargeEntity = chargeEntityFixture.build();
        var cardAuthRequest = new CardAuthorisationGatewayRequest(chargeEntity, anAuthCardDetails().build());

        when(worldpayAuthoriseHandler.authoriseWithoutExemption(cardAuthRequest))
                .thenReturn(getGatewayResponse(WORLDPAY_AUTHORISATION_SUCCESS_RESPONSE));

        worldpayPaymentProvider.authorise(cardAuthRequest, chargeEntity);

        verifyChargeUpdatedWith(EXEMPTION_NOT_REQUESTED);
        verifyEventEmitted(chargeEntity, EXEMPTION_NOT_REQUESTED);
    }

    @Test
    void should_not_include_exemption_if_account_has_exemption_engine_set_to_true_but_3ds_is_not_enabled() throws Exception {

        gatewayAccountEntity.setRequires3ds(false);
        gatewayAccountEntity.setIntegrationVersion3ds(1);
        gatewayAccountEntity.setWorldpay3dsFlexCredentialsEntity(aWorldpay3dsFlexCredentialsEntity().withExemptionEngine(true).build());
        chargeEntityFixture.withGatewayAccountEntity(gatewayAccountEntity);

        ChargeEntity chargeEntity = chargeEntityFixture.build();
        var cardAuthRequest = new CardAuthorisationGatewayRequest(chargeEntity, anAuthCardDetails().build());

        when(worldpayAuthoriseHandler.authoriseWithoutExemption(cardAuthRequest))
                .thenReturn(getGatewayResponse(WORLDPAY_AUTHORISATION_SUCCESS_RESPONSE));

        worldpayPaymentProvider.authorise(cardAuthRequest, chargeEntity);

        verifyChargeUpdatedWith(EXEMPTION_NOT_REQUESTED);
        verifyEventEmitted(chargeEntity, EXEMPTION_NOT_REQUESTED);
    }

    @Test
    void should_set_exemption_rejected_when_request_made_with_an_exemption() throws Exception {

        gatewayAccountEntity.setRequires3ds(true);
        gatewayAccountEntity.setIntegrationVersion3ds(1);
        gatewayAccountEntity.setWorldpay3dsFlexCredentialsEntity(aWorldpay3dsFlexCredentialsEntity().withExemptionEngine(true).build());
        chargeEntityFixture.withGatewayAccountEntity(gatewayAccountEntity);
        ChargeEntity chargeEntity = chargeEntityFixture.build();
        var cardAuthRequest = new CardAuthorisationGatewayRequest(chargeEntity, anAuthCardDetails().build());

        when(worldpayAuthoriseHandler.authoriseWithExemption(cardAuthRequest))
                .thenReturn(getGatewayResponse(WORLDPAY_EXEMPTION_REQUEST_SOFT_DECLINE_RESULT_REJECTED_RESPONSE));
        when(worldpayAuthoriseHandler.authoriseWithoutExemption(any(CardAuthorisationGatewayRequest.class)))
                .thenReturn(getGatewayResponse(WORLDPAY_AUTHORISATION_SUCCESS_RESPONSE));

        worldpayPaymentProvider.authorise(cardAuthRequest, chargeEntity);

        verify(worldpayAuthoriseHandler).authoriseWithoutExemption(cardAuthorisationGatewayRequestArgumentCaptor.capture());
        CardAuthorisationGatewayRequest secondRequest = cardAuthorisationGatewayRequestArgumentCaptor.getValue();
        assertThat(secondRequest.getTransactionId(), not(nullValue()));
        assertThat(secondRequest.getTransactionId(), not(chargeEntity.getGatewayTransactionId()));
        verifyChargeUpdatedWith(EXEMPTION_REJECTED);
        verifyEventEmitted(chargeEntity, EXEMPTION_REJECTED);
    }

    @Test
    void should_set_exemption_out_of_scope_when_request_made_with_an_exemption() throws Exception {
        gatewayAccountEntity.setRequires3ds(true);
        gatewayAccountEntity.setIntegrationVersion3ds(1);
        gatewayAccountEntity.setWorldpay3dsFlexCredentialsEntity(aWorldpay3dsFlexCredentialsEntity().withExemptionEngine(true).build());
        chargeEntityFixture.withGatewayAccountEntity(gatewayAccountEntity);
        ChargeEntity chargeEntity = chargeEntityFixture.build();
        var cardAuthRequest = new CardAuthorisationGatewayRequest(chargeEntity, anAuthCardDetails().build());

        when(worldpayAuthoriseHandler.authoriseWithExemption(cardAuthRequest))
                .thenReturn(getGatewayResponse(WORLDPAY_EXEMPTION_REQUEST_SOFT_DECLINE_RESULT_OUT_OF_SCOPE_RESPONSE));
        when(worldpayAuthoriseHandler.authoriseWithoutExemption(any(CardAuthorisationGatewayRequest.class)))
                .thenReturn(getGatewayResponse(WORLDPAY_AUTHORISATION_SUCCESS_RESPONSE));

        worldpayPaymentProvider.authorise(cardAuthRequest, chargeEntity);

        verify(worldpayAuthoriseHandler).authoriseWithoutExemption(cardAuthorisationGatewayRequestArgumentCaptor.capture());
        CardAuthorisationGatewayRequest secondRequest = cardAuthorisationGatewayRequestArgumentCaptor.getValue();
        assertThat(secondRequest.getTransactionId(), not(nullValue()));
        assertThat(secondRequest.getTransactionId(), not(chargeEntity.getGatewayTransactionId()));
        verifyChargeUpdatedWith(EXEMPTION_OUT_OF_SCOPE);
        verifyEventEmitted(chargeEntity, EXEMPTION_OUT_OF_SCOPE);
    }

    @Test
    void should_not_retry_without_exemption_when_authorising_with_exemption_results_in_3ds_challenge_required() throws Exception {
        verifyAuthorisationNotRetried(WORLDPAY_3DS_FLEX_RESPONSE);
    }

    @Test
    void should_not_retry_without_exemption_when_authorising_with_exemption_results_in_request_for_3ds() throws Exception {
        verifyAuthorisationNotRetried(WORLDPAY_3DS_RESPONSE);
    }

    @Test
    void should_not_retry_without_exemption_when_authorising_with_exemption_results_in_exemption_honoured_but_authorisation_refused() throws Exception {
        verifyAuthorisationNotRetried(WORLDPAY_EXEMPTION_REQUEST_DECLINE_RESPONSE);
    }

    @Test
    void should_not_retry_without_exemption_flag_when_authorising_with_exemption_flag_results_in_authorised() throws Exception {
        verifyAuthorisationNotRetried(WORLDPAY_EXEMPTION_REQUEST_HONOURED_RESPONSE);
    }

    private void verifyAuthorisationNotRetried(String worldpayXmlResponse) throws Exception {
        gatewayAccountEntity.setRequires3ds(true);
        gatewayAccountEntity.setIntegrationVersion3ds(1);
        gatewayAccountEntity.setWorldpay3dsFlexCredentialsEntity(aWorldpay3dsFlexCredentialsEntity().withExemptionEngine(true).build());
        chargeEntityFixture.withGatewayAccountEntity(gatewayAccountEntity);
        ChargeEntity chargeEntity = chargeEntityFixture.build();

        var cardAuthRequest = new CardAuthorisationGatewayRequest(chargeEntity, anAuthCardDetails().build());

        when(worldpayAuthoriseHandler.authoriseWithExemption(cardAuthRequest)).thenReturn(getGatewayResponse(worldpayXmlResponse));

        worldpayPaymentProvider.authorise(cardAuthRequest, chargeEntity);

        verify(worldpayAuthoriseHandler, never()).authoriseWithoutExemption(cardAuthRequest);
    }

    @Test
    void should_include_exemption_if_account_has_exemption_engine_set_to_true() throws Exception {

        gatewayAccountEntity.setRequires3ds(true);
        gatewayAccountEntity.setIntegrationVersion3ds(1);
        gatewayAccountEntity.setWorldpay3dsFlexCredentialsEntity(aWorldpay3dsFlexCredentialsEntity().withExemptionEngine(true).build());
        chargeEntityFixture.withGatewayAccountEntity(gatewayAccountEntity);
        ChargeEntity chargeEntity = chargeEntityFixture.build();

        var cardAuthRequest = new CardAuthorisationGatewayRequest(chargeEntity, anAuthCardDetails().build());
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

        when(worldpayAuthoriseHandler.authoriseWithExemption(cardAuthRequest))
                .thenReturn(getGatewayResponse(WORLDPAY_EXEMPTION_REQUEST_HONOURED_RESPONSE));

        worldpayPaymentProvider.authorise(cardAuthRequest, chargeEntity);

        verifyChargeUpdatedWith(EXEMPTION_HONOURED);
        verifyEventEmitted(chargeEntity, EXEMPTION_HONOURED);
    }

    @Test
    void should_retry_without_exemption_flag_when_authorising_with_exemption_flag_results_in_soft_decline() throws Exception {

        gatewayAccountEntity.setRequires3ds(true);
        gatewayAccountEntity.setIntegrationVersion3ds(1);
        gatewayAccountEntity.setWorldpay3dsFlexCredentialsEntity(aWorldpay3dsFlexCredentialsEntity().withExemptionEngine(true).build());
        chargeEntityFixture.withGatewayAccountEntity(gatewayAccountEntity);
        chargeEntityFixture.withStatus(ChargeStatus.AUTHORISATION_READY);
        String gatewayTransactionId = randomUUID().toString();
        chargeEntityFixture.withGatewayTransactionId(gatewayTransactionId);
        ChargeEntity chargeEntity = chargeEntityFixture.build();

        var cardAuthRequest = new CardAuthorisationGatewayRequest(chargeEntity, anAuthCardDetails().build());

        when(worldpayAuthoriseHandler.authoriseWithExemption(cardAuthRequest))
                .thenReturn(getGatewayResponse(WORLDPAY_EXEMPTION_REQUEST_SOFT_DECLINE_RESULT_REJECTED_RESPONSE));

        var secondResponse = getGatewayResponse(WORLDPAY_AUTHORISATION_SUCCESS_RESPONSE);
        when(worldpayAuthoriseHandler.authoriseWithoutExemption(any())).thenReturn(secondResponse);

        GatewayResponse<WorldpayOrderStatusResponse> response = worldpayPaymentProvider.authorise(cardAuthRequest, chargeEntity);

        verify(worldpayAuthoriseHandler).authoriseWithoutExemption(cardAuthorisationGatewayRequestArgumentCaptor.capture());
        CardAuthorisationGatewayRequest secondRequest = cardAuthorisationGatewayRequestArgumentCaptor.getValue();
        assertThat(secondRequest.getTransactionId(), not(nullValue()));
        assertThat(secondRequest.getTransactionId(), not(chargeEntity.getGatewayTransactionId()));

        assertTrue(response.getBaseResponse().isPresent());
        assertEquals(secondResponse.getBaseResponse().get(), response.getBaseResponse().get());

        ArgumentCaptor<LoggingEvent> loggingEventArgumentCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(mockAppender, times(2)).doAppend(loggingEventArgumentCaptor.capture());
        List<LoggingEvent> logs = loggingEventArgumentCaptor.getAllValues();
        assertTrue(logs.stream().anyMatch(loggingEvent -> {
            String log = format("Authorisation with billing address and with 3DS data and without device data " +
                    "collection result for %s", chargeEntity.getExternalId());
            return loggingEvent.getMessage().contains(log);
        }));
        assertTrue(logs.stream().anyMatch(loggingEvent -> {
            String log = "Worldpay authorisation response (orderCode: transaction-id, lastEvent: REFUSED, " +
                    "exemptionResponse result: REJECTED, exemptionResponse reason: HIGH_RISK)";
            return loggingEvent.getMessage().contains(log);
        }));
        assertTrue(logs.stream().anyMatch(loggingEvent ->
                loggingEvent.getMessage().contains("AUTHORISATION READY -> AUTHORISATION READY")));
    }

    private GatewayResponse<WorldpayOrderStatusResponse> getGatewayResponse(String responseFile) throws Exception {
        GatewayResponse.GatewayResponseBuilder<WorldpayOrderStatusResponse> responseBuilder = responseBuilder();
        responseBuilder.withResponse(unmarshall(load(responseFile), WorldpayOrderStatusResponse.class));
        return responseBuilder.build();
    }

    @Test
    void should_get_payment_provider_name() {
        assertThat(worldpayPaymentProvider.getPaymentGatewayName().getName(), is("worldpay"));
    }

    @Test
    void should_generate_transactionId() {
        assertThat(worldpayPaymentProvider.generateTransactionId().isPresent(), is(true));
        assertThat(worldpayPaymentProvider.generateTransactionId().get(), is(instanceOf(String.class)));
    }


    @Test
    void should_log_exemption_3ds_for_charge_during_3ds_authorisation() throws Exception {
        ChargeEntity chargeEntity = chargeEntityFixture.build();

        when(response.getEntity()).thenReturn(load(WORLDPAY_EXEMPTION_REQUEST_REJECTED_AUTHORISED_RESPONSE));
        when(authoriseClient.postRequestFor(any(URI.class), eq(WORLDPAY), eq("test"), any(GatewayOrder.class), anyList(), anyMap()))
                .thenReturn(response);

        worldpayPaymentProvider.authorise3dsResponse(get3dsResponseGatewayRequest(chargeEntity));

        verifyChargeUpdatedWith(EXEMPTION_REJECTED);
        verifyEventEmitted(chargeEntity, EXEMPTION_REJECTED);
    }

    private void verifyEventEmitted(ChargeEntity chargeEntity, Exemption3ds exemption3ds) {
        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);
        verify(eventService, times(1)).emitAndRecordEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getResourceExternalId(), is(chargeEntity.getExternalId()));
        assertThat(((Gateway3dsExemptionResultObtainedEventDetails) eventCaptor.getValue().getEventDetails()).getExemption3ds(),
                is(exemption3ds.toString()));
        assertThat(eventCaptor.getValue().getEventType(), is("GATEWAY_3DS_EXEMPTION_RESULT_OBTAINED"));
    }

    @Test
    void should_include_paResponse_In_3ds_second_order() throws Exception {
        ChargeEntity chargeEntity = chargeEntityFixture
                .withExternalId("uniqueSessionId")
                .withTransactionId("MyUniqueTransactionId!")
                .build();

        when(authoriseClient.postRequestFor(any(URI.class), eq(WORLDPAY), eq("test"), any(GatewayOrder.class), anyList(), anyMap()))
                .thenThrow(new GatewayException.GatewayErrorException("Unexpected HTTP status code 401 from gateway"));

        worldpayPaymentProvider.authorise3dsResponse(get3dsResponseGatewayRequest(chargeEntity));

        ArgumentCaptor<GatewayOrder> gatewayOrderArgumentCaptor = ArgumentCaptor.forClass(GatewayOrder.class);

        verify(authoriseClient).postRequestFor(
                eq(WORLDPAY_URL),
                eq(WORLDPAY), eq("test"),
                gatewayOrderArgumentCaptor.capture(),
                anyList(),
                anyMap());

        assertXMLEqual(load(WORLDPAY_VALID_3DS_RESPONSE_AUTH_WORLDPAY_REQUEST),
                gatewayOrderArgumentCaptor.getValue().getPayload());
    }

    @Test
    void should_include_completed_authentication_in_second_order_when_paRequest_not_in_frontend_request() throws Exception {
        ChargeEntity chargeEntity = chargeEntityFixture
                .withExternalId("uniqueSessionId")
                .withTransactionId("MyUniqueTransactionId!")
                .build();

        when(authoriseClient.postRequestFor(any(URI.class), eq(WORLDPAY), eq("test"), any(GatewayOrder.class), anyList(), anyMap()))
                .thenThrow(new GatewayException.GatewayErrorException("Unexpected HTTP status code 401 from gateway"));

        var request = new Auth3dsResponseGatewayRequest(chargeEntity, new Auth3dsResult());
        worldpayPaymentProvider.authorise3dsResponse(request);

        ArgumentCaptor<GatewayOrder> gatewayOrderArgumentCaptor = ArgumentCaptor.forClass(GatewayOrder.class);

        verify(authoriseClient).postRequestFor(
                eq(WORLDPAY_URL),
                eq(WORLDPAY), eq("test"),
                gatewayOrderArgumentCaptor.capture(),
                anyList(),
                anyMap());

        assertXMLEqual(load(WORLDPAY_VALID_3DS_FLEX_RESPONSE_AUTH_WORLDPAY_REQUEST),
                gatewayOrderArgumentCaptor.getValue().getPayload());
    }

    @Test
    void should_include_provider_session_id_when_available_for_charge() throws Exception {
        String providerSessionId = "provider-session-id";
        ChargeEntity chargeEntity = chargeEntityFixture
                .withExternalId("uniqueSessionId")
                .withTransactionId("MyUniqueTransactionId!")
                .withProviderSessionId(providerSessionId)
                .build();

        when(authoriseClient.postRequestFor(any(URI.class), eq(WORLDPAY), eq("test"), any(GatewayOrder.class), anyList(), anyMap()))
                .thenThrow(new GatewayException.GatewayErrorException("Unexpected HTTP status code 400 from gateway"));

        worldpayPaymentProvider.authorise3dsResponse(get3dsResponseGatewayRequest(chargeEntity));

        ArgumentCaptor<List<HttpCookie>> cookies = ArgumentCaptor.forClass(List.class);

        verify(authoriseClient).postRequestFor(
                eq(WORLDPAY_URL),
                eq(WORLDPAY), eq("test"),
                ArgumentCaptor.forClass(GatewayOrder.class).capture(),
                cookies.capture(),
                ArgumentCaptor.forClass(Map.class).capture());

        assertThat(cookies.getValue().size(), is(1));
        assertThat(cookies.getValue().get(0).getName(), is(WORLDPAY_MACHINE_COOKIE_NAME));
        assertThat(cookies.getValue().get(0).getValue(), is(providerSessionId));
    }

    @Test
    void assert_authorization_header_is_passed_to_gateway_client() throws Exception {
        String providerSessionId = "provider-session-id";
        ChargeEntity mockChargeEntity = chargeEntityFixture
                .withExternalId("uniqueSessionId")
                .withTransactionId("MyUniqueTransactionId!")
                .withProviderSessionId(providerSessionId)
                .withGatewayAccountCredentialsEntity(aGatewayAccountCredentialsEntity()
                        .withPaymentProvider("worldpay")
                        .withCredentials(Map.of(
                                CREDENTIALS_MERCHANT_ID, "MERCHANTCODE",
                                CREDENTIALS_USERNAME, "worldpay-password",
                                CREDENTIALS_PASSWORD, "password"
                        ))
                        .build())
                .build();

        when(authoriseClient.postRequestFor(any(URI.class), eq(WORLDPAY), eq("test"), any(GatewayOrder.class), anyList(), anyMap()))
                .thenThrow(new GatewayException.GatewayErrorException("Unexpected HTTP status code 400 from gateway"));

        worldpayPaymentProvider.authorise3dsResponse(get3dsResponseGatewayRequest(mockChargeEntity));

        ArgumentCaptor<GatewayOrder> gatewayOrderArgumentCaptor = ArgumentCaptor.forClass(GatewayOrder.class);
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);

        verify(authoriseClient).postRequestFor(
                eq(WORLDPAY_URL),
                eq(WORLDPAY), eq("test"),
                gatewayOrderArgumentCaptor.capture(),
                anyList(),
                headers.capture());

        assertThat(headers.getValue().size(), is(1));
        assertThat(headers.getValue(), is(AuthUtil.getGatewayAccountCredentialsAsAuthHeader(gatewayAccountEntity.getGatewayAccountCredentials().get(0).getCredentials())));
    }

    @Test
    void should_successfully_query_payment_status() throws Exception {
        when(response.getEntity()).thenReturn(load(WORLDPAY_AUTHORISED_INQUIRY_RESPONSE));

        ChargeEntity chargeEntity = chargeEntityFixture.build();

        when(inquiryClient.postRequestFor(any(URI.class), eq(WORLDPAY), eq("test"), any(GatewayOrder.class), anyMap()))
                .thenReturn(response);

        ChargeQueryGatewayRequest chargeQueryGatewayRequest = ChargeQueryGatewayRequest.valueOf(Charge.from(chargeEntity), chargeEntity.getGatewayAccount(), chargeEntity.getGatewayAccountCredentialsEntity());
        ChargeQueryResponse chargeQueryResponse = worldpayPaymentProvider.queryPaymentStatus(chargeQueryGatewayRequest);

        assertThat(chargeQueryResponse.getMappedStatus(), is(Optional.of(ChargeStatus.AUTHORISATION_SUCCESS)));
        assertThat(chargeQueryResponse.foundCharge(), is(true));
    }

    @Test
    void query_payment_status_should_return_response_with_gateway_error_when_worldpay_returns_could_not_find_payment_for_order_message() throws Exception {
        when(response.getEntity()).thenReturn(
                load(WORLDPAY_ERROR_RESPONSE)
                        .replace("{{errorDescription}}", "Could not find payment for order")
        );

        ChargeEntity chargeEntity = chargeEntityFixture.build();

        when(inquiryClient.postRequestFor(any(URI.class), eq(WORLDPAY), eq("test"), any(GatewayOrder.class), anyMap()))
                .thenReturn(response);

        ChargeQueryGatewayRequest chargeQueryGatewayRequest = ChargeQueryGatewayRequest.valueOf(Charge.from(chargeEntity), chargeEntity.getGatewayAccount(), chargeEntity.getGatewayAccountCredentialsEntity());
        ChargeQueryResponse chargeQueryResponse = worldpayPaymentProvider.queryPaymentStatus(chargeQueryGatewayRequest);

        assertThat(chargeQueryResponse.foundCharge(), is(false));
        assertThat(chargeQueryResponse.getGatewayError().isPresent(), is(true));
        assertThat(chargeQueryResponse.getGatewayError().get().getMessage(), is("Worldpay query response (error code: 5, error: Could not find payment for order)"));
    }

    @Test
    void query_payment_status_should_throw_error_when_worldpay_returns_error_with_unknown_message() throws Exception {
        when(response.getEntity()).thenReturn(
                load(WORLDPAY_ERROR_RESPONSE)
                        .replace("{{errorDescription}}", "Order inquiries have been disabled for all merchants")
        );

        ChargeEntity chargeEntity = chargeEntityFixture.build();

        when(inquiryClient.postRequestFor(any(URI.class), eq(WORLDPAY), eq("test"), any(GatewayOrder.class), anyMap()))
                .thenReturn(response);

        ChargeQueryGatewayRequest chargeQueryGatewayRequest = ChargeQueryGatewayRequest.valueOf(Charge.from(chargeEntity), chargeEntity.getGatewayAccount(), chargeEntity.getGatewayAccountCredentialsEntity());

        assertThrows(WebApplicationException.class, () -> {
            worldpayPaymentProvider.queryPaymentStatus(chargeQueryGatewayRequest);
        });
    }

    @Test
    void should_construct_gateway_3DS_authorisation_response_with_paRequest_issuerUrl_and_machine_cookie_if_worldpay_asks_us_to_do_3ds_again() throws Exception {
        ChargeEntity chargeEntity = chargeEntityFixture.withProviderSessionId("original-machine-cookie").build();

        when(response.getEntity()).thenReturn(load(WORLDPAY_3DS_RESPONSE));
        when(response.getResponseCookies()).thenReturn(Map.of(WORLDPAY_MACHINE_COOKIE_NAME, "new-machine-cookie-value"));

        when(authoriseClient.postRequestFor(eq(WORLDPAY_URL), eq(WORLDPAY), eq("test"), any(GatewayOrder.class),
                eq(List.of(new HttpCookie(WORLDPAY_MACHINE_COOKIE_NAME, "original-machine-cookie"))), anyMap()))
                .thenReturn(response);

        var auth3dsResult = new Auth3dsResult();
        auth3dsResult.setPaResponse("pa-response");
        var auth3dsResponseGatewayRequest = new Auth3dsResponseGatewayRequest(chargeEntity, auth3dsResult);

        Gateway3DSAuthorisationResponse result = worldpayPaymentProvider.authorise3dsResponse(auth3dsResponseGatewayRequest);

        assertThat(result.getGateway3dsRequiredParams().isPresent(), is(true));
        assertThat(result.getGateway3dsRequiredParams().get().toAuth3dsRequiredEntity().getPaRequest(),
                is("eJxVUsFuwjAM/ZWK80aSUgpFJogNpHEo2hjTzl"));
        assertThat(result.getGateway3dsRequiredParams().get().toAuth3dsRequiredEntity().getIssuerUrl(),
                is("https://secure-test.worldpay.com/jsp/test/shopper/ThreeDResponseSimulator.jsp"));

        assertThat(result.getProviderSessionIdentifier().isPresent(), is(true));
        assertThat(result.getProviderSessionIdentifier().get(), is(ProviderSessionIdentifier.of("new-machine-cookie-value")));
    }

    private Auth3dsResponseGatewayRequest get3dsResponseGatewayRequest(ChargeEntity chargeEntity) {
        Auth3dsResult auth3dsResult = new Auth3dsResult();
        auth3dsResult.setPaResponse("I am an opaque 3D Secure PA response from the card issuer");
        return new Auth3dsResponseGatewayRequest(chargeEntity, auth3dsResult);
    }

    private GatewayAccountEntity aServiceAccount() {
        var gatewayAccountEntity = aGatewayAccountEntity()
                .withId(1L)
                .withGatewayName("worldpay")
                .withRequires3ds(false)
                .withType(TEST)
                .build();

        var creds = aGatewayAccountCredentialsEntity()
                .withCredentials(Map.of(
                        CREDENTIALS_MERCHANT_ID, "MERCHANTCODE",
                        CREDENTIALS_USERNAME, "worldpay-password",
                        CREDENTIALS_PASSWORD, "password"))
                .withGatewayAccountEntity(gatewayAccountEntity)
                .withPaymentProvider(WORLDPAY.getName())
                .withState(ACTIVE)
                .build();

        gatewayAccountEntity.setGatewayAccountCredentials(List.of(creds));

        return gatewayAccountEntity;
    }
}
