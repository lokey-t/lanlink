package com.lanlink.app;

interface IShizukuShell {
    void start(int port, String secret) = 0;
    void stop() = 1;
    String startupError() = 2;
    void destroy() = 16777114;
}
