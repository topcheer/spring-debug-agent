package com.demo.websocket;

import com.debugagent.inspector.WebSocketInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class DemoWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(DemoWebSocketHandler.class);

    @Autowired(required = false)
    private WebSocketInspector webSocketInspector;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("WebSocket connected: {} from {}", session.getId(), session.getRemoteAddress());
        if (webSocketInspector != null) {
            webSocketInspector.recordSession(session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.info("WebSocket message from {}: {}", session.getId(), message.getPayload());
        if (webSocketInspector != null) {
            webSocketInspector.recordMessage(session.getId(), "inbound", message.getPayload());
        }
        session.sendMessage(new TextMessage("Echo: " + message.getPayload()));
        if (webSocketInspector != null) {
            webSocketInspector.recordMessage(session.getId(), "outbound", "Echo: " + message.getPayload());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket closed: {} status={}", session.getId(), status);
        if (webSocketInspector != null) {
            webSocketInspector.recordSessionClosed(session.getId());
        }
    }
}
