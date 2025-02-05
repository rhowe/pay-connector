package uk.gov.pay.connector.paymentinstrument.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import uk.gov.pay.connector.agreement.model.AgreementEntity;
import uk.gov.pay.connector.charge.model.CardDetailsEntity;
import uk.gov.pay.connector.common.model.api.ToLowerCaseStringSerializer;
import uk.gov.pay.connector.gatewayaccount.model.GatewayAccountEntity;
import uk.gov.pay.connector.gatewayaccount.util.JsonToMapConverter;
import uk.gov.pay.connector.util.RandomIdGenerator;
import uk.gov.service.payments.commons.jpa.InstantToUtcTimestampWithoutTimeZoneConverter;

import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.Convert;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import java.time.Instant;
import java.util.Map;


@Entity
@Table(name="payment_instruments")
@Access(AccessType.FIELD)
@SequenceGenerator(name="payment_instruments_id_seq", sequenceName = "payment_instruments_id_seq", allocationSize = 1)
public class PaymentInstrumentEntity {

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "payment_instruments_id_seq")
    private Long id;

    @Column(name="recurring_auth_token", columnDefinition = "json")
    @Convert(converter = JsonToMapConverter.class)
    private Map<String, String> recurringAuthToken;

    @Column(name = "created_date")
    @Convert(converter = InstantToUtcTimestampWithoutTimeZoneConverter.class)
    private Instant createdDate;

    @Column(name = "start_date")
    @Convert(converter = InstantToUtcTimestampWithoutTimeZoneConverter.class)
    private Instant startDate;

    @Column(name="external_id")
    private String externalId;

    private PaymentInstrumentEntity(Instant createdDate, Map<String, String> recurringAuthToken, Instant startDate, CardDetailsEntity cardDetails, PaymentInstrumentStatus paymentInstrumentStatus ) {
        this.createdDate = createdDate;
        this.externalId = RandomIdGenerator.newId();
        this.recurringAuthToken = recurringAuthToken;
        this.startDate = startDate;
        this.cardDetails = cardDetails;
        this.paymentInstrumentStatus = paymentInstrumentStatus;
    }

    public Map<String, String> getRecurringAuthToken() {
        return recurringAuthToken;
    }

    public void setRecurringAuthToken(Map<String, String> recurringAuthToken) {
        this.recurringAuthToken = recurringAuthToken;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Instant createdDate) {
        this.createdDate = createdDate;
    }

    public Instant getStartDate() {
        return startDate;
    }

    public void setStartDate(Instant startDate) {
        this.startDate = startDate;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public PaymentInstrumentStatus getPaymentInstrumentStatus() {
        return paymentInstrumentStatus;
    }

    public void setPaymentInstrumentStatus(PaymentInstrumentStatus paymentInstrumentStatus) {
        this.paymentInstrumentStatus = paymentInstrumentStatus;
    }

    public CardDetailsEntity getCardDetails() {
        return cardDetails;
    }

    public void setCardDetails(CardDetailsEntity cardDetails) {
        this.cardDetails = cardDetails;
    }

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    @JsonProperty("status")
    @JsonSerialize(using = ToLowerCaseStringSerializer.class)
    private PaymentInstrumentStatus paymentInstrumentStatus;

    @Embedded
    private CardDetailsEntity cardDetails;

    public PaymentInstrumentEntity() {
        // For JPA
    }

    public static class PaymentInstrumentEntityBuilder {
        private CardDetailsEntity cardDetails;
        private Instant createdDate;
        private PaymentInstrumentStatus status;
        private Instant startDate;
        private Map<String, String> recurringAuthToken;

        public static PaymentInstrumentEntity.PaymentInstrumentEntityBuilder aPaymentInstrumentEntity(Instant createdDate) {
            var paymentInstrumentEntityBuilder = new PaymentInstrumentEntityBuilder();
            paymentInstrumentEntityBuilder.withCreatedDate(createdDate);
            return paymentInstrumentEntityBuilder;
        }

        public PaymentInstrumentEntity.PaymentInstrumentEntityBuilder withCardDetails(CardDetailsEntity cardDetails) {
            this.cardDetails = cardDetails;
            return this;
        }

        public PaymentInstrumentEntity.PaymentInstrumentEntityBuilder withCreatedDate(Instant createdDate) {
            this.createdDate = createdDate;
            return this;
        }        
        
        public PaymentInstrumentEntity.PaymentInstrumentEntityBuilder withStartDate(Instant startDate) {
            this.startDate = startDate;
            return this;
        }        
        
        public PaymentInstrumentEntity.PaymentInstrumentEntityBuilder withStatus(PaymentInstrumentStatus status) {
            this.status = status;
            return this;
        }        
        
        public PaymentInstrumentEntity.PaymentInstrumentEntityBuilder withRecurringAuthToken(Map<String, String> recurringAuthToken) {
            this.recurringAuthToken = recurringAuthToken;
            return this;
        }
        

        public PaymentInstrumentEntity build() {
            return new PaymentInstrumentEntity(createdDate, recurringAuthToken, startDate, cardDetails, status);
        }
    }

}
