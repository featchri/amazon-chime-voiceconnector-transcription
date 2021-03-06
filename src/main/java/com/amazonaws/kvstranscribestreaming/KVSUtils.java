package com.amazonaws.kvstranscribestreaming;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.kinesisvideo.parser.ebml.MkvTypeInfos;
import com.amazonaws.kinesisvideo.parser.mkv.Frame;
import com.amazonaws.kinesisvideo.parser.mkv.MkvDataElement;
import com.amazonaws.kinesisvideo.parser.mkv.MkvElement;
import com.amazonaws.kinesisvideo.parser.mkv.MkvElementVisitException;
import com.amazonaws.kinesisvideo.parser.mkv.MkvValue;
import com.amazonaws.kinesisvideo.parser.mkv.StreamingMkvReader;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadataVisitor;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoClientBuilder;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoMediaClientBuilder;
import com.amazonaws.services.kinesisvideo.model.APIName;
import com.amazonaws.services.kinesisvideo.model.GetDataEndpointRequest;
import com.amazonaws.services.kinesisvideo.model.GetMediaRequest;
import com.amazonaws.services.kinesisvideo.model.GetMediaResult;
import com.amazonaws.services.kinesisvideo.model.StartSelector;
import com.amazonaws.services.kinesisvideo.model.StartSelectorType;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Utility class to interact with KVS streams
 *
 * <p>Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.</p>
 *
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
public final class KVSUtils {

    private static final Logger logger = LoggerFactory.getLogger(KVSUtils.class);

    /**
     * Fetches the next ByteBuffer of size 1024 bytes from the KVS stream by parsing the frame from the MkvElement
     * Each frame has a ByteBuffer having size 1024
     *
     * @param streamingMkvReader
     * @param fragmentVisitor
     * @param tagProcessor
     * @return
     * @throws MkvElementVisitException
     */
    public static ByteBuffer getByteBufferFromStream(StreamingMkvReader streamingMkvReader,
                                                     FragmentMetadataVisitor fragmentVisitor,
                                                     KVSTransactionIdTagProcessor tagProcessor) throws MkvElementVisitException {

        if (!tagProcessor.shouldStopProcessing()) {
            while (streamingMkvReader.mightHaveNext()) {
                Optional<MkvElement> mkvElementOptional = streamingMkvReader.nextIfAvailable();
                if (mkvElementOptional.isPresent()) {

                    MkvElement mkvElement = mkvElementOptional.get();
                    mkvElement.accept(fragmentVisitor);

                    if (MkvTypeInfos.SIMPLEBLOCK.equals(mkvElement.getElementMetaData().getTypeInfo())) {
                        MkvDataElement dataElement = (MkvDataElement) mkvElement;
                        Frame frame = ((MkvValue<Frame>) dataElement.getValueCopy()).getVal();
                        ByteBuffer audioBuffer = frame.getFrameData();
                        return audioBuffer;
                    }
                }
            }
        }

        return ByteBuffer.allocate(0);
    }

    /**
     * Fetches ByteBuffer of provided size from the KVS stream by repeatedly calling KVS
     * and concatenating the ByteBuffers to create a single chunk
     *
     * @param streamingMkvReader
     * @param fragmentVisitor
     * @param tagProcessor
     * @param chunkSizeInKB
     * @return
     * @throws MkvElementVisitException
     */
    public static ByteBuffer getByteBufferFromStream(StreamingMkvReader streamingMkvReader,
                                                     FragmentMetadataVisitor fragmentVisitor,
                                                     KVSTransactionIdTagProcessor tagProcessor,
                                                     int chunkSizeInKB) throws MkvElementVisitException {

        List<ByteBuffer> byteBufferList = new ArrayList<ByteBuffer>();

        for (int i = 0; i < chunkSizeInKB; i++) {
            ByteBuffer byteBuffer = KVSUtils.getByteBufferFromStream(streamingMkvReader, fragmentVisitor, tagProcessor);
            if (byteBuffer.remaining() > 0) {
                byteBufferList.add(byteBuffer);
            } else {
                break;
            }
        }

        int length = 0;

        for (ByteBuffer bb : byteBufferList) {
            length += bb.remaining();
        }

        if (length == 0) {
            return ByteBuffer.allocate(0);
        }

        ByteBuffer combinedByteBuffer = ByteBuffer.allocate(length);

        for (ByteBuffer bb : byteBufferList) {
            combinedByteBuffer.put(bb);
        }

        combinedByteBuffer.flip();
        return combinedByteBuffer;
    }

    /**
     * Makes a GetMedia call to KVS and retrieves the InputStream corresponding to the given streamName and startFragmentNum
     *
     * @param streamArn
     * @param region
     * @param startFragmentNum
     * @param awsCredentialsProvider
     * @return
     */
    public static InputStream getInputStreamFromKVS(String streamArn,
                                                    Regions region,
                                                    String startFragmentNum,
                                                    AWSCredentialsProvider awsCredentialsProvider) {
        Validate.notNull(streamArn);
        Validate.notNull(region);
        Validate.notNull(startFragmentNum);
        Validate.notNull(awsCredentialsProvider);

        AmazonKinesisVideo amazonKinesisVideo = (AmazonKinesisVideo) AmazonKinesisVideoClientBuilder.standard().build();

        String endPoint = amazonKinesisVideo.getDataEndpoint(new GetDataEndpointRequest()
                .withAPIName(APIName.GET_MEDIA)
                .withStreamARN(streamArn)).getDataEndpoint();

        AmazonKinesisVideoMediaClientBuilder amazonKinesisVideoMediaClientBuilder = AmazonKinesisVideoMediaClientBuilder.standard()
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endPoint, region.getName()))
                .withCredentials(awsCredentialsProvider);
        AmazonKinesisVideoMedia amazonKinesisVideoMedia = amazonKinesisVideoMediaClientBuilder.build();

        StartSelector startSelector;
        if (startFragmentNum != null)
        {
            startSelector = new StartSelector()
                .withStartSelectorType(StartSelectorType.FRAGMENT_NUMBER)
                .withAfterFragmentNumber(startFragmentNum);
        } else {
            startSelector = new StartSelector().withStartSelectorType(StartSelectorType.EARLIEST);
        }


        GetMediaResult getMediaResult = amazonKinesisVideoMedia.getMedia(new GetMediaRequest()
                .withStreamARN(streamArn)
                .withStartSelector(startSelector));

        logger.info("GetMedia called on stream {} response {} requestId {}", streamArn,
                getMediaResult.getSdkHttpMetadata().getHttpStatusCode(),
                getMediaResult.getSdkResponseMetadata().getRequestId());

        return getMediaResult.getPayload();
    }
}
