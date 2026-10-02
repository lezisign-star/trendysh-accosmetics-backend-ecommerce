package com.nbh.backend.live;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live "look again" hints for the public storefront: products, stock and delivery prices changed. No data on the wire. */
@RestController
@RequestMapping("/api/v1/public/events")
public class PublicEventStreamController {

    private final LiveStreams streams;

    public PublicEventStreamController(LiveStreams streams) {
        this.streams = streams;
    }

    @GetMapping(produces = "text/event-stream")
    public SseEmitter events() {
        return streams.subscribePublic();
    }
}
