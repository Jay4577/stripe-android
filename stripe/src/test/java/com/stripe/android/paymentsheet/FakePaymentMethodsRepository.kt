package com.stripe.android.paymentsheet

import com.stripe.android.model.PaymentMethod
import com.stripe.android.model.PaymentMethodCreateParams
import com.stripe.android.paymentsheet.repositories.PaymentMethodsRepository

class FakePaymentMethodsRepository(
    private val paymentMethods: List<PaymentMethod>
) : PaymentMethodsRepository {
    lateinit var savedPaymentMethod: PaymentMethod
    var error: Throwable? = null
    override suspend fun get(
        customerConfig: PaymentSheet.CustomerConfiguration,
        type: PaymentMethod.Type
    ): List<PaymentMethod> = paymentMethods

    override suspend fun save(
        customerConfig: PaymentSheet.CustomerConfiguration,
        paymentMethodCreateParams: PaymentMethodCreateParams
    ): PaymentMethod {
        return error?.let {
            throw it
        } ?: savedPaymentMethod
    }
}
