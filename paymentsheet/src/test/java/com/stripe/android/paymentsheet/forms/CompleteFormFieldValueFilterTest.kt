package com.stripe.android.paymentsheet.forms

import com.google.common.truth.Truth.assertThat
import com.stripe.android.paymentsheet.model.PaymentSelection
import com.stripe.android.ui.core.elements.EmailConfig
import com.stripe.android.ui.core.elements.EmailElement
import com.stripe.android.ui.core.elements.IdentifierSpec
import com.stripe.android.ui.core.elements.SectionController
import com.stripe.android.ui.core.elements.SectionElement
import com.stripe.android.ui.core.elements.SimpleTextFieldController
import com.stripe.android.ui.core.forms.FormFieldEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test

@ExperimentalCoroutinesApi
class CompleteFormFieldValueFilterTest {

    private val emailController = SimpleTextFieldController(EmailConfig())
    private var emailSection: SectionElement

    private val hiddenIdentifersFlow = MutableStateFlow<Set<IdentifierSpec>>(emptySet())

    private val fieldFlow = MutableStateFlow(
        mapOf(
            IdentifierSpec.Country to FormFieldEntry("US", true),
            IdentifierSpec.Email to FormFieldEntry("email@email.com", false)
        )
    )

    private val transformElementToFormFieldValueFlow = CompleteFormFieldValueFilter(
        fieldFlow,
        hiddenIdentifersFlow,
        showingMandate = MutableStateFlow(true),
        userRequestedReuse = MutableStateFlow(PaymentSelection.CustomerRequestedSave.NoRequest)
    )

    init {
        runBlocking {
            emailSection = SectionElement(
                identifier = IdentifierSpec.Generic("email_section"),
                EmailElement(
                    IdentifierSpec.Email,
                    controller = emailController
                ),
                SectionController(emailController.label.first(), listOf(emailController))
            )
        }
    }

    @Test
    fun `With only some complete controllers and no hidden values the flow value is null`() {
        runTest {
            assertThat(transformElementToFormFieldValueFlow.filterFlow().first()).isNull()
        }
    }

    @Test
    fun `If all controllers are complete and no hidden values the flow value has all values`() {
        runTest {
            fieldFlow.value =
                mapOf(
                    IdentifierSpec.Country to FormFieldEntry("US", true),
                    IdentifierSpec.Email to FormFieldEntry("email@email.com", true)
                )

            val formFieldValue = transformElementToFormFieldValueFlow.filterFlow().first()

            assertThat(formFieldValue).isNotNull()
            assertThat(formFieldValue?.fieldValuePairs)
                .containsKey(IdentifierSpec.Email)
            assertThat(formFieldValue?.fieldValuePairs)
                .containsKey(IdentifierSpec.Country)
        }
    }

    @Test
    fun `If an hidden field is incomplete field pairs have the non-hidden values`() {
        runTest {
            hiddenIdentifersFlow.value = setOf(IdentifierSpec.Email)

            val formFieldValues = transformElementToFormFieldValueFlow.filterFlow()

            val formFieldValue = formFieldValues.first()
            assertThat(formFieldValue).isNotNull()
            assertThat(formFieldValue?.fieldValuePairs)
                .doesNotContainKey(IdentifierSpec.Email)
            assertThat(formFieldValue?.fieldValuePairs)
                .containsKey(IdentifierSpec.Country)
        }
    }

    @Test
    fun `If an hidden field is complete field pairs contain only the non-hidden values`() {
        runTest {
            fieldFlow.value =
                mapOf(
                    IdentifierSpec.Country to FormFieldEntry("US", true),
                    IdentifierSpec.Email to FormFieldEntry("email@email.com", true)
                )

            hiddenIdentifersFlow.value = setOf(emailSection.fields[0].identifier)

            val formFieldValue = transformElementToFormFieldValueFlow.filterFlow().first()

            assertThat(formFieldValue).isNotNull()
            assertThat(formFieldValue?.fieldValuePairs)
                .doesNotContainKey(IdentifierSpec.Email)
            assertThat(formFieldValue?.fieldValuePairs)
                .containsKey(IdentifierSpec.Country)
        }
    }
}
