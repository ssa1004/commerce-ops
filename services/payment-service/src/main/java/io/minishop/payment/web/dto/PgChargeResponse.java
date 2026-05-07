package io.minishop.payment.web.dto;

public record PgChargeResponse(
        boolean success,
        String reference,
        String reason
) {
    public static PgChargeResponse ok(String reference) {
        return new PgChargeResponse(true, reference, null);
    }

    public static PgChargeResponse fail(String reason) {
        return new PgChargeResponse(false, null, reason);
    }
}
