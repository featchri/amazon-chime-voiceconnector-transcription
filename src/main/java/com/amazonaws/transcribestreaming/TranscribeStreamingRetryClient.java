package com.amazonaws.transcribestreaming;

import com.amazonaws.kvstranscribestreaming.MetricsUtil;
import com.amazonaws.regions.Regions;
import org.apache.commons.lang3.Validate;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.transcribestreaming.TranscribeStreamingAsyncClient;
import software.amazon.awssdk.services.transcribestreaming.model.AudioStream;
import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionRequest;
import software.amazon.awssdk.services.transcribestreaming.model.StartStreamTranscriptionResponseHandler;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Build a client wrapper around the Amazon Transcribe client to retry
 * on an exception that can be retried.
 *
 * <p>
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
public class TranscribeStreamingRetryClient implements AutoCloseable {

    private static final int DEFAULT_MAX_SLEEP_TIME_MILLS = 500;
    private int sleepTime = DEFAULT_MAX_SLEEP_TIME_MILLS;
    private final TranscribeStreamingAsyncClient client;
    private final MetricsUtil metricsUtil;
    List<Class<?>> nonRetriableExceptions = Arrays.asList(SdkClientException.class);

    private static final Logger logger = LoggerFactory.getLogger(TranscribeStreamingRetryClient.class);

    /**
     * Create a TranscribeStreamingRetryClient with given credential and configuration
     *
     * @param creds       Creds to use for transcription
     * @param endpoint    Endpoint to use for transcription
     * @param region      Region to use for transcriptions
     * @param metricsUtil
     * @throws URISyntaxException if the endpoint is not a URI
     */
    public TranscribeStreamingRetryClient(AwsCredentialsProvider creds,
                                          String endpoint, Regions region, MetricsUtil metricsUtil) throws URISyntaxException {
        this(TranscribeStreamingAsyncClient.builder()
                .credentialsProvider(creds)
                .endpointOverride(new URI(endpoint))
                .region(Region.of(region.getName()))
                .build(), metricsUtil);
    }

    /**
     * Initiate TranscribeStreamingRetryClient with TranscribeStreamingAsyncClient
     *
     * @param client      TranscribeStreamingAsyncClient
     * @param metricsUtil
     */
    public TranscribeStreamingRetryClient(TranscribeStreamingAsyncClient client, MetricsUtil metricsUtil) {
        this.client = client;
        this.metricsUtil = metricsUtil;
    }

    /**
     * Initiate a Stream Transcription with retry.
     *
     * @param request         StartStreamTranscriptionRequest to use to start transcription
     * @param publisher       The source audio stream as Publisher
     * @param responseHandler StreamTranscriptionBehavior object that defines how the response needs to be handled.
     * @return Completable future to handle stream response.
     */

    public CompletableFuture<Void> startStreamTranscription(final StartStreamTranscriptionRequest request,
                                                            final Publisher<AudioStream> publisher,
                                                            final StreamTranscriptionBehavior responseHandler) {

        Validate.notNull(request);
        Validate.notNull(publisher);
        Validate.notNull(responseHandler);

        CompletableFuture<Void> finalFuture = new CompletableFuture<>();

        recursiveStartStream(rebuildRequestWithSession(request), publisher, responseHandler, finalFuture);

        return finalFuture;
    }

    /**
     * Recursively call startStreamTranscription() to be called till the request is completed or till we run out of retries.
     *
     * @param request         StartStreamTranscriptionRequest
     * @param publisher       The source audio stream as Publisher
     * @param responseHandler StreamTranscriptionBehavior object that defines how the response needs to be handled.
     * @param finalFuture     final future to finish on completing the chained futures.
     */
    private void recursiveStartStream(final StartStreamTranscriptionRequest request,
                                      final Publisher<AudioStream> publisher,
                                      final StreamTranscriptionBehavior responseHandler,
                                      final CompletableFuture<Void> finalFuture) {
        CompletableFuture<Void> result = client.startStreamTranscription(request, publisher,
                getResponseHandler(responseHandler));
        result.whenComplete((r, e) -> {
            if (e != null) {
                logger.debug("Error occured: " + e.getMessage());

                if (isExceptionRetriable(e)) {
                    logger.debug("Retriable error occurred and will be retried.");
                    logger.debug("Sleeping for sometime before retrying...");
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e1) {
                        logger.error("Sleep between retries interrupted. Failed with exception: ", e);
                        finalFuture.completeExceptionally(e);
                    }
                    recursiveStartStream(request, publisher, responseHandler, finalFuture);
                } else {
                    metricsUtil.recordMetric("TranscribeStreamError", 1);
                    logger.error("Encountered unretriable exception or ran out of retries.", e);
                    responseHandler.onError(e);
                    finalFuture.completeExceptionally(e);
                }
            } else {
                metricsUtil.recordMetric("TranscribeStreamError", 0);
                responseHandler.onComplete();
                finalFuture.complete(null);
            }
        });
    }

    private StartStreamTranscriptionRequest rebuildRequestWithSession(StartStreamTranscriptionRequest request) {
        return StartStreamTranscriptionRequest.builder()
                .languageCode(request.languageCode())
                .mediaEncoding(request.mediaEncoding())
                .mediaSampleRateHertz(request.mediaSampleRateHertz())
                .sessionId(UUID.randomUUID().toString())
                .build();
    }

    /**
     * StartStreamTranscriptionResponseHandler implements subscriber of transcript stream
     * Output is printed to standard output
     */
    private StartStreamTranscriptionResponseHandler getResponseHandler(
            StreamTranscriptionBehavior transcriptionBehavior) {
        final StartStreamTranscriptionResponseHandler build = StartStreamTranscriptionResponseHandler.builder()
                .onResponse(r -> {
                    transcriptionBehavior.onResponse(r);
                })
                .onError(e -> {
                    //Do nothing here. Don't close any streams that shouldn't be cleaned up yet.
                })
                .onComplete(() -> {
                    //Do nothing here. Don't close any streams that shouldn't be cleaned up yet.
                })
                .subscriber(event -> {
                    try {
                        transcriptionBehavior.onStream(event);
                    }
                    // We swallow any exception occurred while processing the TranscriptEvent and continue transcribing
                    // Transcribe errors will however cause the future to complete exceptionally and we'll retry (if applicable)
                    catch (Exception e) {
                    }
                })
                .build();
        return build;
    }

    /**
     * Check if the exception can be retried.
     *
     * @param e Exception that occurred
     * @return True if the exception is retriable
     */
    private boolean isExceptionRetriable(Throwable e) {
        if (nonRetriableExceptions.contains(e.getCause().getClass())) {
            return false;
        }
        return true;
    }

    @Override
    public void close() {
        logger.debug("TranscribeStreamingRetryClient closed");
        this.client.close();
    }
}
