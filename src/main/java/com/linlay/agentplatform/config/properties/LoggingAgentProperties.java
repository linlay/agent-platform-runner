package com.linlay.agentplatform.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "logging.agent")
public class LoggingAgentProperties {

    private final Request request = new Request();
    private final Auth auth = new Auth();
    private final ExceptionLogging exception = new ExceptionLogging();
    private final Tool tool = new Tool();
    private final Tool action = new Tool();
    private final Viewport viewport = new Viewport();
    private final Sse sse = new Sse();

    public Request getRequest() {
        return request;
    }

    public Auth getAuth() {
        return auth;
    }

    public ExceptionLogging getException() {
        return exception;
    }

    public Tool getTool() {
        return tool;
    }

    public Tool getAction() {
        return action;
    }

    public Viewport getViewport() {
        return viewport;
    }

    public Sse getSse() {
        return sse;
    }

    public static class Request {
        private boolean enabled = true;
        private boolean includeQuery = true;
        private boolean includeBody = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isIncludeQuery() {
            return includeQuery;
        }

        public void setIncludeQuery(boolean includeQuery) {
            this.includeQuery = includeQuery;
        }

        public boolean isIncludeBody() {
            return includeBody;
        }

        public void setIncludeBody(boolean includeBody) {
            this.includeBody = includeBody;
        }
    }

    public static class Auth {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class ExceptionLogging {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Tool {
        private boolean enabled = true;
        private boolean includeArgs = false;
        private boolean includeResult = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isIncludeArgs() {
            return includeArgs;
        }

        public void setIncludeArgs(boolean includeArgs) {
            this.includeArgs = includeArgs;
        }

        public boolean isIncludeResult() {
            return includeResult;
        }

        public void setIncludeResult(boolean includeResult) {
            this.includeResult = includeResult;
        }
    }

    public static class Viewport {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Sse {
        private boolean enabled = false;
        private boolean includePayload = false;
        private List<String> eventWhitelist = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isIncludePayload() {
            return includePayload;
        }

        public void setIncludePayload(boolean includePayload) {
            this.includePayload = includePayload;
        }

        public List<String> getEventWhitelist() {
            return eventWhitelist;
        }

        public void setEventWhitelist(List<String> eventWhitelist) {
            this.eventWhitelist = eventWhitelist == null ? new ArrayList<>() : new ArrayList<>(eventWhitelist);
        }
    }
}
