package com.getbouncer.cardscan.ui

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.util.Size
import androidx.lifecycle.LifecycleOwner
import com.getbouncer.cardscan.ui.analyzer.CompletionLoopAnalyzer
import com.getbouncer.cardscan.ui.analyzer.MainLoopAnalyzer
import com.getbouncer.cardscan.ui.result.CompletionLoopAggregator
import com.getbouncer.cardscan.ui.result.CompletionLoopListener
import com.getbouncer.cardscan.ui.result.CompletionLoopResult
import com.getbouncer.cardscan.ui.result.MainLoopAggregator
import com.getbouncer.cardscan.ui.result.MainLoopState
import com.getbouncer.scan.framework.AggregateResultListener
import com.getbouncer.scan.framework.AnalyzerLoopErrorListener
import com.getbouncer.scan.framework.AnalyzerPool
import com.getbouncer.scan.framework.Config
import com.getbouncer.scan.framework.FiniteAnalyzerLoop
import com.getbouncer.scan.framework.ProcessBoundAnalyzerLoop
import com.getbouncer.scan.framework.TrackedImage
import com.getbouncer.scan.framework.time.Duration
import com.getbouncer.scan.framework.time.Rate
import com.getbouncer.scan.framework.util.cacheFirstResultSuspend
import com.getbouncer.scan.payment.FrameDetails
import com.getbouncer.scan.payment.analyzer.NameAndExpiryAnalyzer
import com.getbouncer.scan.payment.ml.AlphabetDetect
import com.getbouncer.scan.payment.ml.CardDetect
import com.getbouncer.scan.payment.ml.ExpiryDetect
import com.getbouncer.scan.payment.ml.SSDOcr
import com.getbouncer.scan.payment.ml.TextDetect
import com.getbouncer.scan.ui.ScanFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class SavedFrame(
    val pan: String?,
    val frame: SSDOcr.Input,
    val details: FrameDetails,
)

data class SavedFrameType(
    val hasCard: Boolean,
    val hasPan: Boolean,
)

/**
 * This class contains the logic required for analyzing a credit card for scanning.
 */
open class CardScanFlow(
    private val enableNameExtraction: Boolean,
    private val enableExpiryExtraction: Boolean,
    private val scanResultListener: AggregateResultListener<MainLoopAggregator.InterimResult, MainLoopAggregator.FinalResult>,
    private val scanErrorListener: AnalyzerLoopErrorListener,
    private val completionResultListener: CompletionLoopListener,
) : ScanFlow {
    companion object {
        private const val MAX_COMPLETION_LOOP_FRAMES_FAST_DEVICE = 8
        private const val MAX_COMPLETION_LOOP_FRAMES_SLOW_DEVICE = 5

        /**
         * This field represents whether the flow was initialized with name and expiry enabled.
         */
        var attemptedNameAndExpiryInitialization = false
            private set

        private val getSsdOcrModel = cacheFirstResultSuspend { context: Context, forImmediateUse: Boolean ->
            SSDOcr.ModelFetcher(context).fetchData(forImmediateUse)
        }
        private val getCardDetectModel = cacheFirstResultSuspend { context: Context, forImmediateUse: Boolean ->
            CardDetect.ModelFetcher(context).fetchData(forImmediateUse)
        }
        private val getTextDetectorModel = cacheFirstResultSuspend { context: Context, forImmediateUse: Boolean ->
            TextDetect.ModelFetcher(context).fetchData(forImmediateUse)
        }
        private val getAlphabetDetectorModel = cacheFirstResultSuspend { context: Context, forImmediateUse: Boolean ->
            AlphabetDetect.ModelFetcher(context).fetchData(forImmediateUse)
        }
        private val getExpiryDetectorModel = cacheFirstResultSuspend { context: Context, forImmediateUse: Boolean ->
            ExpiryDetect.ModelFetcher(context).fetchData(forImmediateUse)
        }

        /**
         * Warm up the analyzers for card scanner. This method is optional, but will increase the speed at which the
         * scan occurs.
         *
         * @param context: A context to use for warming up the analyzers.
         */
        @JvmStatic
        fun warmUp(context: Context, apiKey: String, initializeNameAndExpiryExtraction: Boolean) {
            Config.apiKey = apiKey

            // pre-fetch all of the models used by this flow.
            GlobalScope.launch(Dispatchers.IO) { getSsdOcrModel(context, false) }
            GlobalScope.launch(Dispatchers.IO) { getCardDetectModel(context, false) }

            if (initializeNameAndExpiryExtraction) {
                attemptedNameAndExpiryInitialization = true
                GlobalScope.launch(Dispatchers.IO) { getTextDetectorModel(context, false) }
                GlobalScope.launch(Dispatchers.IO) { getAlphabetDetectorModel(context, false) }
                GlobalScope.launch(Dispatchers.IO) { getExpiryDetectorModel(context, false) }
            }
        }
    }

    /**
     * If this is true, do not start the flow.
     */
    private var canceled = false

    private var mainLoopAggregator: MainLoopAggregator? = null
    private var mainLoop: ProcessBoundAnalyzerLoop<SSDOcr.Input, MainLoopState, MainLoopAnalyzer.Prediction>? = null
    private var mainLoopJob: Job? = null

    /**
     * Start the image processing flow for scanning a card.
     *
     * @param context: The context used to download analyzers if needed
     * @param imageStream: The flow of images to process
     * @param previewSize: The size of the preview frame where the view finder is located
     */
    override fun startFlow(
        context: Context,
        imageStream: Flow<TrackedImage>,
        previewSize: Size,
        viewFinder: Rect,
        lifecycleOwner: LifecycleOwner,
        coroutineScope: CoroutineScope
    ) = coroutineScope.launch {
        val listener =
            object : AggregateResultListener<MainLoopAggregator.InterimResult, MainLoopAggregator.FinalResult> {
                override suspend fun onResult(result: MainLoopAggregator.FinalResult) {
                    mainLoop?.unsubscribe()
                    mainLoop = null

                    mainLoopJob?.cancel()
                    mainLoopAggregator = null

                    scanResultListener.onResult(result)
                }

                override suspend fun onInterimResult(result: MainLoopAggregator.InterimResult) {
                    scanResultListener.onInterimResult(result)
                }

                override suspend fun onReset() {
                    scanResultListener.onReset()
                }
            }

        if (canceled) {
            return@launch
        }

        mainLoopAggregator = MainLoopAggregator(listener).also { mainLoopOcrAggregator ->
            // make this result aggregator pause and reset when the lifecycle pauses.
            mainLoopOcrAggregator.bindToLifecycle(lifecycleOwner)

            val analyzerPool = AnalyzerPool.of(
                MainLoopAnalyzer.Factory(
                    SSDOcr.Factory(context, getSsdOcrModel(context, true)),
                    CardDetect.Factory(context, getCardDetectModel(context, true)),
                )
            )

            mainLoop = ProcessBoundAnalyzerLoop(
                analyzerPool = analyzerPool,
                resultHandler = mainLoopOcrAggregator,
                analyzerLoopErrorListener = scanErrorListener,
            ).apply {
                subscribeTo(
                    imageStream.map {
                        SSDOcr.Input(
                            fullImage = it,
                            previewSize = previewSize,
                            cardFinder = viewFinder,
                        )
                    },
                    coroutineScope,
                )
            }
        }
    }.let { Unit }

    /**
     * In the event that the scan cannot complete, halt the flow to halt analyzers and free up CPU and memory.
     */
    override fun cancelFlow() {
        canceled = true
        canceled = true

        mainLoopAggregator?.run { cancel() }
        mainLoopAggregator = null

        mainLoop?.unsubscribe()
        mainLoop = null

        mainLoopJob?.apply { if (isActive) { cancel() } }
        mainLoopJob = null
    }

    open fun launchCompletionLoop(
        context: Context,
        savedFrames: Collection<SavedFrame>,
        isFastDevice: Boolean,
        coroutineScope: CoroutineScope,
    ) = coroutineScope.launch {
        if (canceled) {
            return@launch
        }

        val analyzerPool = AnalyzerPool.of(
            CompletionLoopAnalyzer.Factory(
                nameAndExpiryFactory = NameAndExpiryAnalyzer.Factory(
                    textDetectFactory = TextDetect.Factory(context, getTextDetectorModel(context, true)),
                    alphabetDetectFactory = AlphabetDetect.Factory(context, getAlphabetDetectorModel(context, true)),
                    expiryDetectFactory = ExpiryDetect.Factory(context, getExpiryDetectorModel(context, true)),
                    runNameExtraction = enableNameExtraction && isFastDevice,
                    runExpiryExtraction = enableExpiryExtraction,
                )
            )
        )

        FiniteAnalyzerLoop(
            analyzerPool = analyzerPool,
            resultHandler = CompletionLoopAggregator(completionResultListener),
            analyzerLoopErrorListener = object : AnalyzerLoopErrorListener {
                override fun onAnalyzerFailure(t: Throwable): Boolean {
                    Log.e(Config.logTag, "Completion loop analyzer failure", t)
                    runBlocking {
                        completionResultListener.onCompletionLoopDone(CompletionLoopResult())
                    }
                    return true // terminate the loop on any analyzer failures
                }

                override fun onResultFailure(t: Throwable): Boolean {
                    Log.e(Config.logTag, "Completion loop result failures", t)
                    runBlocking {
                        completionResultListener.onCompletionLoopDone(CompletionLoopResult())
                    }
                    return true // terminate the loop on any result failures
                }
            }
        ).process(savedFrames, coroutineScope)
    }.let { Unit }

    /**
     * Select which frames to use in the completion loop.
     */
    open fun <SavedFrame> selectCompletionLoopFrames(
        frameRate: Rate,
        frames: Map<SavedFrameType, List<SavedFrame>>,
    ): Collection<SavedFrame> {
        fun getFrames(frameType: SavedFrameType) = frames[frameType] ?: emptyList()

        val cardAndPan = getFrames(SavedFrameType(hasCard = true, hasPan = true))
        val card = getFrames(SavedFrameType(hasCard = true, hasPan = false))
        val pan = getFrames(SavedFrameType(hasCard = false, hasPan = true))

        val maxCompletionLoopFrames =
            if (frameRate.duration <= Duration.ZERO || frameRate > Config.slowDeviceFrameRate) {
                MAX_COMPLETION_LOOP_FRAMES_FAST_DEVICE
            } else {
                MAX_COMPLETION_LOOP_FRAMES_SLOW_DEVICE
            }

        return (cardAndPan + card + pan).take(maxCompletionLoopFrames)
    }
}
