/**
 * Copyright (c) 2010-2018 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.http.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.smarthome.core.thing.Channel;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.transform.TransformationException;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.TypeParser;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.http.internal.model.CommandRequest;
import org.openhab.binding.http.internal.model.ErrorListener;
import org.openhab.binding.http.internal.model.StateRequest;
import org.openhab.binding.http.internal.model.Transform;
import org.openhab.binding.http.internal.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.openhab.binding.http.internal.HttpBindingConstants.CHANNEL_STATE_TYPES;
import static org.openhab.binding.http.internal.HttpBindingConstants.CHANNEL_TYPE_ID_IMAGE;
import static org.openhab.binding.http.internal.HttpBindingConstants.MAX_IMAGE_RESPONSE_BODY_LEN;
import static org.openhab.binding.http.internal.HttpBindingConstants.MAX_RESPONSE_BODY_LEN;

/**
 * This holds the state and handles commands for a channel connected to a HTTP Thing.
 *
 * @author Brian J. Tarricone - Initial contribution
 */
@NonNullByDefault
public class HttpChannelState implements AutoCloseable {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ChannelUID channelUID;
    private final ChannelTypeUID channelTypeUID;
    private final HttpClient httpClient;
    private final int maxHttpResponseBodyLen;
    private final Optional<StateRequest> stateRequest;
    private final Optional<CommandRequest> commandRequest;
    private final Function<ChannelUID, Boolean> isChannelLinked;
    private final BiConsumer<ChannelUID, State> stateUpdatedListener;
    private final ErrorListener errorListener;

    private volatile boolean fetchingState = false;
    private Optional<ScheduledFuture<?>> stateUpdater = Optional.empty();
    private Optional<String> lastStateEtag = Optional.empty();

    public HttpChannelState(final Channel channel,
                            final HttpClient httpClient,
                            final Optional<StateRequest> stateRequest,
                            final ScheduledExecutorService scheduler,
                            final Optional<CommandRequest> commandRequest,
                            final Function<ChannelUID, Boolean> isChannelLinked,
                            final BiConsumer<ChannelUID, State> stateUpdatedListener,
                            final ErrorListener errorListener)
    {
        this.channelUID = channel.getUID();
        this.channelTypeUID = channel.getChannelTypeUID();
        this.httpClient = httpClient;
        this.maxHttpResponseBodyLen = CHANNEL_TYPE_ID_IMAGE.equals(channel.getChannelTypeUID().getId())
                ? MAX_IMAGE_RESPONSE_BODY_LEN : MAX_RESPONSE_BODY_LEN;
        this.stateRequest = stateRequest;
        this.commandRequest = commandRequest;
        this.isChannelLinked = isChannelLinked;
        this.stateUpdatedListener = stateUpdatedListener;
        this.errorListener = errorListener;

        this.stateRequest.ifPresent(sr ->
                this.stateUpdater = Optional.of(scheduler.scheduleWithFixedDelay(
                        () -> fetchState(sr),
                        0,
                        sr.getRefreshInterval().toMillis(),
                        TimeUnit.MILLISECONDS
                ))
        );
    }

    /**
     * Handles a single command.
     *
     * @param command the {@link Command}
     */
    public void handleCommand(final Command command) {
        if (command.equals(RefreshType.REFRESH)) {
            this.stateRequest.ifPresent(this::fetchState);
        } else if (!this.commandRequest.isPresent()) {
            logger.warn("[{}] Got command on channel '{}', but no command URL set", this.channelUID.getId(), command.toFullString());
        } else {
            final CommandRequest commandRequest = this.commandRequest.get();
            final String commandStr = command.toFullString();
            try {
                final String transformedCommand = doTransform(commandRequest.getRequestTransform(), commandStr);
                final URL transformedUrl = formatUrl(commandRequest.getUrl(), transformedCommand);
                HttpUtil.makeRequest(
                        this.httpClient,
                        commandRequest,
                        transformedUrl,
                        Optional.empty(),
                        Optional.of(commandStr),
                        this.maxHttpResponseBodyLen
                ).whenComplete((response, t) -> {
                    if (t != null) {
                        this.errorListener.accept(this.channelUID, ThingStatusDetail.COMMUNICATION_ERROR, "Connection to server failed when sending command: " + t.getMessage());
                    } else if (response.getResponse().getStatus() / 100 != 2) {
                        this.errorListener.accept(this.channelUID, ThingStatusDetail.COMMUNICATION_ERROR, "Server returned HTTP status " + response.getResponse().getStatus() + " when sending command");
                    } else {
                        stateUpdatedListener.accept(this.channelUID, stateFromResponse(response, commandRequest.getResponseTransform()));
                    }
                });
            } catch (final TransformationException e) {
                this.errorListener.accept(this.channelUID,  ThingStatusDetail.COMMUNICATION_ERROR, "Failed to transform request: " + e.getMessage());
            } catch (final MalformedURLException e) {
                this.errorListener.accept(this.channelUID,  ThingStatusDetail.CONFIGURATION_ERROR, "Failed to interpolate command into URL: " + e.getMessage());
            }
        }
    }

    /**
     * Shuts down the channel and canceles any background tasks
     */
    @Override
    public void close() {
        cancelStateFetch();
    }

    private URL formatUrl(final URL origUrl, final String command) throws MalformedURLException {
        final String origUrlStr = origUrl.toString();
        if (origUrlStr.contains("%s")) {
            return new URL(String.format(origUrlStr, command));
        } else {
            return origUrl;
        }
    }

    private void cancelStateFetch() {
        this.stateUpdater = this.stateUpdater.flatMap(su -> {
            su.cancel(false);
            return Optional.empty();
        });
    }

    private void fetchState(final StateRequest stateRequest) {
        if (!this.fetchingState && isChannelLinked.apply(this.channelUID)) {
            this.fetchingState = true;
            final URL url = stateRequest.getUrl();
            HttpUtil.makeRequest(
                    this.httpClient,
                    stateRequest,
                    url,
                    this.lastStateEtag,
                    Optional.empty(),
                    this.maxHttpResponseBodyLen
            ).whenComplete((response, t) -> {
                this.fetchingState = false;
                if (t != null) {
                    this.errorListener.accept(this.channelUID, ThingStatusDetail.COMMUNICATION_ERROR, "Connection to server failed when fetching state: " + t.getMessage());
                } else if (response.getResponse().getStatus() / 100 != 2 && response.getResponse().getStatus() != HttpStatus.NOT_MODIFIED_304) {
                    this.errorListener.accept(this.channelUID, ThingStatusDetail.COMMUNICATION_ERROR, "Server returned HTTP status " + response.getResponse().getStatus() + " when fetching state");
                } else if (response.getResponse().getStatus() != HttpStatus.NOT_MODIFIED_304) {
                    final State newState = stateFromResponse(response, stateRequest.getResponseTransform());
                    logger.debug("[{}] got new state '{}'", this.channelUID.getId(), newState.toFullString());
                    this.stateUpdatedListener.accept(this.channelUID, newState);
                    this.lastStateEtag = Optional.ofNullable(response.getResponse().getHeaders().get("etag"));
                }
            });
        }
    }

    private String doTransform(final Optional<Transform> transform, final String value) throws TransformationException {
        if (transform.isPresent()) {
            return transform.get().applyTransform(value);
        } else {
            return value;
        }
    }

    private State stateFromResponse(final HttpUtil.HttpResponse response, final Optional<Transform> transform) {
        if (CHANNEL_TYPE_ID_IMAGE.equals(this.channelTypeUID.getId())) {
            return response.asRawType();
        } else if (CHANNEL_STATE_TYPES.containsKey(this.channelTypeUID.getId())){
            try {
                final String stateStr = doTransform(transform, response.asString());
                return Optional.ofNullable(TypeParser.parseState(CHANNEL_STATE_TYPES.get(this.channelTypeUID.getId()), stateStr)).orElseGet(() -> {
                    this.errorListener.accept(this.channelUID, ThingStatusDetail.COMMUNICATION_ERROR, String.format("State '%s' is not valid for channel '%s'", stateStr, this.channelUID.getId()));
                    return UnDefType.UNDEF;
                });
            } catch (final TransformationException e) {
                this.errorListener.accept(this.channelUID, ThingStatusDetail.COMMUNICATION_ERROR, "Failed to transform response: " + e.getMessage());
                return UnDefType.UNDEF;
            } catch (final IllegalStateException e) {
                this.errorListener.accept(this.channelUID, ThingStatusDetail.COMMUNICATION_ERROR, "HTTP server returned unparseable state: " + e.getMessage());
                return UnDefType.UNDEF;
            }
        } else {
            logger.warn("Unknown channel type '{}' for channel '{}'", this.channelTypeUID.getId(), this.channelUID.getId());
            return UnDefType.UNDEF;
        }
    }
}
