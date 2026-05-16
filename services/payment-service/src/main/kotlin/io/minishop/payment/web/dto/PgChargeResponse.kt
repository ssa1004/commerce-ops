package io.minishop.payment.web.dto

@JvmRecord
data class PgChargeResponse(
    val success: Boolean,
    val reference: String?,
    val reason: String?,
) {
    companion object {
        @JvmStatic
        fun ok(reference: String): PgChargeResponse = PgChargeResponse(true, reference, null)

        @JvmStatic
        fun fail(reason: String): PgChargeResponse = PgChargeResponse(false, null, reason)
    }
}
