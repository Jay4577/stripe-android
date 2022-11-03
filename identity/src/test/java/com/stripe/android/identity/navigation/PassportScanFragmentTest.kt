package com.stripe.android.identity.navigation

import android.content.Context
import android.view.View
import android.widget.Button
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.lifecycle.MediatorLiveData
import androidx.navigation.Navigation
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.common.truth.Truth.assertThat
import com.stripe.android.core.model.StripeFile
import com.stripe.android.identity.R
import com.stripe.android.identity.SUCCESS_VERIFICATION_PAGE_NOT_REQUIRE_LIVE_CAPTURE
import com.stripe.android.identity.VERIFICATION_PAGE_DATA_NOT_MISSING_BACK
import com.stripe.android.identity.analytics.IdentityAnalyticsRequestFactory
import com.stripe.android.identity.analytics.IdentityAnalyticsRequestFactory.Companion.EVENT_SCREEN_PRESENTED
import com.stripe.android.identity.analytics.IdentityAnalyticsRequestFactory.Companion.PARAM_EVENT_META_DATA
import com.stripe.android.identity.analytics.IdentityAnalyticsRequestFactory.Companion.PARAM_SCAN_TYPE
import com.stripe.android.identity.analytics.IdentityAnalyticsRequestFactory.Companion.PARAM_SCREEN_NAME
import com.stripe.android.identity.analytics.IdentityAnalyticsRequestFactory.Companion.PASSPORT
import com.stripe.android.identity.analytics.IdentityAnalyticsRequestFactory.Companion.SCREEN_NAME_LIVE_CAPTURE_PASSPORT
import com.stripe.android.identity.analytics.ScreenTracker
import com.stripe.android.identity.camera.IdentityAggregator
import com.stripe.android.identity.camera.IdentityScanFlow
import com.stripe.android.identity.databinding.IdentityDocumentScanFragmentBinding
import com.stripe.android.identity.networking.Resource
import com.stripe.android.identity.networking.SingleSideDocumentUploadState
import com.stripe.android.identity.networking.UploadedResult
import com.stripe.android.identity.networking.models.CollectedDataParam
import com.stripe.android.identity.networking.models.DocumentUploadParam
import com.stripe.android.identity.networking.models.VerificationPage
import com.stripe.android.identity.states.IdentityScanState
import com.stripe.android.identity.utils.SingleLiveEvent
import com.stripe.android.identity.viewModelFactoryFor
import com.stripe.android.identity.viewmodel.IdentityScanViewModel
import com.stripe.android.identity.viewmodel.IdentityViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.same
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class PassportScanFragmentTest {
    @get:Rule
    var rule: TestRule = InstantTaskExecutorRule()

    private val finalResultLiveData = SingleLiveEvent<IdentityAggregator.FinalResult>()
    private val displayStateChanged = SingleLiveEvent<Pair<IdentityScanState, IdentityScanState?>>()
    private val testDispatcher = UnconfinedTestDispatcher()

    private val mockScanFlow = mock<IdentityScanFlow>()
    private val mockIdentityScanViewModel = mock<IdentityScanViewModel>().also {
        whenever(it.identityScanFlow).thenReturn(mockScanFlow)
        whenever(it.finalResult).thenReturn(finalResultLiveData)
        whenever(it.interimResults).thenReturn(mock())
        whenever(it.displayStateChanged).thenReturn(displayStateChanged)
    }

    private val mockPageAndModel = MediatorLiveData<Resource<IdentityViewModel.PageAndModelFiles>>()

    private val documentUploadState =
        MutableStateFlow(SingleSideDocumentUploadState())

    private val mockScreenTracker = mock<ScreenTracker>()

    private val mockIdentityViewModel = mock<IdentityViewModel> {
        on { pageAndModelFiles } doReturn mockPageAndModel
        on { documentFrontUploadedState } doReturn documentUploadState
        on { identityAnalyticsRequestFactory } doReturn
            IdentityAnalyticsRequestFactory(
                context = ApplicationProvider.getApplicationContext(),
                args = mock()
            )
        on { fpsTracker } doReturn mock()
        on { screenTracker } doReturn mockScreenTracker
        on { uiContext } doReturn testDispatcher
        on { workContext } doReturn testDispatcher
    }

    private val frontUploadedState = SingleSideDocumentUploadState(
        highResResult = Resource.success(FRONT_HIGH_RES_RESULT),
        lowResResult = Resource.success(FRONT_LOW_RES_RESULT)
    )

    private val verificationPageRequireSelfie = mock<VerificationPage> {
        on { selfieCapture } doReturn mock()
    }

    @Before
    fun simulateModelDownloaded() {
        mockPageAndModel.postValue(
            Resource.success(
                IdentityViewModel.PageAndModelFiles(
                    SUCCESS_VERIFICATION_PAGE_NOT_REQUIRE_LIVE_CAPTURE,
                    mock(),
                    mock()
                )
            )
        )
    }

    @Test
    fun `when started analytics event is sent`() {
        launchPassportScanFragment().onFragment {
            runBlocking {
                mockScreenTracker.screenTransitionFinish(eq(SCREEN_NAME_LIVE_CAPTURE_PASSPORT))
            }
            verify(mockIdentityViewModel).sendAnalyticsRequest(
                argThat {
                    eventName == EVENT_SCREEN_PRESENTED &&
                        (params[PARAM_EVENT_META_DATA] as Map<*, *>)[PARAM_SCREEN_NAME] == SCREEN_NAME_LIVE_CAPTURE_PASSPORT &&
                        (params[PARAM_EVENT_META_DATA] as Map<*, *>)[PARAM_SCAN_TYPE] == PASSPORT
                }
            )
        }
    }

    @Test
    fun `front scanned and uploaded - not require selfie - submit`() {
        launchPassportScanFragment().onFragment { passportScanFragment ->
            runBlocking {
                val navController = TestNavHostController(
                    ApplicationProvider.getApplicationContext()
                )
                navController.setGraph(
                    R.navigation.identity_nav_graph
                )
                navController.setCurrentDestination(R.id.passportScanFragment)
                Navigation.setViewNavController(
                    passportScanFragment.requireView(),
                    navController
                )
                // start scan
                // mock success of scan
                val mockFrontFinalResult = mock<IdentityAggregator.FinalResult>().also {
                    whenever(it.identityState).thenReturn(mock<IdentityScanState.Finished>())
                }
                finalResultLiveData.postValue(mockFrontFinalResult)

                val successCaptor = argumentCaptor<(VerificationPage) -> Unit>()
                verify(mockIdentityViewModel).observeForVerificationPage(
                    any(),
                    successCaptor.capture(),
                    any()
                )

                val mockVerificationPage = mock<VerificationPage>()
                whenever(mockIdentityScanViewModel.targetScanType).thenReturn(IdentityScanState.ScanType.PASSPORT)
                successCaptor.lastValue.invoke(mockVerificationPage)
                verify(mockIdentityViewModel).uploadScanResult(
                    same(mockFrontFinalResult),
                    same(mockVerificationPage),
                    eq(IdentityScanState.ScanType.PASSPORT)
                )

                // click continue, trigger navigation
                val binding =
                    IdentityDocumentScanFragmentBinding.bind(passportScanFragment.requireView())
                binding.kontinue.findViewById<Button>(R.id.button).callOnClick()

                // continue button is triggered to loading
                assertThat(binding.kontinue.findViewById<Button>(R.id.button).isEnabled).isFalse()
                assertThat(binding.kontinue.findViewById<CircularProgressIndicator>(R.id.indicator).visibility).isEqualTo(
                    View.VISIBLE
                )

                documentUploadState.update { frontUploadedState }

                // post returns valid result
                whenever(mockIdentityViewModel.postVerificationPageData(any())).thenReturn(
                    VERIFICATION_PAGE_DATA_NOT_MISSING_BACK
                )

                // observeForVerificationPage - trigger onSuccess
                verify(mockIdentityViewModel, times(2)).observeForVerificationPage(
                    any(),
                    successCaptor.capture(),
                    any()
                )
                successCaptor.lastValue.invoke(mock())

                // verify post request
                verify(mockIdentityViewModel).postVerificationPageData(
                    eq(
                        CollectedDataParam.createFromFrontUploadedResultsForAutoCapture(
                            type = CollectedDataParam.Type.PASSPORT,
                            frontHighResResult = FRONT_HIGH_RES_RESULT,
                            frontLowResResult = FRONT_LOW_RES_RESULT
                        )
                    )
                )

                verify(mockIdentityViewModel).postVerificationPageSubmit()
            }
        }
    }

    @Test
    fun `front scanned and uploaded - require selfie - to selfie`() {
        launchPassportScanFragment().onFragment { passportScanFragment ->
            runBlocking {
                val navController = TestNavHostController(
                    ApplicationProvider.getApplicationContext()
                )
                navController.setGraph(
                    R.navigation.identity_nav_graph
                )
                navController.setCurrentDestination(R.id.passportScanFragment)
                Navigation.setViewNavController(
                    passportScanFragment.requireView(),
                    navController
                )
                // start scan
                // mock success of scan
                val mockFrontFinalResult = mock<IdentityAggregator.FinalResult>().also {
                    whenever(it.identityState).thenReturn(mock<IdentityScanState.Finished>())
                }
                finalResultLiveData.postValue(mockFrontFinalResult)

                val successCaptor = argumentCaptor<(VerificationPage) -> Unit>()
                verify(mockIdentityViewModel).observeForVerificationPage(
                    any(),
                    successCaptor.capture(),
                    any()
                )

                val mockVerificationPage = mock<VerificationPage>()
                whenever(mockIdentityScanViewModel.targetScanType).thenReturn(IdentityScanState.ScanType.PASSPORT)
                successCaptor.lastValue.invoke(mockVerificationPage)
                verify(mockIdentityViewModel).uploadScanResult(
                    same(mockFrontFinalResult),
                    same(mockVerificationPage),
                    eq(IdentityScanState.ScanType.PASSPORT)
                )

                // click continue, trigger navigation
                val binding =
                    IdentityDocumentScanFragmentBinding.bind(passportScanFragment.requireView())
                binding.kontinue.findViewById<Button>(R.id.button).callOnClick()

                // continue button is triggered to loading
                assertThat(binding.kontinue.findViewById<Button>(R.id.button).isEnabled).isFalse()
                assertThat(binding.kontinue.findViewById<CircularProgressIndicator>(R.id.indicator).visibility).isEqualTo(
                    View.VISIBLE
                )

                documentUploadState.update { frontUploadedState }

                // post returns valid result
                whenever(mockIdentityViewModel.postVerificationPageData(any())).thenReturn(
                    VERIFICATION_PAGE_DATA_NOT_MISSING_BACK
                )

                // observeForVerificationPage - trigger onSuccess
                verify(mockIdentityViewModel, times(2)).observeForVerificationPage(
                    any(),
                    successCaptor.capture(),
                    any()
                )
                successCaptor.lastValue.invoke(verificationPageRequireSelfie)

                // verify post request
                verify(mockIdentityViewModel).postVerificationPageData(
                    eq(
                        CollectedDataParam.createFromFrontUploadedResultsForAutoCapture(
                            type = CollectedDataParam.Type.PASSPORT,
                            frontHighResResult = FRONT_HIGH_RES_RESULT,
                            frontLowResResult = FRONT_LOW_RES_RESULT
                        )
                    )
                )

                assertThat(navController.currentDestination?.id).isEqualTo(R.id.selfieFragment)
            }
        }
    }

    @Test
    fun `when final result is received scanFlow is reset and cameraAdapter is unbound`() {
        launchPassportScanFragment().onFragment { passportScanFragment ->
            assertThat(passportScanFragment.cameraAdapter.isBoundToLifecycle()).isTrue()

            finalResultLiveData.postValue(
                mock<IdentityAggregator.FinalResult>().also {
                    whenever(it.identityState).thenReturn(mock<IdentityScanState.Finished>())
                }
            )

            verify(mockScanFlow).resetFlow()
            assertThat(passportScanFragment.cameraAdapter.isBoundToLifecycle()).isFalse()
        }
    }

    @Test
    fun `when displayStateChanged to Initial UI is properly updated`() {
        postDisplayStateChangedDataAndVerifyUI(mock<IdentityScanState.Initial>()) { binding, context ->
            assertThat(binding.cameraView.viewFinderBackgroundView.visibility).isEqualTo(View.VISIBLE)
            assertThat(binding.cameraView.viewFinderWindowView.visibility).isEqualTo(View.VISIBLE)
            assertThat(binding.cameraView.viewFinderBorderView.visibility).isEqualTo(View.VISIBLE)
            assertThat(binding.checkMarkView.visibility).isEqualTo(View.GONE)
            assertThat(binding.kontinue.findViewById<Button>(R.id.button).isEnabled).isFalse()
            assertThat(binding.headerTitle.text).isEqualTo(
                context.getText(R.string.passport)
            )
            assertThat(binding.message.text).isEqualTo(
                context.getText(R.string.position_passport)
            )
        }
    }

    @Test
    fun `when displayStateChanged to Found UI is properly updated`() {
        postDisplayStateChangedDataAndVerifyUI(mock<IdentityScanState.Found>()) { binding, context ->
            assertThat(binding.cameraView.viewFinderBackgroundView.visibility).isEqualTo(View.VISIBLE)
            assertThat(binding.cameraView.viewFinderWindowView.visibility).isEqualTo(View.VISIBLE)
            assertThat(binding.cameraView.viewFinderBorderView.visibility).isEqualTo(View.VISIBLE)
            assertThat(binding.checkMarkView.visibility).isEqualTo(View.GONE)
            assertThat(binding.kontinue.findViewById<Button>(R.id.button).isEnabled).isFalse()
            assertThat(binding.message.text).isEqualTo(
                context.getText(R.string.hold_still)
            )
        }
    }

    @Test
    fun `when displayStateChanged to Unsatisfied UI is properly updated`() {
        postDisplayStateChangedDataAndVerifyUI(mock<IdentityScanState.Unsatisfied>()) { binding, context ->
            assertThat(binding.cameraView.viewFinderBackgroundView.visibility).isEqualTo(View.VISIBLE)
            assertThat(binding.cameraView.viewFinderWindowView.visibility).isEqualTo(View.VISIBLE)
            assertThat(binding.cameraView.viewFinderBorderView.visibility).isEqualTo(View.VISIBLE)
            assertThat(binding.checkMarkView.visibility).isEqualTo(View.GONE)
            assertThat(binding.kontinue.findViewById<Button>(R.id.button).isEnabled).isFalse()
            assertThat(binding.message.text).isEqualTo(
                context.getText(R.string.position_passport)
            )
        }
    }

    @Test
    fun `when displayStateChanged to Satisfied UI is properly updated`() {
        postDisplayStateChangedDataAndVerifyUI(mock<IdentityScanState.Satisfied>()) { binding, context ->
            assertThat(binding.cameraView.viewFinderBackgroundView.visibility).isEqualTo(View.VISIBLE)
            assertThat(binding.cameraView.viewFinderWindowView.visibility).isEqualTo(View.VISIBLE)
            assertThat(binding.cameraView.viewFinderBorderView.visibility).isEqualTo(View.VISIBLE)
            assertThat(binding.checkMarkView.visibility).isEqualTo(View.GONE)
            assertThat(binding.kontinue.findViewById<Button>(R.id.button).isEnabled).isFalse()
            assertThat(binding.message.text).isEqualTo(
                context.getText(R.string.scanned)
            )
        }
    }

    @Test
    fun `when displayStateChanged to Finished UI is properly updated`() {
        postDisplayStateChangedDataAndVerifyUI(mock<IdentityScanState.Finished>()) { binding, context ->
            assertThat(binding.cameraView.viewFinderBackgroundView.visibility).isEqualTo(View.INVISIBLE)
            assertThat(binding.cameraView.viewFinderWindowView.visibility).isEqualTo(View.INVISIBLE)
            assertThat(binding.cameraView.viewFinderBorderView.visibility).isEqualTo(View.INVISIBLE)
            assertThat(binding.checkMarkView.visibility).isEqualTo(View.VISIBLE)
            assertThat(binding.kontinue.findViewById<Button>(R.id.button).isEnabled).isTrue()
            assertThat(binding.message.text).isEqualTo(
                context.getText(R.string.scanned)
            )
        }
    }

    private fun launchPassportScanFragment() = launchFragmentInContainer(
        themeResId = R.style.Theme_MaterialComponents
    ) {
        PassportScanFragment(
            viewModelFactoryFor(mockIdentityScanViewModel),
            viewModelFactoryFor(mockIdentityViewModel)
        )
    }

    private fun postDisplayStateChangedDataAndVerifyUI(
        newScanState: IdentityScanState,
        check: (binding: IdentityDocumentScanFragmentBinding, context: Context) -> Unit
    ) {
        launchPassportScanFragment().onFragment {
            displayStateChanged.postValue((newScanState to mock()))
            check(IdentityDocumentScanFragmentBinding.bind(it.requireView()), it.requireContext())
        }
    }

    private companion object {
        val FRONT_HIGH_RES_RESULT = UploadedResult(
            uploadedStripeFile = StripeFile(
                id = "frontHighResResult"
            ),
            scores = listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f),
            uploadMethod = DocumentUploadParam.UploadMethod.AUTOCAPTURE
        )
        val FRONT_LOW_RES_RESULT = UploadedResult(
            uploadedStripeFile = StripeFile(
                id = "frontLowResResult"
            ),
            scores = listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f),
            uploadMethod = DocumentUploadParam.UploadMethod.AUTOCAPTURE
        )
    }
}
