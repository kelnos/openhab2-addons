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
import org.eclipse.smarthome.core.library.types.DateTimeType;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.HSBType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.OpenClosedType;
import org.eclipse.smarthome.core.library.types.PointType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.library.types.UpDownType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.type.ChannelTypeUID;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.TypeParser;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.http.internal.model.ErrorListener;
import org.openhab.binding.http.internal.model.HttpChannelConfig;
import org.openhab.binding.http.internal.model.Transform;
import org.openhab.binding.http.internal.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.openhab.binding.http.internal.HttpBindingConstants.CHANNEL_TYPE_ID_IMAGE;
import static org.openhab.binding.http.internal.HttpBindingConstants.DEFAULT_CONTENT_TYPE;

/**
 * This holds the state and handles commands for a channel connected to a HTTP Thing.
 *
 * @author Brian J. Tarricone - Initial contribution
 */
@NonNullByDefault
public class HttpChannelState implements AutoCloseable {
    private static final List<Class<? extends State>> STATE_TYPES = Arrays.asList(
            OnOffType.class,
            OpenClosedType.class,
            UpDownType.class,
            PointType.class,
            HSBType.class,
            DecimalType.class,
            DateTimeType.class,
            StringType.class
    );

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ChannelUID channelUID;
    private final ChannelTypeUID channelTypeUID;
    private final HttpClient httpClient;
    private final int maxHttpResponseBodyLen;
    private final Optional<HttpChannelConfig.StateRequest> stateRequest;
    private final ScheduledExecutorService scheduler;
    private final Optional<HttpChannelConfig.CommandRequest> commandRequest;
    private final BiConsumer<ChannelUID, State> stateUpdatedListener;
    private final ErrorListener errorListener;

    private volatile boolean fetchingState = false;
    private Optional<ScheduledFuture<?>> stateUpdater = Optional.empty();
    private Optional<String> lastStateEtag = Optional.empty();

    public HttpChannelState(final ChannelUID channelUID,
                            final ChannelTypeUID channelTypeUID,
                            final HttpClient httpClient,
                            final int maxHttpResponseBodyLen,
                            final Optional<HttpChannelConfig.StateRequest> stateRequest,
                            final ScheduledExecutorService scheduler,
                            final Optional<HttpChannelConfig.CommandRequest> commandRequest,
                            final BiConsumer<ChannelUID, State> stateUpdatedListener,
                            final ErrorListener errorListener)
    {
        this.channelUID = channelUID;
        this.channelTypeUID = channelTypeUID;
        this.httpClient = httpClient;
        this.maxHttpResponseBodyLen = maxHttpResponseBodyLen;
        this.stateRequest = stateRequest;
        this.scheduler = scheduler;
        this.commandRequest = commandRequest;
        this.stateUpdatedListener = stateUpdatedListener;
        this.errorListener = errorListener;

        this.stateRequest.ifPresent(this::startStateFetch);
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
            logger.warn("[{}] Got command '{}', but no command URL set", this.channelUID.getId(), command.toFullString());
        } else {
            final HttpChannelConfig.CommandRequest commandRequest = this.commandRequest.get();
            final HttpChannelConfig.Method method = commandRequest.getMethod();
            final String commandStr = command.toFullString();
            try {
                final String transformedCommand = doTransform(commandRequest.getRequestTransform(), commandStr);
                final URL transformedUrl = formatUrl(commandRequest.getUrl(), transformedCommand);
                makeHttpRequest(
                        method,
                        transformedUrl,
                        commandRequest.getConnectTimeout(),
                        commandRequest.getRequestTimeout(),
                        commandRequest.getUsername(),
                        commandRequest.getPassword(),
                        commandRequest.getContentType(),
                        Optional.empty(),
                        Optional.of(commandStr)
                ).whenComplete((response, t) -> {
                    if (t != null) {
                        this.errorListener.accept(this.channelUID, ThingStatusDetail.COMMUNICATION_ERROR, "Connetion to server failed when sending command: " + t.getMessage());
                    } else if (response.getResponse().getStatus() / 100 != 2) {
                        this.errorListener.accept(this.channelUID, ThingStatusDetail.COMMUNICATION_ERROR, "Server returned HTTP status " + response.getResponse().getStatus() + " when sending command");
                    } else {
                        stateUpdatedListener.accept(this.channelUID, stateFromResponse(response, commandRequest.getResponseTransform()));
                    }
                });
            } catch (final IllegalArgumentException e) {
                this.errorListener.accept(this.channelUID,  ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
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

    private URL formatUrl(final URL origUrl, final String command) throws IllegalArgumentException {
        final String origUrlStr = origUrl.toString();
        if (origUrlStr.contains("%s")) {
            try {
                return new URL(String.format(origUrlStr, command));
            } catch (final MalformedURLException e) {
                throw new IllegalArgumentException("Failed to interpolate command into URL: " + e.getMessage(), e);
            }
        } else {
            return origUrl;
        }
    }

    private CompletionStage<HttpUtil.HttpResponse> makeHttpRequest(final HttpChannelConfig.Method method,
                                                                   final URL url,
                                                                   final Duration connectTimeout,
                                                                   final Duration requestTimeout,
                                                                   final Optional<String> username,
                                                                   final Optional<String> password,
                                                                   final String contentType,
                                                                   final Optional<String> lastEtag,
                                                                   final Optional<String> requestBody)
    {
        return HttpUtil.makeRequest(
                this.httpClient,
                method.toString(),
                url,
                username,
                password,
                contentType,
                lastEtag,
                requestBody,
                connectTimeout,
                requestTimeout,
                this.maxHttpResponseBodyLen
        );
    }

    private void startStateFetch(final HttpChannelConfig.StateRequest stateRequest) {
        cancelStateFetch();

        this.stateUpdater = Optional.of(scheduler.scheduleWithFixedDelay(
                () -> fetchState(stateRequest),
                0,
                stateRequest.getRefreshInterval().toMillis(),
                TimeUnit.MILLISECONDS
        ));
    }

    private void cancelStateFetch() {
        this.stateUpdater = this.stateUpdater.flatMap(su -> {
            su.cancel(false);
            return Optional.empty();
        });
    }

    private void fetchState(final HttpChannelConfig.StateRequest stateRequest) {
        if (!this.fetchingState) {
            this.fetchingState = true;
            final URL url = stateRequest.getUrl();
            makeHttpRequest(
                    HttpChannelConfig.Method.GET,
                    url,
                    stateRequest.getConnectTimeout(),
                    stateRequest.getRequestTimeout(),
                    stateRequest.getUsername(),
                    stateRequest.getPassword(),
                    DEFAULT_CONTENT_TYPE,
                    this.lastStateEtag,
                    Optional.empty()
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

    private String doTransform(final Optional<Transform> maybeTransform, final String value) throws IllegalArgumentException {
        return maybeTransform.map(transform -> transform.applyTransform(value)).orElse(value);
    }

    private State stateFromResponse(final HttpUtil.HttpResponse response, final Optional<Transform> transform) {
        if (CHANNEL_TYPE_ID_IMAGE.equals(this.channelTypeUID.getId())) {
            return response.asRawType();
        } else {
            try {
                return TypeParser.parseState(STATE_TYPES, doTransform(transform, response.asString()));
            } catch (final IllegalArgumentException e) {
                this.errorListener.accept(this.channelUID, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                return UnDefType.UNDEF;
            } catch (final IllegalStateException e) {
                this.errorListener.accept(this.channelUID, ThingStatusDetail.COMMUNICATION_ERROR, "HTTP server returned unparseable state: " + e.getMessage());
                return UnDefType.UNDEF;
            }
        }
    }
}
