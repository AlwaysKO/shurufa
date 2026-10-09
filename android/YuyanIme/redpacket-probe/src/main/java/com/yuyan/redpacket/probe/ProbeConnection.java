package com.yuyan.redpacket.probe;

final class ProbeConnection {
    private final Runnable connect, status, unavailable;
    private boolean connecting, connected;
    ProbeConnection(Runnable connect, Runnable status, Runnable unavailable) {
        this.connect = connect; this.status = status; this.unavailable = unavailable;
    }
    void resume(boolean authorized) {
        if (authorized && !connected && !connecting) {
            connecting = true; connect.run();
        }
    }
    void refresh(boolean authorized) {
        if (connected) status.run();
        else if (authorized) resume(true);
        else unavailable.run();
    }
    void connected() { connecting = false; connected = true; status.run(); }
    void disconnected() { connecting = false; connected = false; }
    boolean attached() { return connecting || connected; }
}
