package uk.gov.pay.connector.gateway.processor;

import junitparams.JUnitParamsRunner;
import org.exparity.hamcrest.date.ZonedDateTimeMatchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import uk.gov.pay.connector.charge.model.domain.Charge;
import uk.gov.pay.connector.charge.model.domain.ChargeEntity;
import uk.gov.pay.connector.charge.model.domain.ChargeEntityFixture;
import uk.gov.pay.connector.charge.service.ChargeService;
import uk.gov.pay.connector.events.EventService;
import uk.gov.pay.connector.events.model.Event;
import uk.gov.pay.connector.events.model.ResourceType;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntityFixture;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.verify;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.AUTHORISATION_ERROR;
import static uk.gov.pay.connector.charge.model.domain.ChargeStatus.CAPTURED;

@RunWith(JUnitParamsRunner.class)
public class ChargeNotificationProcessorTest {
    
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    protected static final long GATEWAY_ACCOUNT_ID = 10L;
    
    protected ChargeNotificationProcessor chargeNotificationProcessor;
    protected GatewayAccountEntity gatewayAccount;

    @Mock
    protected ChargeService chargeService;
    
    @Mock
    protected EventService eventService;
    
    @Before
    public void setUp() {
        gatewayAccount = GatewayAccountEntityFixture
                .aGatewayAccountEntity()
                .build();
        gatewayAccount.setId(GATEWAY_ACCOUNT_ID);
        chargeNotificationProcessor = new ChargeNotificationProcessor(chargeService, eventService);
    }
    
    @Test
    public void receivedCaptureNotification_shouldEmitEvent() {
        ChargeEntity chargeEntity = ChargeEntityFixture.aValidChargeEntity().withStatus(AUTHORISATION_ERROR).build();
        Charge charge = Charge.from(chargeEntity);
        
        chargeNotificationProcessor.processCaptureNotificationForExpungedCharge(gatewayAccount, charge.getGatewayTransactionId(), charge,  CAPTURED);
        
        ArgumentCaptor<Event> eventArgumentCaptor = ArgumentCaptor.forClass(Event.class);
        
        verify(eventService).emitEvent(eventArgumentCaptor.capture());
        Event event = eventArgumentCaptor.getValue();

        assertThat(event.getEventType(), is("CAPTURE_CONFIRMED_BY_GATEWAY_NOTIFICATION"));
        assertThat(event.getTimestamp(), ZonedDateTimeMatchers.within(5, ChronoUnit.SECONDS, ZonedDateTime.now()));
        assertThat(event.getResourceType(), is(ResourceType.PAYMENT));
    }
}
