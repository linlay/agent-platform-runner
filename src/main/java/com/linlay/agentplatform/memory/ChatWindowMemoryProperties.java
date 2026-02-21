package com.linlay.agentplatform.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "memory.chat")
public class ChatWindowMemoryProperties {

    private String dir = "./chats";
    private int k = 20;
    private String charset = "UTF-8";
    private java.util.List<String> actionTools = java.util.List.of();

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public int getK() {
        return k;
    }

    public void setK(int k) {
        this.k = k;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public java.util.List<String> getActionTools() {
        return actionTools;
    }

    public void setActionTools(java.util.List<String> actionTools) {
        this.actionTools = actionTools == null ? java.util.List.of() : java.util.List.copyOf(actionTools);
    }
}
