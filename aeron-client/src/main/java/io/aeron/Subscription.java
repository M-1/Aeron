/*
 * Copyright 2014 - 2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron;

import io.aeron.logbuffer.*;
import org.agrona.collections.ArrayUtil;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

class SubscriptionLhsPadding
{
    @SuppressWarnings("unused")
    protected long p1, p2, p3, p4, p5, p6, p7;
}

class SubscriptionHotFields extends SubscriptionLhsPadding
{
    protected int roundRobinIndex = 0;
}

class SubscriptionRhsPadding extends SubscriptionHotFields
{
    @SuppressWarnings("unused")
    protected long p8, p9, p10, p11, p12, p13, p14;
}

/**
 * Aeron Subscriber API for receiving a reconstructed {@link Image} for a stream of messages from publishers on
 * a given channel and streamId pair.
 *
 * Subscribers are created via an {@link Aeron} object, and received messages are delivered
 * to the {@link FragmentHandler}.
 *
 * By default fragmented messages are not reassembled before delivery. If an application must
 * receive whole messages, whether or not they were fragmented, then the Subscriber
 * should be created with a {@link FragmentAssembler} or a custom implementation.
 *
 * It is an application's responsibility to {@link #poll} the Subscriber for new messages.
 *
 * Subscriptions are not threadsafe and should not be shared between subscribers.
 *
 * @see FragmentAssembler
 */
public class Subscription extends SubscriptionRhsPadding implements AutoCloseable
{
    private static final Image[] EMPTY_ARRAY = new Image[0];

    private final long registrationId;
    private final int streamId;
    private volatile boolean isClosed = false;

    private volatile Image[] images = EMPTY_ARRAY;
    private final ClientConductor clientConductor;
    private final String channel;

    Subscription(final ClientConductor conductor, final String channel, final int streamId, final long registrationId)
    {
        this.clientConductor = conductor;
        this.channel = channel;
        this.streamId = streamId;
        this.registrationId = registrationId;
    }

    /**
     * Media address for delivery to the channel.
     *
     * @return Media address for delivery to the channel.
     */
    public String channel()
    {
        return channel;
    }

    /**
     * Stream identity for scoping within the channel media address.
     *
     * @return Stream identity for scoping within the channel media address.
     */
    public int streamId()
    {
        return streamId;
    }

    /**
     * Poll the {@link Image}s under the subscription for available message fragments.
     *
     * Each fragment read will be a whole message if it is under MTU length. If larger than MTU then it will come
     * as a series of fragments ordered within a session.
     *
     * To assemble messages that span multiple fragments then use {@link FragmentAssembler}.
     *
     * @param fragmentHandler callback for handling each message fragment as it is read.
     * @param fragmentLimit   number of message fragments to limit for the poll operation across multiple {@link Image}s.
     * @return the number of fragments received
     */
    public int poll(final FragmentHandler fragmentHandler, final int fragmentLimit)
    {
        final Image[] images = this.images;
        final int length = images.length;
        int fragmentsRead = 0;

        int startingIndex = roundRobinIndex++;
        if (startingIndex >= length)
        {
            roundRobinIndex = startingIndex = 0;
        }

        for (int i = startingIndex; i < length && fragmentsRead < fragmentLimit; i++)
        {
            fragmentsRead += images[i].poll(fragmentHandler, fragmentLimit - fragmentsRead);
        }

        for (int i = 0; i < startingIndex && fragmentsRead < fragmentLimit; i++)
        {
            fragmentsRead += images[i].poll(fragmentHandler, fragmentLimit - fragmentsRead);
        }

        return fragmentsRead;
    }

    /**
     * Poll in a controlled manner the {@link Image}s under the subscription for available message fragments.
     * Control is applied to fragments in the stream. If more fragments can be read on another stream
     * they will even if BREAK or ABORT is returned from the fragment handler.
     *
     * Each fragment read will be a whole message if it is under MTU length. If larger than MTU then it will come
     * as a series of fragments ordered within a session.
     *
     * To assemble messages that span multiple fragments then use {@link ControlledFragmentAssembler}.
     *
     * @param fragmentHandler callback for handling each message fragment as it is read.
     * @param fragmentLimit   number of message fragments to limit for the poll operation across multiple {@link Image}s.
     * @return the number of fragments received
     * @see ControlledFragmentHandler
     */
    public int controlledPoll(final ControlledFragmentHandler fragmentHandler, final int fragmentLimit)
    {
        final Image[] images = this.images;
        final int length = images.length;
        int fragmentsRead = 0;

        int startingIndex = roundRobinIndex++;
        if (startingIndex >= length)
        {
            roundRobinIndex = startingIndex = 0;
        }

        for (int i = startingIndex; i < length && fragmentsRead < fragmentLimit; i++)
        {
            fragmentsRead += images[i].controlledPoll(fragmentHandler, fragmentLimit - fragmentsRead);
        }

        for (int i = 0; i < startingIndex && fragmentsRead < fragmentLimit; i++)
        {
            fragmentsRead += images[i].controlledPoll(fragmentHandler, fragmentLimit - fragmentsRead);
        }

        return fragmentsRead;
    }

    /**
     * Poll the {@link Image}s under the subscription for available message fragments in blocks.
     *
     * This method is useful for operations like bulk archiving and messaging indexing.
     *
     * @param blockHandler     to receive a block of fragments from each {@link Image}.
     * @param blockLengthLimit for each {@link Image} polled.
     * @return the number of bytes consumed.
     */
    public long blockPoll(final BlockHandler blockHandler, final int blockLengthLimit)
    {
        long bytesConsumed = 0;
        for (final Image image : images)
        {
            bytesConsumed += image.blockPoll(blockHandler, blockLengthLimit);
        }

        return bytesConsumed;
    }

    /**
     * Poll the {@link Image}s under the subscription for available message fragments in blocks.
     *
     * This method is useful for operations like bulk archiving a stream to file.
     *
     * @param rawBlockHandler to receive a block of fragments from each {@link Image}.
     * @param blockLengthLimit for each {@link Image} polled.
     * @return the number of bytes consumed.
     */
    public long rawPoll(final RawBlockHandler rawBlockHandler, final int blockLengthLimit)
    {
        long bytesConsumed = 0;
        for (final Image image : images)
        {
            bytesConsumed += image.rawPoll(rawBlockHandler, blockLengthLimit);
        }

        return bytesConsumed;
    }

    /**
     * Count of images connected to this subscription.
     *
     * @return count of images connected to this subscription.
     */
    public int imageCount()
    {
        return images.length;
    }

    /**
     * Return the {@link Image} associated with the given sessionId.
     *
     * @param sessionId associated with the Image.
     * @return Image associated with the given sessionId or null if no Image exist.
     */
    public Image imageBySessionId(final int sessionId)
    {
        Image result = null;

        for (final Image image : images)
        {
            if (sessionId == image.sessionId())
            {
                result = image;
                break;
            }
        }

        return result;
    }

    /**
     * Get a {@link List} of active {@link Image}s that match this subscription.
     *
     * @return a {@link List} of active {@link Image}s that match this subscription.
     */
    public List<Image> images()
    {
        return Arrays.asList(images);
    }

    /**
     * Iterate over the {@link Image}s for this subscription.
     *
     * @param imageConsumer to handle each {@link Image}.
     */
    public void forEachImage(final Consumer<Image> imageConsumer)
    {
        for (final Image image : images)
        {
            imageConsumer.accept(image);
        }
    }

    /**
     * Close the Subscription so that associated {@link Image}s can be released.
     *
     * This method is idempotent.
     */
    public void close()
    {
        clientConductor.mainLock().lock();
        try
        {
            if (!isClosed)
            {
                isClosed = true;

                clientConductor.releaseSubscription(this);

                for (final Image image : images)
                {
                    clientConductor.unavailableImageHandler().onUnavailableImage(image);
                    clientConductor.lingerResource(image.managedResource());
                }

                this.images = EMPTY_ARRAY;
            }
        }
        finally
        {
            clientConductor.mainLock().unlock();
        }
    }

    /**
     * Has this object been closed and should no longer be used?
     *
     * @return true if it has been closed otherwise false.
     */
    public boolean isClosed()
    {
        return isClosed;
    }

    /**
     * Return the registration id used to register this Publication with the media driver.
     *
     * @return registration id
     */
    public long registrationId()
    {
        return registrationId;
    }

    void addImage(final Image image)
    {
        if (isClosed)
        {
            clientConductor.lingerResource(image.managedResource());
        }
        else
        {
            images = ArrayUtil.add(images, image);
        }
    }

    Image removeImage(final long correlationId)
    {
        final Image[] oldArray = images;
        Image removedImage = null;

        for (final Image image : oldArray)
        {
            if (image.correlationId() == correlationId)
            {
                removedImage = image;
                break;
            }
        }

        if (null != removedImage)
        {
            images = ArrayUtil.remove(oldArray, removedImage);
            clientConductor.lingerResource(removedImage.managedResource());
        }

        return removedImage;
    }

    boolean hasImage(final long correlationId)
    {
        boolean hasImage = false;

        for (final Image image : images)
        {
            if (correlationId == image.correlationId())
            {
                hasImage = true;
                break;
            }
        }

        return hasImage;
    }

    boolean hasNoImages()
    {
        return images.length == 0;
    }
}
