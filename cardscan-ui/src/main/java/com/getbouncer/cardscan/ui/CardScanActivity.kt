package com.getbouncer.cardscan.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import com.getbouncer.cardscan.ui.result.MainLoopAggregator
import com.getbouncer.cardscan.ui.result.MainLoopState
import com.getbouncer.scan.framework.AggregateResultListener
import com.getbouncer.scan.framework.AnalyzerLoopErrorListener
import com.getbouncer.scan.framework.Config
import com.getbouncer.scan.framework.time.Clock
import com.getbouncer.scan.framework.time.seconds
import com.getbouncer.scan.payment.card.formatPan
import com.getbouncer.scan.payment.card.getCardIssuer
import com.getbouncer.scan.payment.card.isValidPan
import com.getbouncer.scan.payment.ml.SSDOcr
import com.getbouncer.scan.payment.ml.ssd.DetectionBox
import com.getbouncer.scan.payment.ml.ssd.calculateCardFinderCoordinatesFromObjectDetection
import com.getbouncer.scan.ui.DebugDetectionBox
import com.getbouncer.scan.ui.SimpleScanActivity
import com.getbouncer.scan.ui.util.asRect
import com.getbouncer.scan.ui.util.fadeIn
import com.getbouncer.scan.ui.util.fadeOut
import com.getbouncer.scan.ui.util.getColorByRes
import com.getbouncer.scan.ui.util.hide
import com.getbouncer.scan.ui.util.setTextSize
import com.getbouncer.scan.ui.util.show
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private val MINIMUM_RESOLUTION = Size(1280, 720) // minimum size of an object square

fun DetectionBox.forDebugPan() = DebugDetectionBox(rect, confidence, label.toString())
fun DetectionBox.forDebugObjDetect(cardFinder: Rect, previewImage: Size) = DebugDetectionBox(
    calculateCardFinderCoordinatesFromObjectDetection(rect, previewImage, cardFinder),
    confidence,
    label.toString()
)

open class CardScanActivity :
    SimpleScanActivity(),
    AggregateResultListener<MainLoopAggregator.InterimResult, MainLoopAggregator.FinalResult>,
    AnalyzerLoopErrorListener {

    /**
     * The text view that informs the user what to do.
     */
    protected open val enterCardManuallyTextView: TextView by lazy { TextView(this) }

    private val enableEnterCardManually: Boolean by lazy {
        intent.getBooleanExtra(PARAM_ENABLE_ENTER_MANUALLY, false)
    }

    private val displayCardPan: Boolean by lazy {
        intent.getBooleanExtra(PARAM_DISPLAY_CARD_PAN, true)
    }

    private val displayCardholderName: Boolean by lazy {
        intent.getBooleanExtra(PARAM_DISPLAY_CARDHOLDER_NAME, false)
    }

    override val displayCardScanLogo: Boolean by lazy {
        intent.getBooleanExtra(PARAM_DISPLAY_CARD_SCAN_LOGO, true)
    }

    private val enableNameExtraction: Boolean by lazy {
        intent.getBooleanExtra(PARAM_ENABLE_NAME_EXTRACTION, false)
    }

    private val enableExpiryExtraction: Boolean by lazy {
        intent.getBooleanExtra(PARAM_ENABLE_EXPIRY_EXTRACTION, false)
    }

    private var mainLoopIsProducingResults = AtomicBoolean(false)
    private val hasPreviousValidResult = AtomicBoolean(false)
    private var lastDebugFrameUpdate = Clock.markNow()

    private val cardScanFlow: CardScanFlow by lazy {
        CardScanFlow(enableNameExtraction, enableExpiryExtraction, this, this)
    }

    override val minimumAnalysisResolution: Size = MINIMUM_RESOLUTION

    /**
     * During on create
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!CardScanFlow.attemptedNameAndExpiryInitialization && (enableExpiryExtraction || enableNameExtraction)) {
            Log.e(
                Config.logTag,
                "Attempting to run name and expiry without initializing text detector. " +
                    "Please invoke the warmup() function with initializeNameAndExpiryExtraction to true."
            )
            cardScanFlow.cancelFlow()
            showNameAndExpiryInitializationError()
        }

        enterCardManuallyTextView.setOnClickListener { enterCardManually() }
    }

    override fun addUiComponents() {
        super.addUiComponents()

        listOf(
            enterCardManuallyTextView,
        ).forEach {
            it.id = View.generateViewId()
            layout.addView(it)
        }
    }

    override fun setupUiComponents() {
        super.setupUiComponents()

        enterCardManuallyTextView.text = resources.getString(R.string.bouncer_enter_card_manually)
        enterCardManuallyTextView.setTextSize(R.dimen.bouncerEnterCardManuallyTextSize)
        enterCardManuallyTextView.gravity = Gravity.CENTER

        if (enableEnterCardManually) {
            enterCardManuallyTextView.show()
        } else {
            enterCardManuallyTextView.hide()
        }

        if (isBackgroundDark()) {
            enterCardManuallyTextView.setTextColor(getColorByRes(R.color.bouncerEnterCardManuallyColorDark))
        } else {
            enterCardManuallyTextView.setTextColor(getColorByRes(R.color.bouncerEnterCardManuallyColorLight))
        }
    }

    override fun setupUiConstraints() {
        super.setupUiConstraints()

        enterCardManuallyTextView.layoutParams = ConstraintLayout.LayoutParams(
            0, // width
            ConstraintLayout.LayoutParams.WRAP_CONTENT, // height
        ).apply {
            marginStart = resources.getDimensionPixelSize(R.dimen.bouncerEnterCardManuallyMargin)
            marginEnd = resources.getDimensionPixelSize(R.dimen.bouncerEnterCardManuallyMargin)
            bottomMargin = resources.getDimensionPixelSize(R.dimen.bouncerEnterCardManuallyMargin)
            topMargin = resources.getDimensionPixelSize(R.dimen.bouncerEnterCardManuallyMargin)
        }

        enterCardManuallyTextView.addConstraints {
            connect(it.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            connect(it.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.END)
            connect(it.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cardScanFlow.cancelFlow()
    }

    /**
     * Cancel scanning to enter a card manually
     */
    private fun enterCardManually() {
        runBlocking { scanStat.trackResult("enter_card_manually") }
        cancelScan(CANCELED_REASON_ENTER_MANUALLY)
    }

    /**
     * Card was successfully scanned, return an activity result.
     */
    private fun cardScanned(result: CardScanActivityResult) {
        runBlocking { scanStat.trackResult("card_scanned") }
        completeScan(Intent().putExtra(RESULT_SCANNED_CARD, result))
    }

    private fun showNameAndExpiryInitializationError() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bouncer_name_and_expiry_initialization_error)
            .setMessage(R.string.bouncer_name_and_expiry_initialization_error_message)
            .setPositiveButton(R.string.bouncer_name_and_expiry_initialization_error_ok) { _, _ -> userCancelScan() }
            .setCancelable(false)
            .show()
    }

    /**
     * Display the card pan. If debug, show the instant pan. if not, show the most likely pan.
     */
    private fun displayPan(instantPan: String?, mostLikelyPan: String?) {
        if (displayCardPan) {
            if (Config.isDebug && instantPan != null) {
                cardNumberTextView.text = formatPan(instantPan)
                cardNumberTextView.show()
            } else if (!mostLikelyPan.isNullOrEmpty() && isValidPan(mostLikelyPan)) {
                cardNumberTextView.text = formatPan(mostLikelyPan)
                cardNumberTextView.fadeIn()
            }
        }
    }

    /**
     * Display the cardholder name. If debug, show the instant name. if not, show the most likely name.
     */
    private fun displayName(instantName: String?, mostLikelyName: String?) {
        if (displayCardholderName) {
            if (Config.isDebug && instantName != null) {
                cardNameTextView.text = instantName
                cardNameTextView.show()
            } else if (!mostLikelyName.isNullOrEmpty()) {
                cardNameTextView.text = mostLikelyName
                cardNameTextView.fadeIn()
            }
        }
    }

    /**
     * A final result was received from the aggregator. Set the result from this activity.
     */
    override suspend fun onResult(result: MainLoopAggregator.FinalResult) = launch(Dispatchers.Main) {
        // Only show the expiry dates that are not expired
        val (expiryMonth, expiryYear) = if (result.expiry?.isValidExpiry() == true) {
            (result.expiry.month to result.expiry.year)
        } else {
            (null to null)
        }

        cardScanned(
            CardScanActivityResult(
                pan = result.pan,
                networkName = getCardIssuer(result.pan).displayName,
                expiryDay = null,
                expiryMonth = expiryMonth,
                expiryYear = expiryYear,
                cvc = null,
                cardholderName = result.name,
                errorString = result.errorString
            )
        )
    }.let { Unit }

    /**
     * An interim result was received from the result aggregator.
     */
    override suspend fun onInterimResult(result: MainLoopAggregator.InterimResult) = launch(Dispatchers.Main) {
        if (!mainLoopIsProducingResults.getAndSet(true)) {
            scanStat.trackResult("first_image_processed")
        }

        if (result.state is MainLoopState.OcrRunning && !hasPreviousValidResult.getAndSet(true)) {
            scanStat.trackResult("ocr_pan_observed")
            enterCardManuallyTextView.fadeOut()
        }

        val willRunNameAndExpiry = (result.analyzerResult.isExpiryExtractionAvailable && enableExpiryExtraction) ||
            (result.analyzerResult.isNameExtractionAvailable && enableNameExtraction)

        when (result.state) {
            is MainLoopState.Initial -> changeScanState(ScanState.NotFound)
            is MainLoopState.OcrRunning -> {
                displayPan(result.analyzerResult.pan, result.state.getMostLikelyPan())
                if (willRunNameAndExpiry) {
                    changeScanState(ScanState.FoundLong)
                } else {
                    changeScanState(ScanState.FoundShort)
                }
            }
            is MainLoopState.NameAndExpiryRunning -> {
                displayName(result.analyzerResult.pan, result.state.getMostLikelyName())
                if (willRunNameAndExpiry) {
                    changeScanState(ScanState.FoundLong)
                } else {
                    changeScanState(ScanState.FoundShort)
                }
            }
            is MainLoopState.Finished -> changeScanState(ScanState.Correct)
        }

        showDebugFrame(result.frame, result.analyzerResult.panDetectionBoxes, result.analyzerResult.objDetectionBoxes)
    }.let { Unit }

    override suspend fun onReset() = launch(Dispatchers.Main) { changeScanState(ScanState.NotFound) }.let { Unit }

    private suspend fun showDebugFrame(
        frame: SSDOcr.Input,
        panBoxes: List<DetectionBox>?,
        objectBoxes: List<DetectionBox>?
    ) {
        if (Config.isDebug && lastDebugFrameUpdate.elapsedSince() > 1.seconds) {
            lastDebugFrameUpdate = Clock.markNow()
            val bitmap = withContext(Dispatchers.Default) { SSDOcr.cropImage(frame) }
            debugImageView.setImageBitmap(bitmap)
            if (panBoxes != null) {
                debugOverlayView.setBoxes(panBoxes.map { it.forDebugPan() })
            }
            if (objectBoxes != null) {
                debugOverlayView.setBoxes(objectBoxes.map { it.forDebugObjDetect(frame.cardFinder, frame.previewSize) })
            }

            Log.d(Config.logTag, "Delay between capture and result for this frame was ${frame.capturedAt.elapsedSince()}")
        }
    }

    override fun onAnalyzerFailure(t: Throwable): Boolean {
        analyzerFailureCancelScan(t)
        return true
    }

    override fun onResultFailure(t: Throwable): Boolean {
        analyzerFailureCancelScan(t)
        return true
    }

    /**
     * Once the camera stream is available, start processing images.
     */
    override fun onCameraStreamAvailable(cameraStream: Flow<Bitmap>) {
        cardScanFlow.startFlow(
            context = this,
            imageStream = cameraStream,
            previewSize = Size(previewFrame.width, previewFrame.height),
            viewFinder = viewFinderWindowView.asRect(),
            lifecycleOwner = this,
            coroutineScope = this
        )
    }

    override fun onInvalidApiKey() {
        cardScanFlow.cancelFlow()
    }
}
