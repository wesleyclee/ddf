/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.ddf.security.servlet.web.socket;

import ddf.security.SecurityConstants;
import ddf.security.Subject;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import javax.servlet.http.HttpSession;
import org.codice.ddf.security.util.ThreadContextProperties;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SecureWebSocketServlet extends WebSocketServlet {

  private static final Logger LOGGER = LoggerFactory.getLogger(SecureWebSocketServlet.class);

  private final WebSocket ws;

  private final ExecutorService executor;

  public SecureWebSocketServlet(ExecutorService executor, WebSocket ws) {
    this.ws = ws;
    this.executor = executor;
  }

  @Override
  public void destroy() {
    executor.shutdown();
  }

  @Override
  public void configure(WebSocketServletFactory factory) {
    factory.setCreator((req, resp) -> new SocketWrapper(executor, ws, req.getSession()));
  }

  @org.eclipse.jetty.websocket.api.annotations.WebSocket
  public static class SocketWrapper {

    private final WebSocket ws;

    private final ExecutorService executor;

    private final HttpSession httpSession;

    SocketWrapper(ExecutorService executor, WebSocket ws, HttpSession httpSession) {
      this.ws = ws;
      this.executor = executor;
      this.httpSession = httpSession;
    }

    private void runWithUser(Session session, Runnable runnable) {
      Subject subject =
          (Subject)
              ((ServletUpgradeRequest) session.getUpgradeRequest())
                  .getHttpServletRequest()
                  .getAttribute(SecurityConstants.SECURITY_SUBJECT);

      executor.submit(
          () -> {
            subject.execute(runnable);
          });
    }

    @OnWebSocketConnect
    public void onOpen(Session session) {
      if (isUserLoggedIn()) {
        runWithUser(session, () -> ws.onOpen(session));
      } else {
        onError(session, new WebSocketAuthenticationException("User not logged in."));
      }
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
      runWithUser(session, () -> ws.onClose(session, statusCode, reason));
    }

    @OnWebSocketError
    public void onError(Session session, Throwable ex) {
      runWithUser(session, () -> ws.onError(session, ex));
    }

    @OnWebSocketMessage
    public void onMessage(Session session, String message) {
      if (isUserLoggedIn()) {
        runWithUser(
            session,
            () -> {
              String traceId = ThreadContextProperties.addTraceId();
              try {
                ws.onMessage(session, message);
              } catch (IOException e) {
                LOGGER.error("Failed to receive ws message.", e);
              } finally {
                LOGGER.trace("Removing trace ID {} from context", traceId);
                ThreadContextProperties.removeTraceId();
              }
            });
      } else {
        onError(session, new WebSocketAuthenticationException("User not logged in.", message));
      }
    }

    private boolean isUserLoggedIn() {
      if (httpSession == null) {
        return false;
      }

      try {
        httpSession.getCreationTime();
        return true;
      } catch (IllegalStateException ise) {
        // the session is invalid
        return false;
      }
    }
  }
}
