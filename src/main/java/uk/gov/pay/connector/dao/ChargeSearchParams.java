package uk.gov.pay.connector.dao;

import org.apache.commons.lang3.StringUtils;
import uk.gov.pay.connector.model.api.ExternalChargeState;
import uk.gov.pay.connector.model.domain.ChargeStatus;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class ChargeSearchParams {

    private Long gatewayAccountId;
    private String reference;
    private ZonedDateTime fromDate;
    private ZonedDateTime toDate;
    private Long page;
    private Long displaySize;
    private List<ChargeStatus> chargeStatuses = new LinkedList<>();
    private String externalChargeState;

    public Long getGatewayAccountId() {
        return gatewayAccountId;
    }

    public ChargeSearchParams withGatewayAccountId(Long gatewayAccountId) {
        this.gatewayAccountId = gatewayAccountId;
        return this;
    }

    public List<ChargeStatus> getChargeStatuses() {
        return chargeStatuses;
    }


    public ChargeSearchParams withExternalChargeState(String state) {
        if (state != null) {
            this.externalChargeState = state;
            for (ExternalChargeState externalState : parseState(state)) {
                this.chargeStatuses.addAll(ChargeStatus.fromExternal(externalState));
            }
        }
        return this;
    }

    public String getReference() {
        return reference;
    }

    public ChargeSearchParams withReferenceLike(String reference) {
        this.reference = reference;
        return this;
    }

    public ZonedDateTime getFromDate() {
        return fromDate;
    }

    public ChargeSearchParams withFromDate(ZonedDateTime fromDate) {
        this.fromDate = fromDate;
        return this;
    }

    public ZonedDateTime getToDate() {
        return toDate;
    }

    public ChargeSearchParams withToDate(ZonedDateTime toDate) {
        this.toDate = toDate;
        return this;
    }

    public Long getPage() {
        return page;
    }

    public ChargeSearchParams withPage(Long page) {
        this.page = (page != null && page >= 1) ? page : 1; // always show first page if its an invalid page request
        return this;
    }

    public Long getDisplaySize() {
        return displaySize;
    }

    public ChargeSearchParams withDisplaySize(Long displaySize) {
        this.displaySize = displaySize;
        return this;
    }

    public ChargeSearchParams withInternalChargeStatuses(List<ChargeStatus> statuses) {
        this.chargeStatuses = statuses;
        return this;
    }

    public String buildQueryParams() {
        StringBuilder builder = new StringBuilder();

        if (StringUtils.isNotBlank(reference))
            builder.append("&reference=" + reference);
        if (fromDate != null)
            builder.append("&from_date=" + fromDate);
        if (toDate != null)
            builder.append("&to_date=" + toDate);
        if (page != null)
            builder.append("&page=" + page);
        if (displaySize != null)
            builder.append("&display_size=" + displaySize);
        if (StringUtils.isNotBlank(externalChargeState)) {
            builder.append("&state=" + externalChargeState);
        }
        return builder.toString();
    }

    private List<ExternalChargeState> parseState(String state) {
        List<ExternalChargeState> externalStates = null;
        if (isNotBlank(state)) {
            externalStates = ExternalChargeState.fromStatusString(state);
        }
        return externalStates;
    }

}
