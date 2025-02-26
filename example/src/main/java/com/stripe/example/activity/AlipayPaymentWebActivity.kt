package com.stripe.example.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Observer
import com.stripe.android.ApiResultCallback
import com.stripe.android.PaymentConfiguration
import com.stripe.android.PaymentIntentResult
import com.stripe.android.Stripe
import com.stripe.android.model.ConfirmPaymentIntentParams
import com.stripe.android.model.MandateDataParams
import com.stripe.android.model.PaymentMethodCreateParams
import com.stripe.android.model.StripeIntent
import com.stripe.example.R
import com.stripe.example.databinding.PaymentExampleActivityBinding
import org.json.JSONObject

class AlipayPaymentWebActivity : StripeIntentActivity() {

    private val viewBinding: PaymentExampleActivityBinding by lazy {
        PaymentExampleActivityBinding.inflate(layoutInflater)
    }

    private val stripe: Stripe by lazy {
        Stripe(
            applicationContext,
            PaymentConfiguration.getInstance(applicationContext).publishableKey
        )
    }

    private var clientSecret: String? = null
    private var confirmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        viewBinding.confirmWithPaymentButton.text =
            resources.getString(R.string.confirm_alipay_button)
        viewBinding.paymentExampleIntro.text =
            resources.getString(R.string.alipay_example_intro)

        viewModel.inProgress.observe(this) { enableUi(!it) }
        viewModel.status.observe(this, Observer(viewBinding.status::setText))

        viewBinding.confirmWithPaymentButton.setOnClickListener {
            clientSecret?.let {
                // If we already loaded the Payment Intent and haven't confirmed, try again
                if (!confirmed) {
                    updateStatus("\n\nPayment Intent already created, trying to confirm")
                    confirmPayment(it)
                }
            } ?: run {
                createAndConfirmPaymentIntent(
                    country = "US",
                    paymentMethodCreateParams = PaymentMethodCreateParams.createAlipay(),
                    supportedPaymentMethods = "alipay"
                )
            }
        }
    }

    override fun handleCreatePaymentIntentResponse(
        responseData: JSONObject,
        params: PaymentMethodCreateParams?,
        shippingDetails: ConfirmPaymentIntentParams.Shipping?,
        stripeAccountId: String?,
        existingPaymentMethodId: String?,
        mandateDataParams: MandateDataParams?,
        onPaymentIntentCreated: (String) -> Unit
    ) {
        viewModel.status.value +=
            "\n\nStarting PaymentIntent confirmation" +
            (
                stripeAccountId?.let {
                    " for $it"
                } ?: ""
                )

        clientSecret = responseData.getString("secret").also {
            confirmPayment(it)
        }
    }

    private fun confirmPayment(clientSecret: String) {
        stripe.confirmPayment(this, ConfirmPaymentIntentParams.createAlipay(clientSecret))
    }

    private fun updateStatus(appendMessage: String) {
        viewModel.status.value += appendMessage
        viewModel.inProgress.postValue(false)
    }

    private fun enableUi(enable: Boolean) {
        viewBinding.progressBar.visibility = if (enable) View.INVISIBLE else View.VISIBLE
        viewBinding.confirmWithPaymentButton.isEnabled = enable
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Handle the result of stripe.confirmPayment
        stripe.onPaymentResult(
            requestCode,
            data,
            object : ApiResultCallback<PaymentIntentResult> {
                override fun onSuccess(result: PaymentIntentResult) {
                    val paymentIntent = result.intent
                    val status = paymentIntent.status
                    when (status) {
                        StripeIntent.Status.Succeeded ->
                            updateStatus("\n\nPayment succeeded")
                        StripeIntent.Status.RequiresAction ->
                            updateStatus("\n\nUser canceled confirmation")
                        else ->
                            updateStatus(
                                "\n\nPayment failed or canceled." +
                                    "\nStatus: ${paymentIntent.status}"
                            )
                    }
                }

                override fun onError(e: Exception) {
                    updateStatus("\n\nError: ${e.message}")
                }
            }
        )
    }
}
