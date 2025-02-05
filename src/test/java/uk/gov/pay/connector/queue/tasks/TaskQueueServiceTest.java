package uk.gov.pay.connector.queue.tasks;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uk.gov.pay.connector.app.StripeGatewayConfig;
import uk.gov.pay.connector.charge.model.domain.ChargeStatus;
import uk.gov.pay.connector.charge.model.domain.FeeEntity;
import uk.gov.pay.connector.charge.model.domain.FeeType;
import uk.gov.pay.connector.gateway.PaymentGatewayName;
import uk.gov.pay.connector.queue.tasks.model.PaymentTaskData;
import uk.gov.pay.connector.queue.tasks.model.Task;
import uk.gov.service.payments.commons.queue.exception.QueueException;

import java.time.Instant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static uk.gov.pay.connector.charge.model.domain.ChargeEntityFixture.aValidChargeEntity;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntityFixture.aGatewayAccountEntity;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccountType.LIVE;
import static uk.gov.pay.connector.gatewayaccount.model.GatewayAccountType.TEST;

@ExtendWith(MockitoExtension.class)
class TaskQueueServiceTest {

    @Mock
    private TaskQueue mockTaskQueue;

    @Mock
    private StripeGatewayConfig mockStripeGatewayConfig;
    
    private TaskQueueService taskQueueService;

    @Mock
    private Appender<ILoggingEvent> mockAppender;

    @Captor
    ArgumentCaptor<LoggingEvent> loggingEventArgumentCaptor;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final int chargeCreatedDate =  1630105200;

    @BeforeEach
    void setUp() {
        taskQueueService = new TaskQueueService(mockTaskQueue, mockStripeGatewayConfig, objectMapper);
        Logger logger = (Logger) LoggerFactory.getLogger(TaskQueueService.class);
        logger.setLevel(Level.INFO);
        logger.addAppender(mockAppender);
    }

    @Test
    void shouldNotOfferFeeTask_whenChargeIsFailedButNotTerminal() throws Exception {
        var chargeEntity = aValidChargeEntity()
                .withPaymentProvider(PaymentGatewayName.STRIPE.getName())
                .withGatewayTransactionId("a-gateway-transaction-id")
                .withStatus(ChargeStatus.EXPIRE_CANCEL_READY)
                .withCreatedDate(Instant.ofEpochSecond(chargeCreatedDate))
                .withGatewayAccountEntity(aGatewayAccountEntity()
                        .withId(12L)
                        .withGatewayName("stripe")
                        .withRequires3ds(false)
                        .withType(LIVE)
                        .build())
                .build();

        taskQueueService.offerTasksOnStateTransition(chargeEntity);

        verify(mockTaskQueue, never()).addTaskToQueue(any());
    }

    @Test
    void shouldNotOfferFeeTask_whenChargeIsCaptured() throws Exception {
        var chargeEntity = aValidChargeEntity()
                .withPaymentProvider(PaymentGatewayName.STRIPE.getName())
                .withGatewayTransactionId("a-gateway-transaction-id")
                .withStatus(ChargeStatus.CAPTURED)
                .withCreatedDate(Instant.ofEpochSecond(chargeCreatedDate))
                .withGatewayAccountEntity(aGatewayAccountEntity()
                        .withId(12L)
                        .withGatewayName("stripe")
                        .withRequires3ds(false)
                        .withType(LIVE)
                        .build())
                .build();

        taskQueueService.offerTasksOnStateTransition(chargeEntity);

        verify(mockTaskQueue, never()).addTaskToQueue(any());
    }

    @Test
    void shouldNotOfferFeeTask_whenChargeIsNotStripe() throws Exception {
        var chargeEntity = aValidChargeEntity()
                .withPaymentProvider(PaymentGatewayName.WORLDPAY.getName())
                .withGatewayTransactionId("a-gateway-transaction-id")
                .withStatus(ChargeStatus.EXPIRED)
                .withGatewayAccountEntity(aGatewayAccountEntity()
                        .withId(12L)
                        .withGatewayName("worldpay")
                        .withRequires3ds(false)
                        .withType(LIVE)
                        .build())
                .build();

        taskQueueService.offerTasksOnStateTransition(chargeEntity);

        verify(mockTaskQueue, never()).addTaskToQueue(any());
    }

    @Test
    void shouldNotOfferFeeTask_whenChargeHasNoGatewayTransactionId() throws Exception {
        var chargeEntity = aValidChargeEntity()
                .withPaymentProvider(PaymentGatewayName.STRIPE.getName())
                .withStatus(ChargeStatus.EXPIRED)
                .withGatewayAccountEntity(aGatewayAccountEntity()
                        .withId(12L)
                        .withGatewayName("stripe")
                        .withRequires3ds(false)
                        .withType(LIVE)
                        .build())
                .build();

        taskQueueService.offerTasksOnStateTransition(chargeEntity);

        verify(mockTaskQueue, never()).addTaskToQueue(any());
    }

    @Test
    void shouldNotOfferFeeTask_whenChargeHasFees() throws Exception {
        var chargeEntity = aValidChargeEntity()
                .withPaymentProvider(PaymentGatewayName.STRIPE.getName())
                .withGatewayTransactionId("a-gateway-transaction-id")
                .withStatus(ChargeStatus.EXPIRED)
                .withCreatedDate(Instant.ofEpochSecond(chargeCreatedDate))
                .withGatewayAccountEntity(aGatewayAccountEntity()
                        .withId(12L)
                        .withGatewayName("stripe")
                        .withRequires3ds(false)
                        .withType(LIVE)
                        .build())
                .build();
        var feeEntity = new FeeEntity(chargeEntity, Instant.now(), 10L, FeeType.RADAR);
        chargeEntity.addFee(feeEntity);

        taskQueueService.offerTasksOnStateTransition(chargeEntity);

        verify(mockTaskQueue, never()).addTaskToQueue(any());
    }

    @Test
    void shouldSwallowExceptionThrownWhenAddingToQueue() throws Exception {
        doThrow(new QueueException("Something went wrong")).when(mockTaskQueue).addTaskToQueue(any());

        var chargeEntity = aValidChargeEntity()
                .withPaymentProvider(PaymentGatewayName.STRIPE.getName())
                .withGatewayTransactionId("a-gateway-transaction-id")
                .withStatus(ChargeStatus.EXPIRED)
                .withGatewayAccountEntity(aGatewayAccountEntity()
                        .withId(12L)
                        .withGatewayName("stripe")
                        .withRequires3ds(false)
                        .withType(TEST)
                        .build())
                .build();

        taskQueueService.offerTasksOnStateTransition(chargeEntity);

        verify(mockAppender).doAppend(loggingEventArgumentCaptor.capture());

        LoggingEvent loggingEvent = loggingEventArgumentCaptor.getValue();
        assertThat(loggingEvent.getLevel(), is(Level.WARN));
        assertThat(loggingEvent.getMessage(), is("Error adding payment task message to queue"));
    }

    @Test
    void shouldOfferFeeTask_whenPaymentFailedAndIsStripe() throws Exception {
        var chargeEntity = aValidChargeEntity()
                .withPaymentProvider(PaymentGatewayName.STRIPE.getName())
                .withGatewayTransactionId("a-gateway-transaction-id")
                .withStatus(ChargeStatus.EXPIRED)
                .withCreatedDate(Instant.ofEpochSecond(chargeCreatedDate))
                .withGatewayAccountEntity(aGatewayAccountEntity()
                        .withId(12L)
                        .withGatewayName("stripe")
                        .withRequires3ds(false)
                        .withType(LIVE)
                        .build())
                .build();

        taskQueueService.offerTasksOnStateTransition(chargeEntity);
        String data = objectMapper.writeValueAsString(new PaymentTaskData(chargeEntity.getExternalId()));
        var expectedPaymentTask = new Task(data, TaskType.COLLECT_FEE_FOR_STRIPE_FAILED_PAYMENT);
        verify(mockTaskQueue).addTaskToQueue(eq(expectedPaymentTask));    
    }

    @Test
    void shouldNotOfferFeeTask_whenNoneOfTheConditionsMet() throws Exception {
        var chargeEntity = aValidChargeEntity()
                .withPaymentProvider(PaymentGatewayName.STRIPE.getName())
                .withGatewayTransactionId("a-gateway-transaction-id")
                .withStatus(ChargeStatus.CAPTURED)
                .withCreatedDate(Instant.ofEpochSecond(chargeCreatedDate))
                .withGatewayAccountEntity(aGatewayAccountEntity()
                        .withId(12L)
                        .withGatewayName("stripe")
                        .withRequires3ds(false)
                        .withType(TEST)
                        .build())
                .build();

        taskQueueService.offerTasksOnStateTransition(chargeEntity);

        verify(mockTaskQueue, never()).addTaskToQueue(any());
    }
    
    @Test
    void shouldAddTaskToQueue() throws QueueException, JsonProcessingException {
        var task = new Task("some data", TaskType.HANDLE_STRIPE_WEBHOOK_NOTIFICATION);
        taskQueueService.add(task);
        verify(mockTaskQueue).addTaskToQueue(eq(task));
    }
    
    @Test
    void shouldLogErrorForFailedTaskAddition() throws QueueException, JsonProcessingException {
        doThrow(new QueueException("Something went wrong")).when(mockTaskQueue).addTaskToQueue(any());
        var task = new Task("some data", TaskType.HANDLE_STRIPE_WEBHOOK_NOTIFICATION);
        taskQueueService.add(task);
        verify(mockAppender).doAppend(loggingEventArgumentCaptor.capture());
        LoggingEvent loggingEvent = loggingEventArgumentCaptor.getValue();
        assertThat(loggingEvent.getLevel(), is(Level.ERROR));
        assertThat(loggingEvent.getMessage(), is("Error adding task to queue"));
    }
}
