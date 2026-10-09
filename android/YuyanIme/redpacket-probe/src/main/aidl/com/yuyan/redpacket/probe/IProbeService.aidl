package com.yuyan.redpacket.probe;
interface IProbeService {
    String start() = 0;
    String status() = 1;
    String stop() = 2;
    void destroy() = 16777114;
}
