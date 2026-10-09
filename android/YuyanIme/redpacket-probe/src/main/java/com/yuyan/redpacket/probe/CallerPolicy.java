package com.yuyan.redpacket.probe;
final class CallerPolicy {
    static boolean allowed(int caller, int client, int service, boolean destroy) {
        return client >= 10000 && (caller == client || (destroy && caller == service));
    }
}
